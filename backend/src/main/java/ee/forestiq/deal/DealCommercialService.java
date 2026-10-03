package ee.forestiq.deal;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static ee.forestiq.jooq.Tables.DEAL_CASE_EVENT;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.owner.collaboration.OwnerCollaborationPort.DealLifecycleTransition;
import ee.forestiq.security.OrganizationContext;
import ee.forestiq.time.BusinessCalendar;

/** Commercial state machine layered on the immutable deal_case_event timeline. */
@Service
public class DealCommercialService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> TERMINAL_STAGES = Set.of("WON", "LOST", "CANCELLED");
    private static final String LOSS_REASON_PATTERN = "[A-Z0-9_]+";

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final OwnerCollaborationPort ownerCollaboration;
    private final DealQueryRepository queryRepository;
    private final BusinessCalendar businessCalendar;

    public DealCommercialService(DSLContext dsl, OrganizationContext organizationContext,
                                 OwnerCollaborationPort ownerCollaboration,
                                 DealQueryRepository queryRepository, BusinessCalendar businessCalendar) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.ownerCollaboration = ownerCollaboration;
        this.queryRepository = queryRepository;
        this.businessCalendar = businessCalendar;
    }

    @Transactional(readOnly = true)
    public CommercialState state(UUID dealId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Record deal = queryRepository.requireDeal(orgId, dealId);
        authorizeRead(orgId, deal);
        return state(orgId, deal);
    }

    @Transactional
    public CommercialState prepareOffer(UUID dealId, PrepareOfferRequest request) {
        validateOffer(request);
        UUID orgId = organizationContext.requiredOrganizationId();
        Record deal = lockAndAuthorizeSales(orgId, dealId);
        String expectedStage = requireStage(deal, Set.of("VALUED", "NEGOTIATION"),
            "Pakkumist saab ette valmistada pärast hindamisotsust või läbirääkimiste käigus.");

        int revision = nextMonetaryRevision(orgId, dealId);
        UUID offerId = UUID.randomUUID();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("entryId", offerId.toString());
        data.put("kind", "SELLER_OFFER");
        data.put("revision", String.valueOf(revision));
        data.put("amount", request.amount().toPlainString());
        data.put("validUntil", request.validUntil() == null ? "" : request.validUntil().toString());
        data.put("terms", blank(request.terms()));
        addEvent(orgId, dealId, "OFFER_PREPARED", "Pakkumine versiooniga " + revision + " valmistati ette.", data);

        transition(orgId, dealId, expectedStage, "OFFER_PREPARED", "Saada ettevalmistatud pakkumine kliendile",
            businessCalendar.addBusinessDays(Instant.now(), 1), null);
        return state(orgId, queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional
    public CommercialState sendOffer(UUID dealId) {
        UUID orgId = organizationContext.requiredOrganizationId();
        Record deal = lockAndAuthorizeSales(orgId, dealId);
        String expectedStage = requireStage(deal, Set.of("OFFER_PREPARED"), "Saata saab ainult ettevalmistatud pakkumise.");
        OfferEntry latest = latestSellerOffer(orgId, dealId);
        if (latest == null) throw conflict("Ettevalmistatud pakkumise ajalugu puudub.");

        addEvent(orgId, dealId, "OFFER_SENT", "Pakkumine versiooniga " + latest.revision() + " saadeti kliendile.",
            Map.of("entryId", latest.entryId().toString(), "revision", String.valueOf(latest.revision())));
        transition(orgId, dealId, expectedStage, "OFFER_SENT", "Kontrolli kliendi vastust ja lepi järgmine samm",
            businessCalendar.addBusinessDays(Instant.now(), 2), null);
        return state(orgId, queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional
    public CommercialState recordCounterOffer(UUID dealId, CounterOfferRequest request) {
        validateCounterOffer(request);
        UUID orgId = organizationContext.requiredOrganizationId();
        Record deal = lockAndAuthorizeSales(orgId, dealId);
        String expectedStage = requireStage(deal, Set.of("OFFER_SENT", "NEGOTIATION"),
            "Vastupakkumise saab salvestada pärast kliendile saadetud pakkumist.");

        int revision = nextMonetaryRevision(orgId, dealId);
        UUID counterId = UUID.randomUUID();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("entryId", counterId.toString());
        data.put("kind", "CUSTOMER_COUNTEROFFER");
        data.put("revision", String.valueOf(revision));
        data.put("amount", request.amount().toPlainString());
        data.put("validUntil", "");
        data.put("terms", blank(request.note()));
        addEvent(orgId, dealId, "CUSTOMER_COUNTEROFFER", "Kliendi vastupakkumine salvestati versioonina " + revision + ".", data);

        transition(orgId, dealId, expectedStage, "NEGOTIATION", "Otsusta vastupakkumine või valmista uus pakkumine",
            businessCalendar.addBusinessDays(Instant.now(), 1), null);
        ownerCollaboration.applyDealLifecycle(deal.get(DEAL_CASE.OWNER_ID),
            DealLifecycleTransition.ENSURE_NEGOTIATION_AFTER_COUNTEROFFER);
        return state(orgId, queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional
    public CommercialState win(UUID dealId, WinDealRequest request) {
        if (request == null || request.acceptedEntryId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Võidetud tehing peab viitama aktsepteeritud pakkumisele või vastupakkumisele.");
        }
        UUID orgId = organizationContext.requiredOrganizationId();
        Record deal = lockAndAuthorizeSales(orgId, dealId);
        String expectedStage = requireStage(deal, Set.of("OFFER_SENT", "NEGOTIATION"),
            "Tehingu saab võidetuks märkida pärast saadetud pakkumist või läbirääkimisi.");
        OfferEntry accepted = findEntry(orgId, dealId, request.acceptedEntryId());
        if (accepted == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Aktsepteeritud pakkumist ei leitud selle tehingu ajaloost.");
        }

        addEvent(orgId, dealId, "DEAL_WON", "Klient aktsepteeris rahalise pakkumise; tehing märgiti võidetuks.",
            Map.of("entryId", accepted.entryId().toString(), "amount", accepted.amount().toPlainString(), "note", blank(request.note())));
        transition(orgId, dealId, expectedStage, "WON", "Valmista leping ja vii tehing vormistamiseni", null, Instant.now());
        ownerCollaboration.appendDealSystemActivity(deal.get(DEAL_CASE.OWNER_ID),
            "Tehingujuhtum võideti summaga " + accepted.amount().toPlainString() + ".");
        return state(orgId, queryRepository.requireDeal(orgId, dealId));
    }

    @Transactional
    public CommercialState cancel(UUID dealId, CancelDealRequest request) {
        if (request == null || request.note() == null || request.note().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Katkestamise põhjus või märkus on kohustuslik.");
        }
        UUID orgId = organizationContext.requiredOrganizationId();
        Record deal = lockAndAuthorizeSales(orgId, dealId);
        String expectedStage = requireStage(deal, Set.of("VALUED", "OFFER_PREPARED", "OFFER_SENT", "NEGOTIATION"),
            "Tehingu saab katkestada ainult aktiivses kommertsetapis.");
        addEvent(orgId, dealId, "DEAL_CANCELLED", "Tehing katkestati.", Map.of("note", request.note().trim()));
        transition(orgId, dealId, expectedStage, "CANCELLED", "Tehing lõpetatud", null, Instant.now());
        ownerCollaboration.appendDealSystemActivity(deal.get(DEAL_CASE.OWNER_ID),
            "Tehingujuhtum katkestati: " + request.note().trim());
        return state(orgId, queryRepository.requireDeal(orgId, dealId));
    }

    private CommercialState state(UUID orgId, Record deal) {
        UUID dealId = deal.get(DEAL_CASE.ID);
        return new CommercialState(
            dealId,
            deal.get(DEAL_CASE.STAGE),
            deal.get(DEAL_CASE.NEXT_ACTION),
            toInstant(deal.get(DEAL_CASE.NEXT_ACTION_DUE_AT)),
            toInstant(deal.get(DEAL_CASE.CLOSED_AT)),
            offerHistory(orgId, dealId));
    }

    private List<OfferEntry> offerHistory(UUID orgId, UUID dealId) {
        Map<UUID, OfferBuilder> entries = new LinkedHashMap<>();
        for (EventSnapshot event : commercialEvents(orgId, dealId)) {
            Map<String, String> data = event.data();
            if ("OFFER_PREPARED".equals(event.type()) || "CUSTOMER_COUNTEROFFER".equals(event.type())) {
                UUID entryId = uuid(data.get("entryId"));
                if (entryId == null) continue;
                entries.put(entryId, new OfferBuilder(entryId,
                    integer(data.get("revision")), data.getOrDefault("kind", "SELLER_OFFER"), decimal(data.get("amount")),
                    date(data.get("validUntil")), emptyToNull(data.get("terms")), event.createdBy(), event.createdAt()));
            } else if ("OFFER_SENT".equals(event.type())) {
                OfferBuilder entry = entries.get(uuid(data.get("entryId")));
                if (entry != null) entry.sentAt = event.createdAt();
            } else if ("DEAL_WON".equals(event.type())) {
                OfferBuilder entry = entries.get(uuid(data.get("entryId")));
                if (entry != null) entry.acceptedAt = event.createdAt();
            } else if ("DEAL_LOST".equals(event.type())) {
                OfferBuilder entry = entries.get(uuid(data.get("entryId")));
                if (entry != null) entry.rejectedAt = event.createdAt();
            }
        }
        return entries.values().stream().map(OfferBuilder::build)
            .sorted(Comparator.comparingInt(OfferEntry::revision)).toList();
    }

    private List<EventSnapshot> commercialEvents(UUID orgId, UUID dealId) {
        return dsl.select(
                DEAL_CASE_EVENT.EVENT_TYPE,
                DEAL_CASE_EVENT.EVENT_DATA,
                DEAL_CASE_EVENT.CREATED_BY_SUBJECT,
                DEAL_CASE_EVENT.CREATED_AT)
            .from(DEAL_CASE_EVENT)
            .where(DEAL_CASE_EVENT.ORGANIZATION_ID.eq(orgId)
                .and(DEAL_CASE_EVENT.DEAL_CASE_ID.eq(dealId))
                .and(DEAL_CASE_EVENT.EVENT_TYPE.in(
                    "OFFER_PREPARED", "OFFER_SENT", "CUSTOMER_COUNTEROFFER", "DEAL_WON", "DEAL_LOST", "DEAL_CANCELLED")))
            .orderBy(DEAL_CASE_EVENT.CREATED_AT.asc(), DEAL_CASE_EVENT.ID.asc())
            .fetch(record -> new EventSnapshot(
                record.value1(), parseJson(record.value2()), record.value3(), toInstant(record.value4())));
    }

    private Record lockAndAuthorizeSales(UUID orgId, UUID dealId) {
        Record locked = dsl.selectFrom(DEAL_CASE)
            .where(DEAL_CASE.ID.eq(dealId).and(DEAL_CASE.ORGANIZATION_ID.eq(orgId)))
            .forUpdate()
            .fetchOne();
        if (locked == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tehingujuhtumit ei leitud.");
        authorizeMutation(orgId, locked);
        if (TERMINAL_STAGES.contains(locked.get(DEAL_CASE.STAGE))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "Lõpetatud tehingujuhtumit ei saa enam muuta.");
        }
        return locked;
    }

    private void authorizeRead(UUID orgId, Record deal) {
        ownerCollaboration.requireReadable(deal.get(DEAL_CASE.OWNER_ID));
    }

    private void authorizeMutation(UUID orgId, Record deal) {
        ownerCollaboration.requireMutable(deal.get(DEAL_CASE.OWNER_ID));
        if (!ownerCollaboration.hasOrganizationWideAccess()
            && !organizationContext.subject().equals(deal.get(DEAL_CASE.SALES_SUBJECT))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Kommertsetappe saab muuta tehingu müügivastutaja.");
        }
    }

    private int nextMonetaryRevision(UUID orgId, UUID dealId) {
        return dsl.fetchCount(DEAL_CASE_EVENT,
            DEAL_CASE_EVENT.ORGANIZATION_ID.eq(orgId)
                .and(DEAL_CASE_EVENT.DEAL_CASE_ID.eq(dealId))
                .and(DEAL_CASE_EVENT.EVENT_TYPE.in("OFFER_PREPARED", "CUSTOMER_COUNTEROFFER"))) + 1;
    }

    private OfferEntry latestSellerOffer(UUID orgId, UUID dealId) {
        return offerHistory(orgId, dealId).stream()
            .filter(entry -> "SELLER_OFFER".equals(entry.kind()))
            .max(Comparator.comparingInt(OfferEntry::revision))
            .orElse(null);
    }

    private OfferEntry findEntry(UUID orgId, UUID dealId, UUID entryId) {
        return offerHistory(orgId, dealId).stream()
            .filter(entry -> entry.entryId().equals(entryId))
            .findFirst()
            .orElse(null);
    }

    private void transition(UUID orgId, UUID dealId, String expectedStage, String stage,
                            String nextAction, Instant dueAt, Instant closedAt) {
        Instant now = Instant.now();
        int updated = dsl.update(DEAL_CASE)
            .set(DEAL_CASE.STAGE, stage)
            .set(DEAL_CASE.STAGE_SET_AT, dbTime(now))
            .set(DEAL_CASE.NEXT_ACTION, nextAction)
            .set(DEAL_CASE.NEXT_ACTION_DUE_AT, dbTime(dueAt))
            .set(DEAL_CASE.CLOSED_AT, dbTime(closedAt))
            .set(DEAL_CASE.UPDATED_AT, dbTime(now))
            .where(DEAL_CASE.ORGANIZATION_ID.eq(orgId)
                .and(DEAL_CASE.ID.eq(dealId))
                .and(DEAL_CASE.STAGE.eq(expectedStage)))
            .execute();
        if (updated != 1) throw conflict("Tehingujuhtumit muudeti samal ajal. Värskenda andmed ja proovi uuesti.");
    }

    private void addEvent(UUID orgId, UUID dealId, String type, String description, Map<String, String> data) {
        dsl.insertInto(DEAL_CASE_EVENT)
            .columns(
                DEAL_CASE_EVENT.ID,
                DEAL_CASE_EVENT.ORGANIZATION_ID,
                DEAL_CASE_EVENT.DEAL_CASE_ID,
                DEAL_CASE_EVENT.EVENT_TYPE,
                DEAL_CASE_EVENT.DESCRIPTION,
                DEAL_CASE_EVENT.EVENT_DATA,
                DEAL_CASE_EVENT.CREATED_BY_SUBJECT)
            .values(UUID.randomUUID(), orgId, dealId, type, description,
                JSONB.valueOf(toJson(data)), organizationContext.subject())
            .execute();
    }

    private static String requireStage(Record deal, Set<String> allowed, String message) {
        String stage = deal.get(DEAL_CASE.STAGE);
        if (!allowed.contains(stage)) throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, message);
        return stage;
    }

    private static void validateOffer(PrepareOfferRequest request) {
        if (request == null || request.amount() == null || request.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pakkumise summa peab olema positiivne.");
        }
        if (request.validUntil() != null && request.validUntil().isBefore(LocalDate.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pakkumise kehtivus ei tohi olla minevikus.");
        }
    }

    private static void validateCounterOffer(CounterOfferRequest request) {
        if (request == null || request.amount() == null || request.amount().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Kliendi vastupakkumise summa peab olema positiivne.");
        }
    }

    private static Map<String, String> parseJson(JSONB raw) {
        if (raw == null) return Map.of();
        try {
            return JSON.readValue(raw.data(), new TypeReference<Map<String, String>>() { });
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private static String toJson(Map<String, String> data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialize deal event data", exception);
        }
    }

    private static OffsetDateTime dbTime(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static String blank(String value) { return value == null ? "" : value.trim(); }
    private static String emptyToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static UUID uuid(String value) {
        try { return value == null || value.isBlank() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException exception) { return null; }
    }
    private static int integer(String value) {
        try { return Integer.parseInt(value); }
        catch (Exception exception) { return 0; }
    }
    private static BigDecimal decimal(String value) {
        try { return new BigDecimal(value); }
        catch (Exception exception) { return null; }
    }
    private static LocalDate date(String value) {
        try { return value == null || value.isBlank() ? null : LocalDate.parse(value); }
        catch (Exception exception) { return null; }
    }

    public record PrepareOfferRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @FutureOrPresent LocalDate validUntil,
        @Size(max = 4000) String terms
    ) { }

    public record CounterOfferRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @Size(max = 4000) String note
    ) { }

    public record WinDealRequest(
        @NotNull UUID acceptedEntryId,
        @Size(max = 4000) String note
    ) { }

    @Schema(name = "CommercialLoseDealRequest")
    public record LoseDealRequest(
        @NotBlank @Pattern(regexp = LOSS_REASON_PATTERN) String lossReason,
        UUID rejectedEntryId,
        @Size(max = 4000) String note
    ) { }

    public record CancelDealRequest(@NotBlank @Size(max = 4000) String note) { }

    public record CommercialState(
        UUID dealId,
        String stage,
        String nextAction,
        Instant nextActionDueAt,
        Instant closedAt,
        List<OfferEntry> offers
    ) { }

    public record OfferEntry(
        UUID entryId,
        int revision,
        String kind,
        BigDecimal amount,
        LocalDate validUntil,
        String terms,
        String createdBy,
        Instant createdAt,
        Instant sentAt,
        Instant acceptedAt,
        Instant rejectedAt
    ) { }

    private record EventSnapshot(String type, Map<String, String> data, String createdBy, Instant createdAt) { }

    private static final class OfferBuilder {
        private final UUID entryId;
        private final int revision;
        private final String kind;
        private final BigDecimal amount;
        private final LocalDate validUntil;
        private final String terms;
        private final String createdBy;
        private final Instant createdAt;
        private Instant sentAt;
        private Instant acceptedAt;
        private Instant rejectedAt;

        private OfferBuilder(UUID entryId, int revision, String kind, BigDecimal amount, LocalDate validUntil,
                             String terms, String createdBy, Instant createdAt) {
            this.entryId = entryId;
            this.revision = revision;
            this.kind = kind;
            this.amount = amount;
            this.validUntil = validUntil;
            this.terms = terms;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
        }

        private OfferEntry build() {
            return new OfferEntry(entryId, revision, kind, amount, validUntil, terms, createdBy, createdAt,
                sentAt, acceptedAt, rejectedAt);
        }
    }
}
