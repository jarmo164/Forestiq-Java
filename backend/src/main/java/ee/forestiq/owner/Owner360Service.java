package ee.forestiq.owner;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.decision.DecisionDtos.PortfolioBrief;
import ee.forestiq.decision.DecisionDtos.PortfolioSource;
import ee.forestiq.decision.PortfolioDecisionService;
import ee.forestiq.owner.Owner360Dtos.Coordinates;
import ee.forestiq.owner.Owner360Dtos.Owner360Overview;
import ee.forestiq.owner.Owner360Dtos.OwnerIdentity;
import ee.forestiq.owner.Owner360Dtos.ParcelRelation;
import ee.forestiq.owner.Owner360Dtos.ParcelRelationsPage;
import ee.forestiq.owner.Owner360Dtos.PortfolioKpis;
import ee.forestiq.owner.Owner360Dtos.SourceInfo;
import ee.forestiq.owner.Owner360Dtos.TimelineEvent;
import ee.forestiq.owner.Owner360Dtos.TimelinePage;
import ee.forestiq.owner.Owner360Dtos.WorkflowSummary;
import ee.forestiq.registry.RegistryFreshnessService;
import ee.forestiq.registry.RegistryFreshnessService.SourceFreshness;
import ee.forestiq.security.OrganizationContext;

@Service
public class Owner360Service {

    private static final int MAX_PAGE_SIZE = 200;
    private static final long FALLBACK_FRESH_HOURS = 7L * 24L;

    private final DSLContext dsl;
    private final OwnerRepository ownerRepository;
    private final OwnerAuthorizationService authorization;
    private final OrganizationContext organizationContext;
    private final PortfolioDecisionService portfolioDecisionService;
    private final RegistryFreshnessService registryFreshnessService;

    public Owner360Service(DSLContext dsl, OwnerRepository ownerRepository,
                           OwnerAuthorizationService authorization, OrganizationContext organizationContext,
                           PortfolioDecisionService portfolioDecisionService,
                           RegistryFreshnessService registryFreshnessService) {
        this.dsl = dsl;
        this.ownerRepository = ownerRepository;
        this.authorization = authorization;
        this.organizationContext = organizationContext;
        this.portfolioDecisionService = portfolioDecisionService;
        this.registryFreshnessService = registryFreshnessService;
    }

    @Transactional(readOnly = true)
    public Owner360Overview overview(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = requireOwner(orgId, ownerId);
        boolean sensitive = authorization.canReadSensitiveOwnerFields();
        Map<String, Owner360Dtos.SectionState> sections = new LinkedHashMap<>();
        sections.put("summary", Owner360Dtos.SectionState.ready());

        PortfolioBrief portfolio = null;
        try {
            portfolio = portfolioDecisionService.portfolio(ownerId);
            sections.put("portfolio", Owner360Dtos.SectionState.ready());
        } catch (RuntimeException unavailable) {
            sections.put("portfolio", Owner360Dtos.SectionState.error("PORTFOLIO_UNAVAILABLE"));
        }

        Integer historicalRelations = null;
        try {
            historicalRelations = historicalRelationCount(orgId, ownerId);
        } catch (RuntimeException unavailable) {
            sections.put("relations", Owner360Dtos.SectionState.error("RELATIONS_UNAVAILABLE"));
        }

        PortfolioKpis kpis = portfolio == null
            ? new PortfolioKpis(null, historicalRelations, null, null, null, null, null)
            : new PortfolioKpis(
                portfolio.parcelCount(),
                historicalRelations,
                portfolio.totalAreaHectares(),
                portfolio.forestAreaHectares(),
                portfolio.knownVolumeCubicMeters(),
                portfolio.volumeKnownParcelCount(),
                portfolio.activeNotificationCount());

        OwnerIdentity identity = new OwnerIdentity(
            owner.getId(),
            owner.getDisplayName(),
            sensitive,
            sensitive ? owner.getPersonalOrRegistryCode() : null,
            owner.getOwnerType(),
            owner.getStatus(),
            sensitive ? owner.getEmail() : null,
            sensitive ? owner.getPhone() : null,
            sensitive ? owner.getAddress() : null,
            sensitive ? owner.getInfo() : null,
            owner.getAssignedToSubject(),
            owner.getStatusSetAt(),
            owner.getUpdatedAt());

        var signals = List.<ee.forestiq.decision.DecisionDtos.DecisionSignal>of();
        try {
            signals = portfolioDecisionService.signals(ownerId);
            if (!sensitive) {
                signals = signals.stream()
                    .filter(signal -> signal.parcelId() != null && signal.dealId() == null)
                    .toList();
            }
            sections.put("signals", Owner360Dtos.SectionState.ready());
        } catch (RuntimeException unavailable) {
            sections.put("signals", Owner360Dtos.SectionState.error("SIGNALS_UNAVAILABLE"));
        }

        List<SourceInfo> sources = List.of();
        try {
            sources = sourceInfos(orgId, portfolio == null ? List.of() : portfolio.sources());
            sections.put("sources", Owner360Dtos.SectionState.ready());
        } catch (RuntimeException unavailable) {
            sections.put("sources", Owner360Dtos.SectionState.error("SOURCES_UNAVAILABLE"));
        }

        return new Owner360Overview(identity, kpis, signals, sources, Map.copyOf(sections), Instant.now());
    }

