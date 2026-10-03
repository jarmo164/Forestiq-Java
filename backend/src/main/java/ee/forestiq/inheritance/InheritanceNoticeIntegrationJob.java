package ee.forestiq.inheritance;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.integration.IntegrationJobHandler;
import ee.forestiq.integration.IntegrationJobKey;
import ee.forestiq.integration.IntegrationJobResult;

@Component
class InheritanceNoticeIntegrationJob implements IntegrationJobHandler {
    private final DSLContext dsl;
    private final InheritanceSourceProperties properties;
    private final InheritanceNoticeIngestionService service;

    InheritanceNoticeIntegrationJob(DSLContext dsl, InheritanceSourceProperties properties,
                                    InheritanceNoticeIngestionService service) {
        this.dsl = dsl;
        this.properties = properties;
        this.service = service;
    }

    public IntegrationJobKey key() { return IntegrationJobKey.INHERITANCE_NOTICES; }
    public String displayName() { return "Pärimisteated"; }
    public String provider() { return "Ametlikud Teadaanded"; }
    public String purpose() { return "Pärimismenetluse algatamise teadete sobitamine omanikega"; }
    public String scheduleDescription() { return "Iga 30 minuti järel; automaatne katkestuste järeltäitmine"; }
    public boolean configured() { return properties.isDirectConfigured(); }
    public boolean enabled() { return properties.isAutoRefreshEnabled(); }
    public List<UUID> scheduledOrganizations() {
        return dsl.select(field("id", UUID.class)).from(table("organization")).fetch(field("id", UUID.class));
    }

    public IntegrationJobResult run(UUID organizationId, Map<String, String> parameters) {
        DayPlan plan = dayPlan(organizationId, parameters);
        var result = service.synchronize(organizationId, plan.days());
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("checkedDays", result.checkedDays());
        metrics.put("parsed", result.parsed());
        metrics.put("matched", result.matched());
        metrics.put("imported", result.imported());
        metrics.put("skipped", result.skipped());
        metrics.put("failed", result.failed());
        if (plan.scheduled()) {
            LocalDate completed = result.failed() == 0 && plan.lastPastDay() != null
                ? plan.lastPastDay() : plan.previousCursor();
            if (completed != null) metrics.put("lastCompletedDate", completed.toString());
            metrics.put("catchupRemaining", plan.catchupRemaining());
        }
        return result.failed() > 0
            ? IntegrationJobResult.partial(Map.copyOf(metrics), "Osa pärimisteateid jäi importimata; sama perioodi proovitakse uuesti.")
            : IntegrationJobResult.success(Map.copyOf(metrics), "Pärimisteated kontrolliti ja sobitati.");
    }

    private DayPlan dayPlan(UUID organizationId, Map<String, String> parameters) {
        LocalDate today = LocalDate.now(ZoneId.of(properties.getZoneId()));
        if (parameters != null && parameters.containsKey("from")) {
            return new DayPlan(explicitDays(parameters, today), null, null, false, false);
        }

        LocalDate cursor = scheduledCursor(organizationId);
        if (cursor == null) cursor = today.minusDays(2); // preserves the previous today+yesterday first-run window
        LocalDate firstMissing = cursor.plusDays(1);
        LocalDate yesterday = today.minusDays(1);
        int maxDays = Math.max(2, properties.getMaxBackfillDays());
        int pastCapacity = Math.max(1, maxDays - 1); // reserve room for the rolling today/yesterday overlap
        LinkedHashSet<LocalDate> days = new LinkedHashSet<>();
        LocalDate lastPastDay = null;
        if (!firstMissing.isAfter(yesterday)) {
            long missingPastDays = ChronoUnit.DAYS.between(firstMissing, yesterday) + 1;
            long take = Math.min(missingPastDays, pastCapacity);
            for (long offset = 0; offset < take; offset++) days.add(firstMissing.plusDays(offset));
            lastPastDay = firstMissing.plusDays(take - 1);
        }
        // Keep a rolling overlap even after the cursor has advanced. This protects
        // against notices that are published late for the previous calendar day.
        days.add(yesterday);
        days.add(today);
        boolean remaining = lastPastDay != null && lastPastDay.isBefore(yesterday);
        return new DayPlan(List.copyOf(days), cursor, lastPastDay, true, remaining);
    }

    private LocalDate scheduledCursor(UUID organizationId) {
        Object raw = dsl.fetchValue("""
            SELECT metrics->>'lastCompletedDate'
              FROM integration_sync_run
             WHERE organization_id=?
               AND job_key=?
               AND trigger_type='SCHEDULED'
               AND state IN ('SUCCEEDED','PARTIAL')
               AND metrics->>'lastCompletedDate' IS NOT NULL
             ORDER BY finished_at DESC NULLS LAST, queued_at DESC
             LIMIT 1
            """, organizationId, IntegrationJobKey.INHERITANCE_NOTICES.name());
        String value = raw == null ? null : String.valueOf(raw);
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); }
        catch (java.time.format.DateTimeParseException ignored) { return null; }
    }

    private List<LocalDate> explicitDays(Map<String, String> parameters, LocalDate today) {
        try {
            LocalDate from = LocalDate.parse(parameters.get("from"));
            LocalDate to = parameters.containsKey("to") ? LocalDate.parse(parameters.get("to")) : from;
            long count = ChronoUnit.DAYS.between(from, to) + 1;
            if (count < 1 || count > Math.max(1, properties.getMaxBackfillDays()) || to.isAfter(today)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pärimisteadete kuupäevavahemik ei ole lubatud.");
            }
            return LongStream.range(0, count).mapToObj(from::plusDays).toList();
        } catch (java.time.format.DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kuupäev peab olema kujul YYYY-MM-DD.");
        }
    }

    private record DayPlan(List<LocalDate> days, LocalDate previousCursor, LocalDate lastPastDay,
                           boolean scheduled, boolean catchupRemaining) { }
}
