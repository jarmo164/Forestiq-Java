package ee.forestiq.integration;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.security.OrganizationContext;

@Service
public class IntegrationJobService {
    private static final String SYSTEM_SUBJECT = "system-integration-scheduler";
    private final Map<IntegrationJobKey, IntegrationJobHandler> handlers;
    private final IntegrationRunRepository runs;
    private final OrganizationContext organizationContext;
    private final DistributedJobLock jobLock;
    private final TaskExecutor executor;
    private final IntegrationRunParameterCodec parameterCodec;
    private final IntegrationExecutionRouter executionRouter;
    private final IntegrationJobHealthService healthService;

    /**
     * `all` preserves the single-process developer/test topology. Production
     * deployments use `api`, `scheduler` and `worker` so HTTP threads never run
     * provider imports. Tests that construct this service directly intentionally
     * keep the backward-compatible `all` default.
     */
    @Value("${forestiq.runtime.role:all}")
    private String runtimeRole = "all";

    @Value("${forestiq.runtime.python-job-keys:}")
    private String pythonJobKeys = "";

    public IntegrationJobService(List<IntegrationJobHandler> handlers, IntegrationRunRepository runs,
                                 OrganizationContext organizationContext, DistributedJobLock jobLock,
                                 @Qualifier("integrationTaskExecutor") TaskExecutor executor, ObjectMapper objectMapper) {
        this.handlers = handlers.stream().collect(Collectors.toUnmodifiableMap(
            IntegrationJobHandler::key, Function.identity()));
        this.runs = runs;
        this.organizationContext = organizationContext;
        this.jobLock = jobLock;
        this.executor = executor;
        this.parameterCodec = new IntegrationRunParameterCodec(objectMapper);
        this.executionRouter = new IntegrationExecutionRouter(() -> runtimeRole, () -> pythonJobKeys);
        this.healthService = new IntegrationJobHealthService(runs, parameterCodec);
    }

    public List<JobView> jobsForCurrentOrganization() {
        UUID orgId = organizationContext.requiredOrganizationId();
        return handlers.values().stream()
            .sorted(Comparator.comparing(handler -> handler.key().name()))
            .map(handler -> view(orgId, handler))
            .toList();
    }