    @Transactional(readOnly = true)
    public ParcelRelationsPage relations(UUID ownerId, boolean includeHistorical, int offset, int limit) {
        UUID orgId = organizationContext.requiredOrganizationId();
        requireOwner(orgId, ownerId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));

        String activeFilter = includeHistorical ? "" : " AND opr.active = TRUE ";
        List<Record> rows = dsl.fetch("""
            SELECT p.id AS parcel_id, p.cadastral_code, p.name, p.county, p.municipality,
                   p.area_hectares, p.forest_area, opr.active, opr.source,
                   opr.source_seen_at, opr.deactivated_at,
                   (SELECT count(*) FROM owner_parcel_relation co
                     WHERE co.organization_id = opr.organization_id
                       AND co.parcel_id = opr.parcel_id AND co.active = TRUE) AS active_owner_count,
                   ST_X(ST_Centroid(p.boundary)) AS longitude,
                   ST_Y(ST_Centroid(p.boundary)) AS latitude
              FROM owner_parcel_relation opr
              JOIN land_parcel p
                ON p.organization_id = opr.organization_id AND p.id = opr.parcel_id
             WHERE opr.organization_id = ? AND opr.owner_id = ?
            """ + activeFilter + """
             ORDER BY opr.active DESC, p.cadastral_code ASC, p.id ASC
             LIMIT ? OFFSET ?
            """, orgId, ownerId, safeLimit + 1, safeOffset);

