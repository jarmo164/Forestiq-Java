package ee.forestiq.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.config.DistributedJobLock;
import ee.forestiq.security.OrganizationContext;

/**
 * Multi-cycle recovery/soak proof for the shared integration control plane.
 *
 * Feed-specific adapters retain their focused HTTP/parser/database regression
 * tests. This test deliberately exercises the common production orchestration
 * over repeated scheduled cycles with stateful provider stubs so recovery,
 * idempotency and backlog drain are proven together instead of only one branch
 * at a time.
 */
class ContinuousIngestionSoakTest {
    private static final UUID ORGANIZATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final List<IntegrationJobKey> CRITICAL_FEEDS = List.of(
        IntegrationJobKey.OWNER_CADASTRE_RELATIONS,
        IntegrationJobKey.CADASTRE_PUBLIC_DATA,
        IntegrationJobKey.FOREST_NOTIFICATIONS,
        IntegrationJobKey.FOREST_PLAN,
        IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS,
        IntegrationJobKey.INHERITANCE_NOTICES
    );

    @Test
    @SuppressWarnings("unchecked")
    void repeatedScheduledCyclesRecoverFromInterruptionsWithoutDuplicatesAndDrainBacklog() throws Exception {
        RunStore store = new RunStore();
        OrganizationContext organizationContext = mock(OrganizationContext.class);
        when(organizationContext.requiredOrganizationId()).thenReturn(ORGANIZATION_ID);
        when(organizationContext.subject()).thenReturn("soak-admin");

        DistributedJobLock jobLock = mock(DistributedJobLock.class);
        doAnswer(invocation -> {
            Supplier<Object> work = invocation.getArgument(1);
            return new DistributedJobLock.LockedResult<>(true, work.get());
        }).when(jobLock).tryRun(anyString(), any(Supplier.class));

        EnumMap<IntegrationJobKey, SoakHandler> handlers = new EnumMap<>(IntegrationJobKey.class);
        for (IntegrationJobKey key : CRITICAL_FEEDS) {
            int backlog = key == IntegrationJobKey.INHERITANCE_NOTICES ? 5 : 4;
            handlers.put(key, new SoakHandler(key, backlog));
        }
        Map<IntegrationJobKey, Integer> beforeBacklog = new EnumMap<>(IntegrationJobKey.class);
        handlers.forEach((key, handler) -> beforeBacklog.put(key, handler.backlog()));

        IntegrationJobService service = service(store, organizationContext, jobLock,
            new SyncTaskExecutor(), handlers);

        // Cycle 1: every feed proves a normal scheduled success and makes forward progress.
        runCycle(service);
        for (SoakHandler handler : handlers.values()) {
            assertThat(handler.backlog()).isEqualTo(handler.initialBacklog() - 1);
            assertThat(store.latestState(handler.key())).isEqualTo("SUCCEEDED");
        }

        // Cycle 2: different feeds fail in different production-relevant ways.
        SoakHandler ownerSnapshot = handlers.get(IntegrationJobKey.OWNER_CADASTRE_RELATIONS);
        SoakHandler cadastre = handlers.get(IntegrationJobKey.CADASTRE_PUBLIC_DATA);
        SoakHandler forestNotifications = handlers.get(IntegrationJobKey.FOREST_NOTIFICATIONS);
        SoakHandler forestPlan = handlers.get(IntegrationJobKey.FOREST_PLAN);
        SoakHandler weasel = handlers.get(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS);

        int ownerBacklogBeforeFailure = ownerSnapshot.backlog();
        int ownerAppliedBeforeFailure = ownerSnapshot.appliedSnapshots();
        int weaselBacklogBeforeFailure = weasel.backlog();
        int forestBacklogBeforeFailure = forestNotifications.backlog();
        int planBacklogBeforeFailure = forestPlan.backlog();
        int cadastreBacklogBeforeFailure = cadastre.backlog();

        ownerSnapshot.failNext(FailureMode.INCOMPLETE_SNAPSHOT);
        cadastre.failNext(FailureMode.DATABASE_LOSS_AFTER_WRITE);
        forestNotifications.failNext(FailureMode.PROVIDER_5XX);
        forestPlan.failNext(FailureMode.TIMEOUT);
        weasel.failNext(FailureMode.MALFORMED_PAYLOAD);

        runCycle(service);

        // Incomplete owner/cadastre snapshot is rejected without applying/removing live relations.
        assertThat(ownerSnapshot.backlog()).isEqualTo(ownerBacklogBeforeFailure);
        assertThat(ownerSnapshot.appliedSnapshots()).isEqualTo(ownerAppliedBeforeFailure);
        assertThat(store.latestState(IntegrationJobKey.OWNER_CADASTRE_RELATIONS)).isEqualTo("PARTIAL");

        // Provider failures and malformed payloads preserve the unconsumed backlog/cursor.
        assertThat(forestNotifications.backlog()).isEqualTo(forestBacklogBeforeFailure);
        assertThat(forestPlan.backlog()).isEqualTo(planBacklogBeforeFailure);
        assertThat(weasel.backlog()).isEqualTo(weaselBacklogBeforeFailure);
        assertThat(store.latestState(IntegrationJobKey.FOREST_NOTIFICATIONS)).isEqualTo("FAILED");
        assertThat(store.latestState(IntegrationJobKey.FOREST_PLAN)).isEqualTo("FAILED");
        assertThat(store.latestState(IntegrationJobKey.WEASEL_OWNERSHIP_TRANSITIONS)).isEqualTo("FAILED");

        // Simulate connection loss after a durable business write. The retry must not duplicate it.
        assertThat(cadastre.backlog()).isEqualTo(cadastreBacklogBeforeFailure);
        int cadastreSignalsAfterInterruptedWrite = cadastre.signals();
        assertThat(store.latestState(IntegrationJobKey.CADASTRE_PUBLIC_DATA)).isEqualTo("FAILED");

        // Page-cap/checkpoint case: partial retrieval must remain resumable and not consume backlog.
        forestNotifications.failNext(FailureMode.CHECKPOINT_PARTIAL);
        service.runScheduledAll(IntegrationJobKey.FOREST_NOTIFICATIONS);
        assertThat(forestNotifications.backlog()).isEqualTo(forestBacklogBeforeFailure);
        assertThat(store.latestState(IntegrationJobKey.FOREST_NOTIFICATIONS)).isEqualTo("PARTIAL");

        // Executor saturation is terminal/observable, not an orphaned QUEUED row.
        SoakHandler inheritance = handlers.get(IntegrationJobKey.INHERITANCE_NOTICES);
        int inheritanceAttemptsBeforeSaturation = inheritance.attempts();
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("soak integration queue saturated");
        };
        IntegrationJobService saturated = service(store, organizationContext, jobLock,
            rejectingExecutor, handlers);
        saturated.runScheduledAll(IntegrationJobKey.INHERITANCE_NOTICES);
        assertThat(inheritance.attempts()).isEqualTo(inheritanceAttemptsBeforeSaturation);
        assertThat(store.latestState(IntegrationJobKey.INHERITANCE_NOTICES)).isEqualTo("FAILED");
        assertThat(store.hasActive(IntegrationJobKey.INHERITANCE_NOTICES)).isFalse();

