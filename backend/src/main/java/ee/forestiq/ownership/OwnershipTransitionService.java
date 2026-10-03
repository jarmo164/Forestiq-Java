package ee.forestiq.ownership;

import static org.jooq.impl.DSL.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.security.OrganizationContext;

/**
 * Imports Weasel transition pages with network I/O outside database transactions.
 * Each fetched page is applied atomically and cursor/failure state is persisted in
 * a separate transaction so provider failures cannot erase operational evidence.
 */
@Service
public class OwnershipTransitionService {
    private static final Logger log = LoggerFactory.getLogger(OwnershipTransitionService.class);
    private static final String SYSTEM_SUBJECT = "system-weasel-sync";

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;
    private final OrganizationContext organizationContext;
    private final WeaselProperties properties;
    private final WeaselOwnershipClient client;

    public OwnershipTransitionService(DSLContext dsl, ObjectMapper objectMapper, OrganizationContext organizationContext,
                                      WeaselProperties properties, WeaselOwnershipClient client) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
        this.organizationContext = organizationContext;
        this.properties = properties;
        this.client = client;
    }

    public SyncRun syncCurrentOrganization() {
        return syncOrganization(organizationContext.requiredOrganizationId(), "MANUAL");
    }

    public SyncRun syncOrganization(UUID organizationId, String trigger) {
        if (!properties.isConfigured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Weaseli omandiüleminekute teenusekonto või seotud organisatsioon ei ole seadistatud.");
        UUID configuredOrganizationId;
        try { configuredOrganizationId = UUID.fromString(properties.getOrganizationId()); }
        catch (IllegalArgumentException exception) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "WEASEL_ORGANIZATION_ID peab olema ForestIQ organisatsiooni UUID."); }
        if (!configuredOrganizationId.equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Weaseli teenusetoken ei ole seotud selle ForestIQ organisatsiooniga.");
        }

        var checkpoint = dsl.select(
                field(name("cursor_time"), Instant.class),
                field(name("window_end"), Instant.class),
                field(name("next_page"), Integer.class))
            .from(table("weasel_transition_sync_checkpoint"))
            .where(field(name("organization_id"), UUID.class).eq(organizationId))
            .fetchOne();
        Instant cursor = checkpoint == null ? null : checkpoint.value1();
        Instant resumeWindowEnd = checkpoint == null ? null : checkpoint.value2();
        Integer resumePage = checkpoint == null ? null : checkpoint.value3();

        Instant now = resumeWindowEnd != null && resumePage != null ? resumeWindowEnd : Instant.now();
        Instant from = cursor == null ? now.minus(Math.max(1, properties.getInitialLookbackDays()), ChronoUnit.DAYS)
            : cursor.minus(Math.max(1, properties.getOverlapHours()), ChronoUnit.HOURS);
        int pageSize = Math.max(1, Math.min(properties.getPageSize(), 250));
        int maxPages = Math.max(1, properties.getMaxPagesPerRun());
        int startPage = resumeWindowEnd != null && resumePage != null ? Math.max(1, resumePage) : 1;
        int imported = 0;
        int matched = 0;
        int pages = 0;
        int lastPage = startPage - 1;
        boolean completed = false;
        try {
            for (int offset = 0; offset < maxPages; offset++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Weaseli sünkroonimine katkestati enne järgmise lehe laadimist.");
                }
                int page = startPage + offset;
                long fetchStarted = System.nanoTime();
                WeaselOwnershipClient.TransitionPage response;
                try {
                    response = client.fetchTransitions(from, now, pageSize, page);
                } finally {
                    Metrics.timer("forestiq.integration.fetch.duration", "provider", "weasel")
                        .record(System.nanoTime() - fetchStarted, TimeUnit.NANOSECONDS);
                }
                pages++;
                lastPage = page;

                long applyStarted = System.nanoTime();
                PageImport pageImport;
                try {
                    pageImport = dsl.transactionResult(configuration -> {
                        DSLContext tx = configuration.dsl();
                        int pageMatched = 0;
                        int pageImported = 0;
                        for (WeaselOwnershipClient.OwnershipTransition transition : response.items()) {
                            TransitionImport outcome = importTransition(tx, organizationId, transition);
                            if (outcome.matched()) pageMatched++;
                            if (outcome.imported()) pageImported++;
                        }
                        return new PageImport(pageMatched, pageImported);
                    });
                } finally {
                    Metrics.timer("forestiq.integration.apply.duration", "provider", "weasel")
                        .record(System.nanoTime() - applyStarted, TimeUnit.NANOSECONDS);
                }
                matched += pageImport.matched();
                imported += pageImport.imported();
                if (response.totalItems() <= page * pageSize) {
                    completed = true;
                    break;
                }
            }
            if (!completed) {
                int nextPage = lastPage + 1;
                String message = "Weaseli sünkroonimine jõudis ühe jooksu leheküljepiirini; sama akent jätkatakse leheküljelt " + nextPage + ".";
                persistPartialCheckpoint(organizationId, now, nextPage, message);
                return new SyncRun(trigger, from, now, pages, matched, imported, 0, message);
            }
            persistSuccessCheckpoint(organizationId, now);
            return new SyncRun(trigger, from, now, pages, matched, imported, 0, "Weaseli omandiüleminekud sünkroniseeriti.");
        } catch (RuntimeException exception) {
            Metrics.counter("forestiq.integration.sync.failures", "provider", "weasel").increment();
            persistFailureCheckpoint(organizationId, exception);
            throw exception;
        }
    }

    public List<SyncRun> syncAllOrganizationPortfolios(String trigger) {
        if (!properties.isConfigured()) return List.of(new SyncRun(trigger, null, null, 0, 0, 0, 0, "Weaseli teenus või seotud organisatsioon ei ole seadistatud."));
        try {
            UUID organizationId = UUID.fromString(properties.getOrganizationId());
            return List.of(syncOrganization(organizationId, trigger));
        } catch (RuntimeException exception) {
            return List.of(new SyncRun(trigger, null, null, 0, 0, 0, 1, "Weaseli sünkroniseerimine ebaõnnestus: " + safeMessage(exception)));
        }
    }

    @Transactional(readOnly = true)
    public List<OwnershipTransitionEvent> eventsForOwner(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        return dsl.fetch("""
            SELECT weasel_transition_id, cadastral_code, transition_at, old_owner_count, new_owner_count, relationship_direction, imported_at
            FROM ownership_transition_event
            WHERE organization_id=? AND owner_id=?
            ORDER BY transition_at DESC
            LIMIT 50
            """, orgId, ownerId).map(record -> new OwnershipTransitionEvent(
                record.get("weasel_transition_id", Long.class), record.get("cadastral_code", String.class), record.get("transition_at", Instant.class),
                record.get("old_owner_count", Short.class), record.get("new_owner_count", Short.class), record.get("relationship_direction", String.class), record.get("imported_at", Instant.class)));
    }

    private TransitionImport importTransition(DSLContext tx, UUID orgId, WeaselOwnershipClient.OwnershipTransition transition) {
        List<ActiveParcelOwner> activeOwners = tx.select(
                field(name("p", "id"), UUID.class),
                field(name("o", "id"), UUID.class),
                field(name("o", "personal_or_registry_code"), String.class))
            .from(table("land_parcel").as("p"))
            .join(table("owner_parcel_relation").as("opr")).on(
                field(name("opr", "parcel_id"), UUID.class).eq(field(name("p", "id"), UUID.class))
                    .and(field(name("opr", "organization_id"), UUID.class).eq(field(name("p", "organization_id"), UUID.class)))
                    .and(field(name("opr", "active"), Boolean.class).isTrue()))
            .join(table("owner").as("o")).on(
                field(name("o", "id"), UUID.class).eq(field(name("opr", "owner_id"), UUID.class))
                    .and(field(name("o", "organization_id"), UUID.class).eq(field(name("opr", "organization_id"), UUID.class))))
            .where(field(name("p", "organization_id"), UUID.class).eq(orgId)
                .and(field(name("p", "cadastral_code"), String.class).eq(transition.cadastralCode())))
            .fetch(record -> new ActiveParcelOwner(record.value1(), record.value2(), record.value3()));

        List<ActiveParcelOwner> relevantOwners = activeOwners.stream()
            .filter(owner -> isRelevant(owner.identifier(), transition))
            .toList();
        if (relevantOwners.isEmpty()) return new TransitionImport(false, false);

        int oldOwnerCount = transition.oldOwnerIdentifiers().size();
        int newOwnerCount = transition.newOwnerIdentifiers().size();
        boolean imported = false;

        for (ActiveParcelOwner owner : relevantOwners) {
            boolean knownWasOwner = transition.oldOwnerIdentifiers().contains(owner.identifier());
            boolean knownIsOwner = transition.newOwnerIdentifiers().contains(owner.identifier());
            String direction = knownWasOwner && knownIsOwner ? "MIXED" : knownWasOwner ? "OUTGOING" : "INCOMING";

            boolean eventExists = tx.fetchExists(table("ownership_transition_event"),
                field(name("organization_id"), UUID.class).eq(orgId)
                    .and(field(name("weasel_transition_id"), Long.class).eq(transition.externalId()))
                    .and(field(name("owner_id"), UUID.class).eq(owner.ownerId())));
            if (!eventExists) {
                tx.insertInto(table("ownership_transition_event"))
                    .columns(field(name("organization_id")), field(name("parcel_id")), field(name("owner_id")), field(name("weasel_transition_id")), field(name("cadastral_code")),
                        field(name("transition_at")), field(name("old_owner_count")), field(name("new_owner_count")), field(name("relationship_direction")), field(name("imported_by_subject")))
                    .values(orgId, owner.parcelId(), owner.ownerId(), transition.externalId(), transition.cadastralCode(), transition.occurredAt(),
                        oldOwnerCount, newOwnerCount, direction, SYSTEM_SUBJECT)
                    .execute();
                imported = true;
            }

            String directionLabel = switch (direction) {
                case "OUTGOING" -> "senise omaniku väljumine";
                case "INCOMING" -> "uue omaniku lisandumine";
                case "MIXED" -> "omanike koosseisu muutus";
                default -> "omanike koosseisu muutus";
            };
            String summary = "Weaseli andmeallikas tuvastas katastriüksuse " + transition.cadastralCode() + " omandimuutuse (" + directionLabel + ").";
            String signalPayload = safeJson(Map.of(
                "weaselTransitionId", transition.externalId(),
                "cadastralCode", transition.cadastralCode(),
                "transitionAt", transition.occurredAt().toString(),
                "direction", direction,
                "oldOwnerCount", oldOwnerCount,
                "newOwnerCount", newOwnerCount
            ));
            boolean signalExists = tx.fetchExists(table("data_change_signal"),
                field(name("organization_id"), UUID.class).eq(orgId)
                    .and(field(name("owner_id"), UUID.class).eq(owner.ownerId()))
                    .and(field(name("source_registry"), String.class).eq("WEASEL"))
                    .and(field(name("source_key"), String.class).eq(String.valueOf(transition.externalId()))));
            if (!signalExists) {
                tx.insertInto(table("data_change_signal"))
                    .columns(field(name("id")), field(name("organization_id")), field(name("owner_id")), field(name("parcel_id")), field(name("signal_type")),
                        field(name("source_registry")), field(name("source_key")), field(name("summary")), field(name("payload")), field(name("created_by_subject")))
                    .values(UUID.randomUUID(), orgId, owner.ownerId(), owner.parcelId(), "OWNERSHIP_TRANSITION", "WEASEL", String.valueOf(transition.externalId()), summary,
                        field("?::jsonb", Object.class, signalPayload), SYSTEM_SUBJECT).execute();
            }
            if (!eventExists) {
                tx.insertInto(table("owner_activity"))
                    .columns(field(name("organization_id")), field(name("owner_id")), field(name("activity_type")), field(name("content")), field(name("created_by_subject")))
                    .values(orgId, owner.ownerId(), "SYSTEM", summary, SYSTEM_SUBJECT).execute();
            }
        }
        return new TransitionImport(true, imported);
    }

    private void persistSuccessCheckpoint(UUID orgId, Instant cursor) {
        dsl.transaction(configuration -> upsertCheckpoint(configuration.dsl(), orgId, cursor, null));
    }

    private void persistPartialCheckpoint(UUID orgId, Instant windowEnd, int nextPage, String message) {
        dsl.transaction(configuration -> upsertPartialCheckpoint(configuration.dsl(), orgId, windowEnd, nextPage, message));
    }

    private void persistFailureCheckpoint(UUID orgId, RuntimeException original) {
        try {
            dsl.transaction(configuration -> upsertCheckpointFailure(configuration.dsl(), orgId, original.getMessage()));
        } catch (RuntimeException checkpointFailure) {
            log.error("Failed to persist Weasel failure checkpoint for organization {}", orgId, checkpointFailure);
        }
    }

    private void upsertCheckpoint(DSLContext tx, UUID orgId, Instant cursor, String error) {
        Instant now = Instant.now();
        int updated = tx.update(table("weasel_transition_sync_checkpoint"))
            .set(field(name("cursor_time")), cursor)
            .set(field(name("window_end")), (Instant) null)
            .set(field(name("next_page")), (Integer) null)
            .set(field(name("last_success_at")), now)
            .set(field(name("last_failure_at")), (Instant) null)
            .set(field(name("last_error")), error)
            .set(field(name("updated_at")), now)
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .execute();
        if (updated == 0) {
            tx.insertInto(table("weasel_transition_sync_checkpoint"))
                .columns(field(name("organization_id")), field(name("cursor_time")), field(name("window_end")), field(name("next_page")),
                    field(name("last_success_at")), field(name("last_failure_at")), field(name("last_error")), field(name("updated_at")))
                .values(orgId, cursor, null, null, now, null, error, now)
                .execute();
        }
    }

    private void upsertPartialCheckpoint(DSLContext tx, UUID orgId, Instant windowEnd, int nextPage, String message) {
        Instant fallback = windowEnd.minus(Math.max(1, properties.getInitialLookbackDays()), ChronoUnit.DAYS);
        Instant now = Instant.now();
        int updated = tx.update(table("weasel_transition_sync_checkpoint"))
            .set(field(name("window_end")), windowEnd)
            .set(field(name("next_page")), nextPage)
            .set(field(name("last_error")), safeMessage(message))
            .set(field(name("updated_at")), now)
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .execute();
        if (updated == 0) {
            tx.insertInto(table("weasel_transition_sync_checkpoint"))
                .columns(field(name("organization_id")), field(name("cursor_time")), field(name("window_end")),
                    field(name("next_page")), field(name("last_error")), field(name("updated_at")))
                .values(orgId, fallback, windowEnd, nextPage, safeMessage(message), now)
                .execute();
        }
    }

    private void upsertCheckpointFailure(DSLContext tx, UUID orgId, String error) {
        Instant fallback = Instant.now().minus(Math.max(1, properties.getInitialLookbackDays()), ChronoUnit.DAYS);
        Instant now = Instant.now();
        String safeError = safeMessage(error);
        int updated = tx.update(table("weasel_transition_sync_checkpoint"))
            .set(field(name("last_failure_at")), now)
            .set(field(name("last_error")), safeError)
            .set(field(name("updated_at")), now)
            .where(field(name("organization_id"), UUID.class).eq(orgId))
            .execute();
        if (updated == 0) {
            tx.insertInto(table("weasel_transition_sync_checkpoint"))
                .columns(field(name("organization_id")), field(name("cursor_time")), field(name("last_failure_at")),
                    field(name("last_error")), field(name("updated_at")))
                .values(orgId, fallback, now, safeError, now)
                .execute();
        }
    }

    private boolean isRelevant(String knownIdentifier, WeaselOwnershipClient.OwnershipTransition transition) {
        return knownIdentifier != null && (transition.oldOwnerIdentifiers().contains(knownIdentifier) || transition.newOwnerIdentifiers().contains(knownIdentifier));
    }

    private String safeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (Exception ignored) { return "{}"; }
    }

    private static String safeMessage(String message) { return message == null || message.isBlank() ? "Weaseli teenus ei vastanud" : message; }
    private static String safeMessage(RuntimeException exception) { return safeMessage(exception.getMessage()); }

    private record ActiveParcelOwner(UUID parcelId, UUID ownerId, String identifier) { }
    private record TransitionImport(boolean matched, boolean imported) { }
    private record PageImport(int matched, int imported) { }
    public record SyncRun(String trigger, Instant from, Instant to, int pages, int matched, int imported, int failed, String message) { }
    public record OwnershipTransitionEvent(long weaselTransitionId, String cadastralCode, Instant transitionAt,
                                           short oldOwnerCount, short newOwnerCount, String relationshipDirection, Instant importedAt) { }
}