        boolean hasMore = rows.size() > safeLimit;
        if (hasMore) rows = rows.subList(0, safeLimit);
        List<ParcelRelation> content = rows.stream().map(this::relation).toList();
        return new ParcelRelationsPage(content, safeOffset, safeLimit, hasMore);
    }

    @Transactional(readOnly = true)
    public TimelinePage history(UUID ownerId, int offset, int limit) {
        UUID orgId = organizationContext.requiredOrganizationId();
        requireOwner(orgId, ownerId);
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        int fetchLimit = Math.min(600, safeOffset + safeLimit + 1);
        boolean sensitive = authorization.canReadSensitiveOwnerFields();
        List<TimelineEvent> events = new ArrayList<>();

        List<Record> activities = dsl.fetch("""
            SELECT id, activity_type, content, created_by_subject, created_at
              FROM owner_activity
             WHERE organization_id = ? AND owner_id = ?
             ORDER BY created_at DESC, id DESC
             LIMIT ?
            """, orgId, ownerId, fetchLimit);
        for (Record row : activities) {
            String type = row.get("activity_type", String.class);
            if (!sensitive && "NOTE".equals(type)) continue;
            events.add(new TimelineEvent(
                "activity:" + row.get("id", UUID.class), "FORESTIQ", type,
                activityTitle(type), sensitive ? row.get("content", String.class) : null,
                instant(row, "created_at"), "ForestIQ",
                sensitive ? row.get("created_by_subject", String.class) : null, null, null));
        }

        List<Record> ownershipAudit = dsl.fetch("""
            SELECT a.id, a.action, a.relation_source, a.reason, a.actor_subject, a.created_at,
                   a.parcel_id, p.cadastral_code
              FROM owner_parcel_relation_audit a
              JOIN land_parcel p ON p.organization_id = a.organization_id AND p.id = a.parcel_id
             WHERE a.organization_id = ? AND a.owner_id = ?
             ORDER BY a.created_at DESC, a.id DESC
             LIMIT ?
            """, orgId, ownerId, fetchLimit);
        for (Record row : ownershipAudit) {
            String action = row.get("action", String.class);
            events.add(new TimelineEvent(
                "ownership:" + row.get("id", UUID.class), "OWNERSHIP", action,
                ownershipTitle(action), sensitive ? row.get("reason", String.class) : "Omandiseose muudatus.",
                instant(row, "created_at"), row.get("relation_source", String.class),
                sensitive ? row.get("actor_subject", String.class) : null,
                row.get("parcel_id", UUID.class), row.get("cadastral_code", String.class)));
        }

        if (sensitive) {
            List<Record> dealEvents = dsl.fetch("""
                SELECT e.id, e.event_type, e.description, e.created_by_subject, e.created_at, d.id AS deal_id
                  FROM deal_case_event e
                  JOIN deal_case d ON d.organization_id = e.organization_id AND d.id = e.deal_case_id
                 WHERE e.organization_id = ? AND d.owner_id = ?
                 ORDER BY e.created_at DESC, e.id DESC
                 LIMIT ?
                """, orgId, ownerId, fetchLimit);
            for (Record row : dealEvents) {
                String type = row.get("event_type", String.class);
                events.add(new TimelineEvent(
                    "deal:" + row.get("id", UUID.class), "WORKFLOW", type,
                    "Tehing: " + humanize(type), row.get("description", String.class),
                    instant(row, "created_at"), "ForestIQ tehinguvoog",
                    row.get("created_by_subject", String.class), null, null));
            }
        }

        events.sort(Comparator.comparing(TimelineEvent::createdAt,
            Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(TimelineEvent::id));
        boolean hasMore = events.size() > safeOffset + safeLimit;
        if (safeOffset >= events.size()) return new TimelinePage(List.of(), safeOffset, safeLimit, false);
        int toIndex = Math.min(events.size(), safeOffset + safeLimit);
        return new TimelinePage(List.copyOf(events.subList(safeOffset, toIndex)), safeOffset, safeLimit, hasMore);
    }

    @Transactional(readOnly = true)
    public WorkflowSummary workflow(UUID ownerId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Owner owner = requireOwner(orgId, ownerId);
        if (!authorization.canReadSensitiveOwnerFields()) {
            return new WorkflowSummary(owner.getStatus(), owner.getAssignedToSubject(), false,
                null, null, null, null, null, null, null);
        }

        Record deal = dsl.fetchOne("""
            SELECT id, stage, next_action, next_action_due_at,
                   count(*) OVER () AS active_deal_count
              FROM deal_case
             WHERE organization_id = ? AND owner_id = ?
               AND stage NOT IN ('WON','LOST','CANCELLED')
             ORDER BY updated_at DESC, created_at DESC, id DESC
             LIMIT 1
            """, orgId, ownerId);
        Record reminder = dsl.fetchOne("""
            SELECT count(*) AS open_reminder_count, min(due_time) AS next_reminder_at
              FROM reminder
             WHERE organization_id = ? AND owner_id = ? AND due_time >= now()
            """, orgId, ownerId);

        return new WorkflowSummary(
            owner.getStatus(), owner.getAssignedToSubject(), true,
            deal == null ? 0 : number(deal, "active_deal_count"),
            deal == null ? null : deal.get("id", UUID.class),
            deal == null ? null : deal.get("stage", String.class),
            deal == null ? null : deal.get("next_action", String.class),
            deal == null ? null : instant(deal, "next_action_due_at"),
            reminder == null ? 0 : number(reminder, "open_reminder_count"),
            reminder == null ? null : instant(reminder, "next_reminder_at"));
    }

    private Owner requireOwner(UUID orgId, UUID ownerId) {
        Owner owner = ownerRepository.findByIdAndOrganizationId(ownerId, orgId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Owner not found"));
        authorization.assertCanRead(owner);
        return owner;
    }

    private int historicalRelationCount(UUID orgId, UUID ownerId) {
        Record row = dsl.fetchOne("""
            SELECT count(*) AS relation_count
              FROM owner_parcel_relation
             WHERE organization_id = ? AND owner_id = ? AND active = FALSE
            """, orgId, ownerId);
        return row == null ? 0 : number(row, "relation_count");
    }

    private ParcelRelation relation(Record row) {
        Double longitude = row.get("longitude", Double.class);
        Double latitude = row.get("latitude", Double.class);
        Coordinates centroid = longitude == null || latitude == null ? null : new Coordinates(longitude, latitude);
        return new ParcelRelation(
            row.get("parcel_id", UUID.class), row.get("cadastral_code", String.class), row.get("name", String.class),
            row.get("county", String.class), row.get("municipality", String.class),
            row.get("area_hectares", BigDecimal.class), row.get("forest_area", BigDecimal.class),
            Boolean.TRUE.equals(row.get("active", Boolean.class)), row.get("source", String.class),
            instant(row, "source_seen_at"), instant(row, "deactivated_at"),
            number(row, "active_owner_count"), null, centroid);
    }

    private List<SourceInfo> sourceInfos(UUID orgId, List<PortfolioSource> portfolioSources) {
        Map<String, SourceInfo> merged = new LinkedHashMap<>();
        for (PortfolioSource source : portfolioSources) {
            SourceInfo info = sourceInfo(source);
            merged.put(info.source(), info);
        }
        for (SourceFreshness source : registryFreshnessService.dashboardForOrganization(orgId).sources()) {
            SourceInfo info = registrySourceInfo(source);
            merged.put(info.source(), info);
        }
        return List.copyOf(merged.values());
    }

    private SourceInfo sourceInfo(PortfolioSource source) {
        String freshness;
        if (!source.available() || source.observedAt() == null) {
            freshness = "UNKNOWN";
        } else if (source.observedAt().isBefore(Instant.now().minus(FALLBACK_FRESH_HOURS, ChronoUnit.HOURS))) {
            freshness = "STALE";
        } else {
            freshness = "FRESH";
        }
        return new SourceInfo(source.source(), source.observedAt(), freshness, source.available(), source.note());
    }

    static String registryFreshnessState(SourceFreshness source) {
        Instant success = source.lastSuccessfulSyncAt();
        Instant failure = source.lastFailedSyncAt();
        if (source.totalParcels() == 0 || success == null) return "UNKNOWN";
        if (failure != null && failure.isAfter(success)) return "FAILED_REFRESH";
        if (source.staleParcels() > 0) return "STALE";
        return "FRESH";
    }

    private SourceInfo registrySourceInfo(SourceFreshness source) {
        String state = registryFreshnessState(source);
        StringBuilder note = new StringBuilder();
        note.append("Tõend: ").append(source.evidence()).append(". ");
        if (source.sourceDate() != null) note.append("Allika kuupäev: ").append(source.sourceDate()).append(". ");
        if (source.lastFailedSyncAt() != null) {
            note.append("Viimane ebaõnnestunud uuendus: ").append(source.lastFailedSyncAt()).append(". ");
        }
        if (source.failuresLast24Hours() > 0) {
            note.append("Ebaõnnestumisi 24 h jooksul: ").append(source.failuresLast24Hours()).append(".");
        }
        return new SourceInfo(source.label(), source.lastSuccessfulSyncAt(), state,
            source.lastSuccessfulSyncAt() != null, note.toString().trim());
    }

    private static int number(Record row, String field) {
        Number value = row.get(field, Number.class);
        return value == null ? 0 : value.intValue();
    }

    private static Instant instant(Record row, String field) {
        Object value = row.get(field);
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        throw new IllegalArgumentException("Unsupported timestamp value for " + field + ": " + value.getClass().getName());
    }

    private static String activityTitle(String type) {
        return switch (type == null ? "" : type) {
            case "NOTE" -> "Märkus";
            case "STATUS_CHANGED" -> "Staatus muutus";
            case "ASSIGNED" -> "Vastutaja muutus";
            case "CADASTRE_ADDED" -> "Katastriseos lisati";
            case "CADASTRE_REMOVED" -> "Katastriseos lõpetati";
            case "EVALUATION" -> "Hindamine";
            case "REMINDER" -> "Meeldetuletus";
            default -> humanize(type);
        };
    }

    private static String ownershipTitle(String action) {
        return switch (action == null ? "" : action) {
            case "CREATED", "ACTIVATED" -> "Katastriseos lisati";
            case "DEACTIVATED", "ENDED" -> "Katastriseos lõpetati";
            default -> "Omandiseos: " + humanize(action);
        };
    }

    private static String humanize(String value) {
        if (value == null || value.isBlank()) return "Sündmus";
        String normalized = value.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}