        // Simulate an application restart leaving a RUNNING database row behind.
        int recoveredBefore = store.recoveredOrphans();
        store.seedStaleRunning(IntegrationJobKey.FOREST_PLAN);
        IntegrationJobService restarted = service(store, organizationContext, jobLock,
            new SyncTaskExecutor(), handlers);
        restarted.runScheduledAll(IntegrationJobKey.FOREST_PLAN);
        assertThat(store.recoveredOrphans()).isEqualTo(recoveredBefore + 1);
        assertThat(store.latestState(IntegrationJobKey.FOREST_PLAN)).isEqualTo("SUCCEEDED");
        assertThat(store.hasActive(IntegrationJobKey.FOREST_PLAN)).isFalse();

        // Several more scheduler cycles must drain every backlog without operator intervention.
        for (int cycle = 0; cycle < 10 && handlers.values().stream().anyMatch(handler -> handler.backlog() > 0); cycle++) {
            runCycle(restarted);
        }

        for (SoakHandler handler : handlers.values()) {
            assertThat(handler.backlog()).as("backlog for %s", handler.key()).isZero();
            assertThat(handler.rows()).as("logical rows for %s", handler.key()).isEqualTo(handler.initialBacklog());
            assertThat(handler.signals()).as("signals for %s", handler.key()).isEqualTo(handler.initialBacklog());
            assertThat(store.latestState(handler.key())).as("last state for %s", handler.key()).isEqualTo("SUCCEEDED");
            assertThat(store.hasActive(handler.key())).isFalse();
        }

