package ee.forestiq.decision;

import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ee.forestiq.decision.DecisionDtos.DecisionSignal;
import ee.forestiq.decision.DecisionDtos.EvidencePreview;
import ee.forestiq.decision.DecisionDtos.EvidenceSnapshot;
import ee.forestiq.decision.DecisionDtos.GeographicGroup;
import ee.forestiq.decision.DecisionDtos.PortfolioBrief;
import ee.forestiq.decision.DecisionDtos.PortfolioParcel;
import ee.forestiq.decision.DecisionDtos.PortfolioSource;
import ee.forestiq.owner.OwnerAuthorizationService;
import ee.forestiq.security.OrganizationContext;

@Service
public class PortfolioDecisionService {

    private final DSLContext dsl;
    private final OrganizationContext orgCtx;
    private final OwnerAuthorizationService ownerAuthorization;
    private final ObjectMapper objectMapper;

    public PortfolioDecisionService(DSLContext dsl, OrganizationContext orgCtx,
                                    OwnerAuthorizationService ownerAuthorization, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.orgCtx = orgCtx;
        this.ownerAuthorization = ownerAuthorization;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PortfolioBrief portfolio(UUID ownerId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        OwnerAccess owner = requireOwner(orgId, ownerId);
        return buildPortfolio(orgId, owner, null);
    }

    @Transactional(readOnly = true)
    public List<DecisionSignal> signals(UUID ownerId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        OwnerAccess owner = requireOwner(orgId, ownerId);
        return buildSignals(orgId, owner, null, null);
    }

    @Transactional(readOnly = true)
    public EvidencePreview evidencePreview(UUID dealId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        DealAccess deal = requireDeal(orgId, dealId);
        Set<UUID> selected = selectedParcelIds(orgId, dealId);
        PortfolioBrief portfolio = buildPortfolio(orgId, deal.owner(), selected);
        List<DecisionSignal> signals = buildSignals(orgId, deal.owner(), selected, dealId);
        return new EvidencePreview(dealId, deal.owner().id(), Instant.now(), List.copyOf(selected), portfolio, signals);
    }

    @Transactional
    public EvidenceSnapshot confirmEvidence(UUID dealId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        DealAccess deal = requireDeal(orgId, dealId);
        Set<UUID> selected = selectedParcelIds(orgId, dealId);
        PortfolioBrief portfolio = buildPortfolio(orgId, deal.owner(), selected);
        List<DecisionSignal> signals = buildSignals(orgId, deal.owner(), selected, dealId);
        EvidencePreview preview = new EvidencePreview(dealId, deal.owner().id(), Instant.now(), List.copyOf(selected), portfolio, signals);
        Instant observedAt = portfolio.sources().stream()
            .map(PortfolioSource::observedAt)
            .filter(java.util.Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        String json = toJson(preview);
        UUID id = UUID.randomUUID();
        String subject = orgCtx.subject();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        dsl.execute("""
            INSERT INTO evaluation_evidence_snapshot
                (id, organization_id, deal_case_id, owner_id, state, schema_version, snapshot,
                 source_observed_at, created_by_subject, confirmed_by_subject, confirmed_at)
            VALUES (?, ?, ?, ?, 'CONFIRMED', 1, ?::jsonb, ?, ?, ?, ?)
            """, id, orgId, dealId, deal.owner().id(), json, dbTime(observedAt), subject, subject, now);
        return getEvidence(id);
    }

    @Transactional(readOnly = true)
    public List<EvidenceSnapshot> listEvidence(UUID dealId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        requireDeal(orgId, dealId);
        return dsl.fetch("""
            SELECT id, deal_case_id, owner_id, state, schema_version, snapshot, source_observed_at,
                   created_by_subject, created_at, confirmed_by_subject, confirmed_at
            FROM evaluation_evidence_snapshot
            WHERE organization_id = ? AND deal_case_id = ?
            ORDER BY created_at DESC, id DESC
            LIMIT 100
            """, orgId, dealId).map(this::toSnapshot);
    }

    @Transactional(readOnly = true)
    public EvidenceSnapshot getEvidence(UUID snapshotId) {
        UUID orgId = orgCtx.requiredOrganizationId();
        Record row = dsl.fetchOne("""
            SELECT id, deal_case_id, owner_id, state, schema_version, snapshot, source_observed_at,
                   created_by_subject, created_at, confirmed_by_subject, confirmed_at
            FROM evaluation_evidence_snapshot
            WHERE organization_id = ? AND id = ?
            """, orgId, snapshotId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Hindamise tõenduspilti ei leitud.");
        requireDeal(orgId, row.get("deal_case_id", UUID.class));
        return toSnapshot(row);
    }

    private PortfolioBrief buildPortfolio(UUID orgId, OwnerAccess owner, Set<UUID> selectedParcelIds) {
        List<PortfolioParcelRow> rows = dsl.fetch("""
            SELECT p.id, p.cadastral_code, p.name, p.area_hectares, p.forest_area, p.county, p.updated_at,
                   (SELECT SUM(f.volume) FROM forest_registry_feature f
                     WHERE f.organization_id = p.organization_id AND f.cadastral_code = p.cadastral_code AND f.volume IS NOT NULL) AS known_volume,
                   (SELECT COUNT(*) FROM cadastre_notification n
                     WHERE n.organization_id = p.organization_id AND n.cadastral_code = p.cadastral_code AND n.archived = FALSE) AS active_notifications,
                   (SELECT MAX(f.created_at) FROM forest_registry_feature f
                     WHERE f.organization_id = p.organization_id AND f.cadastral_code = p.cadastral_code) AS feature_observed_at,
                   (SELECT MAX(n.created_at) FROM cadastre_notification n
                     WHERE n.organization_id = p.organization_id AND n.cadastral_code = p.cadastral_code) AS notification_observed_at
            FROM owner_parcel_relation opr
            JOIN land_parcel p ON p.organization_id = opr.organization_id AND p.id = opr.parcel_id
            WHERE opr.organization_id = ? AND opr.owner_id = ? AND opr.active = TRUE
            ORDER BY p.cadastral_code, p.id
            """, orgId, owner.id()).map(row -> new PortfolioParcelRow(
                row.get("id", UUID.class), row.get("cadastral_code", String.class), row.get("name", String.class),
                row.get("area_hectares", BigDecimal.class), row.get("forest_area", BigDecimal.class),
                row.get("known_volume", BigDecimal.class), row.get("active_notifications", Long.class),
                row.get("county", String.class), toInstant(row, "updated_at"),
                toInstant(row, "feature_observed_at"), toInstant(row, "notification_observed_at")
            ));
        if (selectedParcelIds != null) rows = rows.stream().filter(row -> selectedParcelIds.contains(row.id())).toList();

        BigDecimal totalArea = BigDecimal.ZERO;
        BigDecimal forestArea = BigDecimal.ZERO;
        BigDecimal volume = BigDecimal.ZERO;
        int volumeKnown = 0;
        long activeNotifications = 0;
        Instant parcelObserved = null;
        Instant featureObserved = null;
        Instant notificationObserved = null;
        Map<String, GeographyAccumulator> geography = new LinkedHashMap<>();
        List<PortfolioParcel> parcels = new ArrayList<>();

        for (PortfolioParcelRow row : rows) {
            totalArea = totalArea.add(zero(row.area()));
            forestArea = forestArea.add(zero(row.forestArea()));
            if (row.volume() != null) {
                volume = volume.add(row.volume());
                volumeKnown++;
            }
            activeNotifications += row.activeNotifications() == null ? 0 : row.activeNotifications();
            parcelObserved = max(parcelObserved, row.updatedAt());
            featureObserved = max(featureObserved, row.featureObservedAt());
            notificationObserved = max(notificationObserved, row.notificationObservedAt());
            String county = row.county() == null || row.county().isBlank() ? "Määramata" : row.county();
            geography.computeIfAbsent(county, ignored -> new GeographyAccumulator()).add(row.area());
            parcels.add(new PortfolioParcel(row.id(), row.cadastralCode(), row.name(), row.area(), row.forestArea(),
                row.volume(), row.activeNotifications() == null ? 0 : row.activeNotifications(), row.county(), row.updatedAt()));
        }

        List<GeographicGroup> groups = geography.entrySet().stream()
            .map(entry -> new GeographicGroup(entry.getKey(), entry.getValue().count, entry.getValue().area))
            .sorted(Comparator.comparing(GeographicGroup::county, String.CASE_INSENSITIVE_ORDER))
            .toList();
        List<PortfolioSource> sources = List.of(
            new PortfolioSource("Katastri/CRM projektsioon", parcelObserved, parcelObserved != null,
                parcelObserved == null ? "Kinnistuandmeid ei ole." : "Aktiivsed omandisuhted ja kinnistu põhinäitajad."),
            new PortfolioSource("Metsaregistri metsaandmed", featureObserved, featureObserved != null,
                featureObserved == null ? "Teadaolev maht puudub; seda ei käsitleta nullina." : "Maht on teada ainult registris olemasolevate kirjete ulatuses."),
            new PortfolioSource("Metsaregistri teatised", notificationObserved, notificationObserved != null,
                notificationObserved == null ? "Teatisi ei ole laaditud." : "Aktiivsete teatiste arv ei sisalda arhiveeritud kirjeid.")
        );
        return new PortfolioBrief(owner.id(), owner.name(), parcels.size(), totalArea, forestArea,
            volumeKnown == 0 ? null : volume, volumeKnown, activeNotifications, parcels, groups, sources, Instant.now());
    }

    private List<DecisionSignal> buildSignals(UUID orgId, OwnerAccess owner, Set<UUID> selectedParcelIds, UUID onlyDealId) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<DecisionSignal> result = new ArrayList<>();

        if ((owner.phone() == null || owner.phone().isBlank()) && (owner.email() == null || owner.email().isBlank())) {
            result.add(new DecisionSignal("MISSING_CONTACT", "WARNING", "Kontaktandmed puuduvad",
                "Omanikul puuduvad nii telefon kui ka e-posti aadress.", "ForestIQ CRM", now,
                "Kontrolli kontaktandmed usaldusväärsest allikast enne kliendikontakti.", null, null, null, false));
        }

        var parcelFreshness = dsl.fetch("""
            SELECT p.id, p.cadastral_code,
                   GREATEST(
                       (SELECT MAX(f.created_at) FROM forest_registry_feature f
                         WHERE f.organization_id = p.organization_id AND f.cadastral_code = p.cadastral_code),
                       (SELECT MAX(n.created_at) FROM cadastre_notification n
                         WHERE n.organization_id = p.organization_id AND n.cadastral_code = p.cadastral_code)
                   ) AS observed_at
            FROM owner_parcel_relation opr
            JOIN land_parcel p ON p.organization_id = opr.organization_id AND p.id = opr.parcel_id
            WHERE opr.organization_id = ? AND opr.owner_id = ? AND opr.active = TRUE
            """, orgId, owner.id());
        for (Record row : parcelFreshness) {
            UUID parcelId = row.get("id", UUID.class);
            if (!selected(selectedParcelIds, parcelId)) continue;
            Instant observedAt = toInstant(row, "observed_at");
            if (DecisionSignalRules.isStaleRegistry(observedAt, now)) {
                result.add(new DecisionSignal("STALE_REGISTRY_DATA", "WARNING", "Registriandmed vajavad värskuse kontrolli",
                    observedAt == null ? "Kinnistu kohta puudub metsaregistri värskuse ajatempel." : "Viimane metsaregistri kirje on vanem kui 7 päeva.",
                    "Metsaregister", observedAt, "Värskenda või kontrolli registriandmed enne hindamisotsust.",
                    parcelId, row.get("cadastral_code", String.class), null, false));
            }
        }

        var freshNotifications = dsl.fetch("""
            SELECT p.id, p.cadastral_code, MAX(n.created_at) AS observed_at
            FROM owner_parcel_relation opr
            JOIN land_parcel p ON p.organization_id = opr.organization_id AND p.id = opr.parcel_id
            JOIN cadastre_notification n ON n.organization_id = p.organization_id AND n.cadastral_code = p.cadastral_code
            WHERE opr.organization_id = ? AND opr.owner_id = ? AND opr.active = TRUE
              AND n.archived = FALSE AND n.created_at >= now() - interval '30 days'
            GROUP BY p.id, p.cadastral_code
            """, orgId, owner.id());
        for (Record row : freshNotifications) {
            UUID parcelId = row.get("id", UUID.class);
            if (!selected(selectedParcelIds, parcelId)) continue;
            Instant observedAt = toInstant(row, "observed_at");
            if (DecisionSignalRules.isFreshNotification(observedAt, now)) {
                result.add(new DecisionSignal("FRESH_FOREST_NOTIFICATION", "INFO", "Värske metsateatis",
                    "Kinnistul on viimase 30 päeva jooksul laaditud aktiivne metsateatis.", "Metsaregister", observedAt,
                    "Ava teatise detail ja kontrolli töö liik, otsus ning seos kavandatava tehinguga.",
                    parcelId, row.get("cadastral_code", String.class), null, false));
            }
        }

        var labels = dsl.fetch("""
            SELECT p.id, p.cadastral_code, l.label, l.created_at
            FROM owner_parcel_relation opr
            JOIN land_parcel p ON p.organization_id = opr.organization_id AND p.id = opr.parcel_id
            JOIN cadastre_label l ON l.organization_id = p.organization_id AND l.cadastral_code = p.cadastral_code
            WHERE opr.organization_id = ? AND opr.owner_id = ? AND opr.active = TRUE
            """, orgId, owner.id());
        for (Record row : labels) {
            UUID parcelId = row.get("id", UUID.class);
            if (!selected(selectedParcelIds, parcelId)) continue;
            String label = row.get("label", String.class);
            if (DecisionSignalRules.isRestrictionLabel(label)) {
                result.add(new DecisionSignal("RESTRICTION_LABEL", "WARNING", "Kitsendusmärgend vajab kontrolli",
                    "Kinnistul on märgend „" + label + "“.", "ForestIQ märgend", toInstant(row, "created_at"),
                    "Kontrolli kitsenduse tegelik sisu ja õiguslik tähendus ametlikust allikast; märgend ise ei ole õiguslik otsus.",
                    parcelId, row.get("cadastral_code", String.class), null, false));
            }
        }

        var offers = dsl.fetch("""
            SELECT d.id AS deal_id, e.offer_valid_until, e.submitted_at
            FROM deal_case d
            JOIN LATERAL (
                SELECT offer_valid_until, submitted_at
                FROM deal_evaluation
                WHERE organization_id = d.organization_id AND deal_case_id = d.id
                ORDER BY revision DESC
                LIMIT 1
            ) e ON TRUE
            WHERE d.organization_id = ? AND d.owner_id = ?
              AND d.stage NOT IN ('WON','LOST','CANCELLED')
              AND (?::uuid IS NULL OR d.id = ?::uuid)
            """, orgId, owner.id(), onlyDealId, onlyDealId);
        for (Record row : offers) {
            LocalDate validUntil = row.get("offer_valid_until", LocalDate.class);
            if (DecisionSignalRules.isApproachingOfferDeadline(validUntil, today)) {
                result.add(new DecisionSignal("OFFER_DEADLINE", "WARNING", "Pakkumise tähtaeg läheneb",
                    "Pakkumine kehtib kuni " + validUntil + ".", "ForestIQ tehing", toInstant(row, "submitted_at"),
                    "Kontrolli järgmine kontakt ja pakkumise kehtivus enne tähtaja saabumist.",
                    null, null, row.get("deal_id", UUID.class), false));
            }
        }

        return result.stream()
            .sorted(Comparator.comparing((DecisionSignal signal) -> signal.observedAt() == null ? Instant.EPOCH : signal.observedAt()).reversed()
                .thenComparing(DecisionSignal::code))
            .toList();
    }

    private OwnerAccess requireOwner(UUID orgId, UUID ownerId) {
        Record row = dsl.fetchOne("""
            SELECT id, display_name, phone, email, assigned_to_subject
            FROM owner
            WHERE organization_id = ? AND id = ?
            """, orgId, ownerId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Omanikku ei leitud.");
        String assigned = row.get("assigned_to_subject", String.class);
        ownerAuthorization.assertCanReadAssignedSubject(assigned);
        return new OwnerAccess(ownerId, row.get("display_name", String.class), row.get("phone", String.class),
            row.get("email", String.class), assigned);
    }

    private DealAccess requireDeal(UUID orgId, UUID dealId) {
        Record row = dsl.fetchOne("SELECT owner_id FROM deal_case WHERE organization_id = ? AND id = ?", orgId, dealId);
        if (row == null) throw new ResponseStatusException(NOT_FOUND, "Tehingujuhtumit ei leitud.");
        return new DealAccess(dealId, requireOwner(orgId, row.get("owner_id", UUID.class)));
    }

    private Set<UUID> selectedParcelIds(UUID orgId, UUID dealId) {
        return new LinkedHashSet<>(dsl.fetch("""
            SELECT parcel_id FROM deal_case_parcel
            WHERE organization_id = ? AND deal_case_id = ?
            ORDER BY parcel_id
            """, orgId, dealId).getValues("parcel_id", UUID.class));
    }

    private EvidenceSnapshot toSnapshot(Record row) {
        JSONB json = row.get("snapshot", JSONB.class);
        JsonNode node;
        try {
            node = objectMapper.readTree(json.data());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tõenduspildi JSON-i ei õnnestunud lugeda.", exception);
        }
        return new EvidenceSnapshot(row.get("id", UUID.class), row.get("deal_case_id", UUID.class),
            row.get("owner_id", UUID.class), row.get("state", String.class), row.get("schema_version", Integer.class),
            node, toInstant(row, "source_observed_at"), row.get("created_by_subject", String.class),
            toInstant(row, "created_at"), row.get("confirmed_by_subject", String.class), toInstant(row, "confirmed_at"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Tõenduspildi serialiseerimine ebaõnnestus.", exception);
        }
    }

    private static boolean selected(Set<UUID> selected, UUID id) {
        return selected == null || selected.contains(id);
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static Instant max(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static Instant toInstant(Record row, String field) {
        OffsetDateTime value = row.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record OwnerAccess(UUID id, String name, String phone, String email, String assignedSubject) {}
    private record DealAccess(UUID id, OwnerAccess owner) {}
    private record PortfolioParcelRow(UUID id, String cadastralCode, String name, BigDecimal area, BigDecimal forestArea,
                                      BigDecimal volume, Long activeNotifications, String county, Instant updatedAt,
                                      Instant featureObservedAt, Instant notificationObservedAt) {}

    private static final class GeographyAccumulator {
        private long count;
        private BigDecimal area = BigDecimal.ZERO;
        private void add(BigDecimal value) {
            count++;
            area = area.add(zero(value));
        }
    }
}
