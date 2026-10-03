package ee.forestiq.deal.contractsource;

import static ee.forestiq.jooq.Tables.DEAL_CASE;
import static ee.forestiq.jooq.Tables.DEAL_CASE_EVENT;
import static ee.forestiq.jooq.Tables.DEAL_CASE_PARCEL;
import static ee.forestiq.jooq.Tables.LAND_PARCEL;
import static ee.forestiq.jooq.Tables.OWNER;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.owner.collaboration.OwnerCollaborationPort;
import ee.forestiq.security.OrganizationContext;

/** Deal-owned projection of the authoritative WON event and accepted offer timeline. */
@Service
public class WonDealContractSourceService implements WonDealContractSourcePort {

    private final DSLContext dsl;
    private final OrganizationContext organizationContext;
    private final ObjectMapper objectMapper;
    private final OwnerCollaborationPort ownerCollaboration;

    public WonDealContractSourceService(
        DSLContext dsl,
        OrganizationContext organizationContext,
        ObjectMapper objectMapper,
        OwnerCollaborationPort ownerCollaboration) {
        this.dsl = dsl;
        this.organizationContext = organizationContext;
        this.objectMapper = objectMapper;
        this.ownerCollaboration = ownerCollaboration;
    }

    @Override
    @Transactional(readOnly = true)
    public WonDealContractSource requireWonAcceptedOffer(UUID dealId) {
        UUID organizationId = organizationContext.requiredOrganizationId();
        Record deal = dsl.select(
                DEAL_CASE.ID,
                DEAL_CASE.OWNER_ID,
                DEAL_CASE.STAGE,
                OWNER.DISPLAY_NAME,
                OWNER.PERSONAL_OR_REGISTRY_CODE,
                OWNER.ADDRESS)
            .from(DEAL_CASE)
            .join(OWNER).on(OWNER.ID.eq(DEAL_CASE.OWNER_ID)
                .and(OWNER.ORGANIZATION_ID.eq(DEAL_CASE.ORGANIZATION_ID)))
            .where(DEAL_CASE.ID.eq(dealId).and(DEAL_CASE.ORGANIZATION_ID.eq(organizationId)))
            .fetchOne();
        if (deal == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tehingujuhtumit ei leitud.");
        }

        ownerCollaboration.requireReadable(deal.get(DEAL_CASE.OWNER_ID));
        if (!"WON".equals(deal.get(DEAL_CASE.STAGE))) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Lepingu saab luua ainult võidetud tehingujuhtumist.");
        }

        AcceptedOffer accepted = acceptedOffer(organizationId, dealId);
        List<WonDealContractSourcePort.Parcel> parcels = dsl.select(
                LAND_PARCEL.CADASTRAL_CODE,
                LAND_PARCEL.ADDRESS,
                LAND_PARCEL.AREA_HECTARES)
            .from(DEAL_CASE_PARCEL)
            .join(LAND_PARCEL).on(LAND_PARCEL.ID.eq(DEAL_CASE_PARCEL.PARCEL_ID)
                .and(LAND_PARCEL.ORGANIZATION_ID.eq(DEAL_CASE_PARCEL.ORGANIZATION_ID)))
            .where(DEAL_CASE_PARCEL.DEAL_CASE_ID.eq(dealId)
                .and(DEAL_CASE_PARCEL.ORGANIZATION_ID.eq(organizationId)))
            .orderBy(LAND_PARCEL.CADASTRAL_CODE.asc())
            .fetch(record -> new WonDealContractSourcePort.Parcel(record.value1(), record.value2(), record.value3()));
        if (parcels.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                "Tehingujuhtumil ei ole lepingusse lisatavaid kinnistuid.");
        }

        return new WonDealContractSource(
            dealId,
            accepted.entryId(),
            accepted.amount(),
            accepted.terms(),
            new WonDealContractSourcePort.Seller(
                deal.get(OWNER.DISPLAY_NAME),
                deal.get(OWNER.PERSONAL_OR_REGISTRY_CODE),
                deal.get(OWNER.ADDRESS)),
            parcels);
    }

    private AcceptedOffer acceptedOffer(UUID organizationId, UUID dealId) {
        JSONB wonData = dsl.select(DEAL_CASE_EVENT.EVENT_DATA)
            .from(DEAL_CASE_EVENT)
            .where(DEAL_CASE_EVENT.ORGANIZATION_ID.eq(organizationId)
                .and(DEAL_CASE_EVENT.DEAL_CASE_ID.eq(dealId))
                .and(DEAL_CASE_EVENT.EVENT_TYPE.eq("DEAL_WON")))
            .orderBy(DEAL_CASE_EVENT.CREATED_AT.desc(), DEAL_CASE_EVENT.ID.desc())
            .limit(1)
            .fetchOne(DEAL_CASE_EVENT.EVENT_DATA);
        Map<String, String> acceptedData = parse(wonData);
        UUID entryId = uuid(acceptedData.get("entryId"));
        BigDecimal amount = decimal(acceptedData.get("amount"));
        if (entryId == null || amount == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Võidetud tehingul puudub auditeeritav aktsepteeritud pakkumine.");
        }

        String terms = null;
        boolean sourceFound = false;
        List<JSONB> sourceEvents = dsl.select(DEAL_CASE_EVENT.EVENT_DATA)
            .from(DEAL_CASE_EVENT)
            .where(DEAL_CASE_EVENT.ORGANIZATION_ID.eq(organizationId)
                .and(DEAL_CASE_EVENT.DEAL_CASE_ID.eq(dealId))
                .and(DEAL_CASE_EVENT.EVENT_TYPE.in("OFFER_PREPARED", "CUSTOMER_COUNTEROFFER")))
            .orderBy(DEAL_CASE_EVENT.CREATED_AT.asc(), DEAL_CASE_EVENT.ID.asc())
            .fetch(DEAL_CASE_EVENT.EVENT_DATA);
        for (JSONB eventData : sourceEvents) {
            Map<String, String> data = parse(eventData);
            if (entryId.equals(uuid(data.get("entryId")))) {
                terms = blankToNull(data.get("terms"));
                sourceFound = true;
                break;
            }
        }
        if (!sourceFound) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Aktsepteeritud pakkumise lähteandmeid ei leitud tehingu auditiajaloost.");
        }
        return new AcceptedOffer(entryId, amount, terms);
    }

    private Map<String, String> parse(JSONB json) {
        if (json == null) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(json.data());
            if (node != null && node.isTextual()) node = objectMapper.readTree(node.textValue());
            if (node == null || !node.isObject()) throw new IllegalArgumentException("Expected JSON object");
            return objectMapper.convertValue(node, new TypeReference<Map<String, String>>() { });
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Tehingu pakkumise auditandmed on vigased.", exception);
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static BigDecimal decimal(String value) {
        try {
            return value == null || value.isBlank() ? null : new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record AcceptedOffer(UUID entryId, BigDecimal amount, String terms) {
    }
}