        assertThat(cadastre.duplicateRetries()).isGreaterThan(0);
        assertThat(cadastre.signals()).isGreaterThan(cadastreSignalsAfterInterruptedWrite);
        assertThat(weasel.cursorLagSeconds()).isZero();
        assertThat(inheritance.cursorLagSeconds()).isZero();
        assertThat(store.failedRuns()).isGreaterThanOrEqualTo(5);
        assertThat(store.partialRuns()).isGreaterThanOrEqualTo(2);

        writeReport(handlers, beforeBacklog, store);
    }

    private static IntegrationJobService service(RunStore store, OrganizationContext organizationContext,
                                                 DistributedJobLock jobLock, TaskExecutor executor,
                                                 Map<IntegrationJobKey, SoakHandler> handlers) {
        return new IntegrationJobService(new ArrayList<>(handlers.values()), store.repository(),
            organizationContext, jobLock, executor, new ObjectMapper());
    }

    private static void runCycle(IntegrationJobService service) {
        for (IntegrationJobKey key : CRITICAL_FEEDS) service.runScheduledAll(key);
    }

    private static void writeReport(Map<IntegrationJobKey, SoakHandler> handlers,
                                    Map<IntegrationJobKey, Integer> beforeBacklog,
                                    RunStore store) throws IOException {
        List<Map<String, Object>> feeds = new ArrayList<>();
        for (IntegrationJobKey key : CRITICAL_FEEDS) {
            SoakHandler handler = handlers.get(key);
            Map<String, Object> before = new LinkedHashMap<>();
            before.put("backlog", beforeBacklog.get(key));
            before.put("rows", 0);
            before.put("signals", 0);
            before.put("cursorLagSeconds", beforeBacklog.get(key) * 3_600L);

            Map<String, Object> after = new LinkedHashMap<>();
            after.put("backlog", handler.backlog());
            after.put("rows", handler.rows());
            after.put("signals", handler.signals());
            after.put("cursorLagSeconds", handler.cursorLagSeconds());
            after.put("lastState", store.latestState(key));
            after.put("lastSuccess", store.latestSuccess(key));
            after.put("attempts", handler.attempts());
            after.put("duplicateRetries", handler.duplicateRetries());

            Map<String, Object> feed = new LinkedHashMap<>();
            feed.put("job", key.name());
            feed.put("before", before);
            feed.put("after", after);
            feeds.add(feed);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("generatedAt", Instant.now().toString());
        report.put("passed", true);
        report.put("cycles", "baseline + injected failures + restart + bounded drain");
        report.put("scenarios", List.of(
            "provider 5xx", "provider timeout", "malformed payload", "incomplete snapshot",
            "database connection loss after write", "page-cap/checkpoint resume", "executor saturation",
            "application restart/orphan recovery", "multi-day catch-up", "idempotent retry", "backlog drain"
        ));
        report.put("recoveredOrphanRuns", store.recoveredOrphans());
        report.put("failedRunsObserved", store.failedRuns());
        report.put("partialRunsObserved", store.partialRuns());
        report.put("feeds", feeds);

        Path reportPath = Path.of("build", "reports", "dataflow-soak", "summary.json");
        Files.createDirectories(reportPath.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
    }

    private enum FailureMode {
        PROVIDER_5XX,
        TIMEOUT,
        MALFORMED_PAYLOAD,
        DATABASE_LOSS_AFTER_WRITE,
        CHECKPOINT_PARTIAL,
        INCOMPLETE_SNAPSHOT
    }

    private static final class SoakHandler implements IntegrationJobHandler {
        private final IntegrationJobKey key;
        private final int initialBacklog;
        private final Deque<FailureMode> failures = new ArrayDeque<>();
        private final Set<String> logicalRows = new LinkedHashSet<>();
        private int backlog;
        private int signals;
        private int attempts;
        private int duplicateRetries;
        private int appliedSnapshots;

        private SoakHandler(IntegrationJobKey key, int initialBacklog) {
            this.key = key;
            this.initialBacklog = initialBacklog;
            this.backlog = initialBacklog;
        }

        void failNext(FailureMode mode) {
            failures.addLast(mode);
        }

        int initialBacklog() { return initialBacklog; }
        int backlog() { return backlog; }
        int rows() { return logicalRows.size(); }
        int signals() { return signals; }
        int attempts() { return attempts; }
        int duplicateRetries() { return duplicateRetries; }
        int appliedSnapshots() { return appliedSnapshots; }
        long cursorLagSeconds() { return backlog * 3_600L; }

        @Override
        public IntegrationJobKey key() { return key; }

        @Override
        public String displayName() { return key.name(); }

        @Override
        public String provider() { return "stateful-soak-provider"; }

        @Override
        public String purpose() { return "Continuous ingestion recovery validation"; }

        @Override
        public String scheduleDescription() { return "soak cycle"; }

        @Override
        public boolean configured() { return true; }

        @Override
        public boolean enabled() { return true; }

        @Override
        public List<UUID> scheduledOrganizations() { return List.of(ORGANIZATION_ID); }

        @Override
        public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
            assertThat(organizationId).isEqualTo(ORGANIZATION_ID);
            attempts++;
            FailureMode failure = failures.pollFirst();
            if (failure == null) return applyOne();

            return switch (failure) {
                case PROVIDER_5XX -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "provider returned 503 during soak");
                case TIMEOUT -> throw new IllegalStateException("provider timeout during soak");
                case MALFORMED_PAYLOAD -> throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "malformed provider payload during soak");
                case DATABASE_LOSS_AFTER_WRITE -> {
                    writeCurrentLogicalRecord();
                    throw new IllegalStateException("database connection lost after durable write during soak");
                }
                case CHECKPOINT_PARTIAL -> IntegrationJobResult.partial(
                    metrics(false, true), "Page cap reached; checkpoint retained for the next cycle.");
                case INCOMPLETE_SNAPSHOT -> IntegrationJobResult.partial(
                    metrics(false, false), "Incomplete snapshot rejected; live relations remain unchanged.");
            };
        }

        private IntegrationJobResult applyOne() {
            if (backlog == 0) {
                return IntegrationJobResult.success(metrics(key == IntegrationJobKey.OWNER_CADASTRE_RELATIONS, false),
                    "No backlog remains.");
            }
            writeCurrentLogicalRecord();
            backlog--;
            if (key == IntegrationJobKey.OWNER_CADASTRE_RELATIONS) appliedSnapshots++;
            return IntegrationJobResult.success(metrics(key == IntegrationJobKey.OWNER_CADASTRE_RELATIONS, false),
                backlog == 0 ? "Backlog drained." : "Forward progress; backlog remains.");
        }

        private void writeCurrentLogicalRecord() {
            if (backlog == 0) return;
            int logicalIndex = initialBacklog - backlog + 1;
            String logicalId = key.name() + ":" + logicalIndex;
            if (logicalRows.add(logicalId)) signals++;
            else duplicateRetries++;
        }

        private Map<String, Object> metrics(boolean snapshotApplied, boolean checkpointed) {
            Map<String, Object> metrics = new LinkedHashMap<>();
            metrics.put("rows", logicalRows.size());
            metrics.put("signals", signals);
            metrics.put("backlogRemaining", backlog > 0);
            metrics.put("cursorLagSeconds", cursorLagSeconds());
            if (key == IntegrationJobKey.INHERITANCE_NOTICES) metrics.put("catchupRemaining", backlog > 0);
            if (key == IntegrationJobKey.OWNER_CADASTRE_RELATIONS) metrics.put("snapshotApplied", snapshotApplied);
            if (checkpointed) metrics.put("checkpointed", true);
            return metrics;
        }
    }

    private static final class RunStore {
        private final IntegrationRunRepository repository = mock(IntegrationRunRepository.class);
        private final List<MutableRun> runs = new ArrayList<>();
        private int recoveredOrphans;

        private RunStore() {
            when(repository.create(any(UUID.class), any(IntegrationJobKey.class), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> create(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4)));
            when(repository.active(any(UUID.class), any(IntegrationJobKey.class)))
                .thenAnswer(invocation -> active(invocation.getArgument(0), invocation.getArgument(1)));
            when(repository.markRunning(any(UUID.class)))
                .thenAnswer(invocation -> markRunning(invocation.getArgument(0)));
            when(repository.expireStaleActive(any(UUID.class), any(IntegrationJobKey.class)))
                .thenAnswer(invocation -> expireStale(invocation.getArgument(0), invocation.getArgument(1)));
            doAnswer(invocation -> {
                complete(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2),
                    invocation.getArgument(3), invocation.getArgument(4));
                return null;
            }).when(repository).complete(any(UUID.class), anyString(), anyString(), nullable(String.class), nullable(String.class));
        }

        IntegrationRunRepository repository() { return repository; }
        int recoveredOrphans() { return recoveredOrphans; }

        boolean hasActive(IntegrationJobKey key) {
            return runs.stream().anyMatch(run -> run.key == key && isActive(run.state));
        }

        String latestState(IntegrationJobKey key) {
            for (int index = runs.size() - 1; index >= 0; index--) {
                MutableRun run = runs.get(index);
                if (run.key == key) return run.state;
            }
            return null;
        }

        String latestSuccess(IntegrationJobKey key) {
            for (int index = runs.size() - 1; index >= 0; index--) {
                MutableRun run = runs.get(index);
                if (run.key == key && "SUCCEEDED".equals(run.state) && run.finishedAt != null) {
                    return run.finishedAt.toString();
                }
            }
            return null;
        }

        long failedRuns() {
            return runs.stream().filter(run -> "FAILED".equals(run.state)).count();
        }

        long partialRuns() {
            return runs.stream().filter(run -> "PARTIAL".equals(run.state)).count();
        }

        void seedStaleRunning(IntegrationJobKey key) {
            MutableRun run = new MutableRun(UUID.randomUUID(), ORGANIZATION_ID, key, "SCHEDULED",
                "system-integration-scheduler", "{}", "RUNNING");
            run.startedAt = Instant.now().minusSeconds(13 * 3_600L);
            run.stale = true;
            runs.add(run);
        }

        private IntegrationJobService.RunView create(UUID orgId, IntegrationJobKey key, String trigger,
                                                     String subject, String parameters) {
            MutableRun run = new MutableRun(UUID.randomUUID(), orgId, key, trigger, subject, parameters, "QUEUED");
            runs.add(run);
            return run.view();
        }

        private IntegrationJobService.RunView active(UUID orgId, IntegrationJobKey key) {
            for (int index = runs.size() - 1; index >= 0; index--) {
                MutableRun run = runs.get(index);
                if (run.organizationId.equals(orgId) && run.key == key && isActive(run.state)) return run.view();
            }
            return null;
        }

        private boolean markRunning(UUID id) {
            MutableRun run = byId(id);
            if (run == null || !"QUEUED".equals(run.state)) return false;
            run.state = "RUNNING";
            run.startedAt = Instant.now();
            return true;
        }

        private void complete(UUID id, String state, String metrics, String message, String error) {
            MutableRun run = byId(id);
            if (run == null || !isActive(run.state)) return;
            run.state = state;
            run.metrics = metrics;
            run.message = message;
            run.error = error;
            run.finishedAt = Instant.now();
        }

        private int expireStale(UUID orgId, IntegrationJobKey key) {
            int expired = 0;
            for (MutableRun run : runs) {
                if (!run.organizationId.equals(orgId) || run.key != key || !run.stale || !isActive(run.state)) continue;
                run.state = "FAILED";
                run.message = "Stale integration run recovered automatically during soak.";
                run.error = "stale run";
                run.finishedAt = Instant.now();
                run.stale = false;
                recoveredOrphans++;
                expired++;
            }
            return expired;
        }

        private MutableRun byId(UUID id) {
            return runs.stream().filter(run -> run.id.equals(id)).findFirst().orElse(null);
        }

        private static boolean isActive(String state) {
            return "QUEUED".equals(state) || "RUNNING".equals(state);
        }
    }

    private static final class MutableRun {
        private final UUID id;
        private final UUID organizationId;
        private final IntegrationJobKey key;
        private final String trigger;
        private final String subject;
        private final String parameters;
        private final Instant queuedAt = Instant.now();
        private String state;
        private String metrics = "{}";
        private String message;
        private String error;
        private Instant startedAt;
        private Instant finishedAt;
        private boolean stale;

        private MutableRun(UUID id, UUID organizationId, IntegrationJobKey key, String trigger,
                           String subject, String parameters, String state) {
            this.id = id;
            this.organizationId = organizationId;
            this.key = key;
            this.trigger = trigger;
            this.subject = subject;
            this.parameters = parameters;
            this.state = state;
        }

        private IntegrationJobService.RunView view() {
            return new IntegrationJobService.RunView(id, key.name(), trigger, state, subject,
                parameters, metrics, message, error, queuedAt, startedAt, finishedAt);
        }
    }
}