    public RunView startManual(IntegrationJobKey key, Map<String, String> parameters) {
        UUID orgId = organizationContext.requiredOrganizationId();
        IntegrationJobHandler handler = requireHandler(key);
        if (!handler.manualSupported()) throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Seda tööd ei saa käsitsi käivitada.");
        if (!handler.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Andmeallikas ei ole seadistatud.");
        recoverStaleActive(orgId, key);
        if (runs.active(orgId, key) != null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Sama sünkroonimine juba töötab.");
        Map<String, String> safeParameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        RunView queued;
        try {
            queued = runs.create(orgId, key, "MANUAL", organizationContext.subject(), json(safeParameters));
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sama sünkroonimine juba töötab.", exception);
        }
        dispatchInlineWhenEnabled(queued.id(), orgId, handler, safeParameters,
            "Sünkroonimist ei õnnestunud tööjärjekorda lisada.");
        return queued;
    }

    public void runScheduledAll(IntegrationJobKey key) {
        IntegrationJobHandler handler = requireHandler(key);
        if (!handler.enabled() || !handler.configured()) return;
        for (UUID orgId : handler.scheduledOrganizations()) {
            recoverStaleActive(orgId, key);
            if (runs.active(orgId, key) != null) continue;
            RunView run;
            try {
                run = runs.create(orgId, key, "SCHEDULED", SYSTEM_SUBJECT, "{}");
            } catch (DataIntegrityViolationException ignored) {
                // Another replica or manual request won the active-run constraint.
                continue;
            }
            try {
                dispatchInlineWhenEnabled(run.id(), orgId, handler, Map.of(),
                    "Ajastatud sünkroonimist ei õnnestunud tööjärjekorda lisada.");
            } catch (ResponseStatusException ignored) {
                // The durable run has already been marked FAILED. A saturated
                // legacy all-in-one executor must not abort unrelated schedules.
            }
        }
    }

    /**
     * Executes a row that a dedicated worker has atomically claimed from the
     * PostgreSQL durable queue. The database row is already RUNNING here, so it
     * must not be claimed a second time.
     */
    void executeClaimed(IntegrationQueueRepository.ClaimedRun claimed) {
        IntegrationJobKey key;
        try {
            key = IntegrationJobKey.valueOf(claimed.jobKey());
        } catch (IllegalArgumentException exception) {
            runs.complete(claimed.id(), "FAILED", "{}", "Tundmatu sünkroonimistöö.", claimed.jobKey());
            return;
        }
        IntegrationJobHandler handler = handlers.get(key);
        if (handler == null) {
            runs.complete(claimed.id(), "FAILED", "{}", "Sünkroonimistöö handler puudub.", key.name());
            return;
        }
        execute(claimed.id(), claimed.organizationId(), handler, parameters(claimed.parameters()), true);
    }

    public RunView run(UUID id) {
        RunView result = runs.get(organizationContext.requiredOrganizationId(), id);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sünkroonimise käivitust ei leitud.");
        return result;
    }

    public List<RunView> recent(IntegrationJobKey key, int limit) {
        requireHandler(key);
        return runs.recent(organizationContext.requiredOrganizationId(), key, limit);
    }

    private void dispatchInlineWhenEnabled(UUID runId, UUID orgId, IntegrationJobHandler handler,
                                           Map<String, String> parameters, String rejectionMessage) {
        if (!executionRouter.inlineExecutionEnabled() || executionRouter.routedToPython(handler.key())) return;
        try {
            executor.execute(() -> execute(runId, orgId, handler, parameters, false));
        } catch (RuntimeException exception) {
            runs.complete(runId, "FAILED", "{}", rejectionMessage, safeError(exception));
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, rejectionMessage, exception);
        }
    }

    Set<String> javaClaimableJobKeys() {
        return executionRouter.javaClaimableJobKeys(handlers.keySet());
    }

    private void recoverStaleActive(UUID orgId, IntegrationJobKey key) {
        try {
            jobLock.tryRun(lockName(orgId, key), () -> {
                runs.expireStaleActive(orgId, key);
                return Boolean.TRUE;
            });
        } catch (RuntimeException ignored) {
            // Recovery is best-effort. An active row still blocks a replacement safely.
        }
    }

    private void execute(UUID runId, UUID orgId, IntegrationJobHandler handler,
                         Map<String, String> parameters, boolean alreadyClaimed) {
        try {
            var locked = jobLock.tryRun(lockName(orgId, handler.key()), () -> {
                if (!alreadyClaimed && !runs.markRunning(runId)) return Boolean.FALSE;
                try {
                    IntegrationJobResult result = handler.run(orgId, parameters);
                    runs.complete(runId, result.outcome().name(), json(result.metrics()), result.message(), null);
                } catch (RuntimeException exception) {
                    runs.complete(runId, "FAILED", "{}", "Sünkroonimine ebaõnnestus.", safeError(exception));
                }
                return Boolean.TRUE;
            });
            if (!locked.acquired()) {
                runs.complete(runId, "SKIPPED", "{}", "Teine rakenduse eksemplar juba käivitas selle töö.", null);
            }
        } catch (RuntimeException exception) {
            runs.complete(runId, "FAILED", "{}", "Sünkroonimise lukustamine ebaõnnestus.", safeError(exception));
        }
    }

    private static String lockName(UUID orgId, IntegrationJobKey key) {
        return "forestiq:integration:" + orgId + ":" + key.name();
    }

    private JobView view(UUID orgId, IntegrationJobHandler handler) {
        IntegrationJobKey key = handler.key();
        String configurationState = !handler.configured() ? "MISCONFIGURED" : handler.enabled() ? "ENABLED" : "DISABLED";
        RunView latest = runs.latest(orgId, key);
        RunView latestSuccess = runs.latestSuccess(orgId, key);
        RunView current = runs.active(orgId, key);
        HealthView health = healthService.health(orgId, key, configurationState, latest, latestSuccess, current);
        return new JobView(key.name(), handler.displayName(), handler.provider(), handler.purpose(),
            handler.scheduleDescription(), configurationState, handler.manualSupported(), latest,
            latestSuccess, current, health);
    }

    private IntegrationJobHandler requireHandler(IntegrationJobKey key) {
        IntegrationJobHandler handler = handlers.get(key);
        if (handler == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tundmatu sünkroonimistöö.");
        return handler;
    }

    private Map<String, String> parameters(String json) {
        return parameterCodec.parameters(json);
    }

    private String json(Object value) {
        return parameterCodec.toJson(value);
    }

    static String safeError(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
        message = message.replaceAll("(?i)(token|password|authorization|secret)\\s*[:=]\\s*[^,; ]+", "$1=[redacted]");
        message = message.replaceAll("[\\r\\n]+", " ").trim();
        return message.substring(0, Math.min(500, message.length()));
    }

    public record JobView(String key, String displayName, String provider, String purpose,
                          String schedule, String configurationState, boolean manualSupported,
                          RunView latestRun, RunView latestSuccess, RunView currentRun, HealthView health) { }

    public record HealthView(String state, long freshnessSloSeconds, Long latestSuccessAgeSeconds,
                             Long currentRunAgeSeconds, int failureStreak, boolean backlogRemaining,
                             Long cursorLagSeconds, String reason) { }

    public record RunView(UUID id, String jobKey, String trigger, String state, String requestedBy,
                          String parameters, String metrics, String message, String errorSummary,
                          Instant queuedAt, Instant startedAt, Instant finishedAt) { }
}
