package ee.forestiq.contract;

import static ee.forestiq.jooq.Tables.CONTRACT;
import static ee.forestiq.jooq.Tables.CONTRACT_HISTORY;
import static org.jooq.impl.DSL.noCondition;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

import ee.forestiq.owner.OwnerDtos.ContractHistoryItem;
import ee.forestiq.owner.OwnerDtos.ContractSearchFilter;

@Repository
public class ContractRepository {

    private final DSLContext dsl;
    private final ObjectMapper objectMapper;

    public ContractRepository(DSLContext dsl, ObjectMapper objectMapper) {
        this.dsl = dsl;
        this.objectMapper = objectMapper;
    }

    public void storeGeneratedContract(
        UUID orgId,
        UUID historyId,
        UUID contractId,
        String data,
        String seller,
        String buyer,
        String contractNumber,
        String cadastres,
        byte[] pdf,
        UUID companyProfileId,
        UUID templateId,
        String fileName,
        String contentType
    ) {
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.insertInto(CONTRACT_HISTORY)
                .columns(CONTRACT_HISTORY.ID, CONTRACT_HISTORY.ORGANIZATION_ID, CONTRACT_HISTORY.DATA,
                    CONTRACT_HISTORY.SELLERS, CONTRACT_HISTORY.BUYER, CONTRACT_HISTORY.CONTRACT_NO,
                    CONTRACT_HISTORY.CADASTRES)
                .values(historyId, orgId, JSONB.valueOf(data), seller, buyer, contractNumber, cadastres)
                .execute();
            tx.insertInto(CONTRACT)
                .columns(CONTRACT.ID, CONTRACT.ORGANIZATION_ID, CONTRACT.BASE_ID, CONTRACT.CONTRACT_DATA,
                    CONTRACT.COMPANY_PROFILE_ID, CONTRACT.TEMPLATE_ID, CONTRACT.OBJECT_KEY, CONTRACT.FILE_NAME,
                    CONTRACT.CONTENT_TYPE)
                .values(contractId, orgId, historyId, pdf, companyProfileId, templateId, null, fileName, contentType)
                .execute();
        });
    }

    public void updateObjectKey(UUID orgId, UUID contractId, String objectKey) {
        dsl.update(CONTRACT)
            .set(CONTRACT.OBJECT_KEY, objectKey)
            .where(CONTRACT.ID.eq(contractId)
                .and(CONTRACT.ORGANIZATION_ID.eq(orgId)))
            .execute();
    }

    public Optional<ContractFile> findContractFile(UUID orgId, UUID contractId) {
        return dsl.select(CONTRACT.CONTRACT_DATA, CONTRACT.OBJECT_KEY, CONTRACT.FILE_NAME)
            .from(CONTRACT)
            .where(CONTRACT.ID.eq(contractId)
                .and(CONTRACT.ORGANIZATION_ID.eq(orgId)))
            .fetchOptional(record -> new ContractFile(record.value1(), record.value2(), record.value3()));
    }

    public List<ContractStorageRecord> findStorageReconciliationBatch(UUID orgId, int offset, int limit) {
        return dsl.select(CONTRACT.ID, CONTRACT.CONTRACT_DATA, CONTRACT.OBJECT_KEY, CONTRACT.FILE_NAME)
            .from(CONTRACT)
            .where(CONTRACT.ORGANIZATION_ID.eq(orgId))
            .orderBy(CONTRACT.ID.asc())
            .limit(limit)
            .offset(offset)
            .fetch(record -> new ContractStorageRecord(record.value1(), record.value2(), record.value3(), record.value4()));
    }

    public List<ContractHistoryItem> searchHistory(UUID orgId, ContractSearchFilter filter) {
        Condition condition = CONTRACT_HISTORY.ORGANIZATION_ID.eq(orgId);
        if (filter != null && filter.cadastre() != null && !filter.cadastre().isBlank()) {
            condition = condition.and(CONTRACT_HISTORY.CADASTRES.likeIgnoreCase("%" + filter.cadastre() + "%"));
        }
        if (filter != null && filter.buyer() != null && !filter.buyer().isBlank()) {
            condition = condition.and(CONTRACT_HISTORY.BUYER.likeIgnoreCase("%" + filter.buyer() + "%"));
        }
        if (filter != null && filter.seller() != null && !filter.seller().isBlank()) {
            condition = condition.and(CONTRACT_HISTORY.SELLERS.likeIgnoreCase("%" + filter.seller() + "%"));
        }
        int offset = filter == null ? 0 : Math.max(filter.offset(), 0);
        return dsl.select(CONTRACT_HISTORY.ID, CONTRACT_HISTORY.SELLERS, CONTRACT_HISTORY.BUYER,
                CONTRACT_HISTORY.CONTRACT_NO, CONTRACT_HISTORY.CREATED_AT, CONTRACT_HISTORY.DATA)
            .from(CONTRACT_HISTORY)
            .where(condition.and(noCondition()))
            .orderBy(CONTRACT_HISTORY.CREATED_AT.desc())
            .limit(10)
            .offset(offset)
            .fetch(record -> {
                SourceRef source = sourceRef(record.value6());
                return new ContractHistoryItem(
                    record.value1(),
                    record.value2(),
                    record.value3(),
                    record.value4(),
                    toInstant(record.value5()),
                    source.dealId(),
                    source.offerEntryId());
            });
    }

    public void deleteHistory(UUID orgId, UUID id) {
        dsl.deleteFrom(CONTRACT_HISTORY)
            .where(CONTRACT_HISTORY.ID.eq(id)
                .and(CONTRACT_HISTORY.ORGANIZATION_ID.eq(orgId)))
            .execute();
    }

    private SourceRef sourceRef(JSONB data) {
        if (data == null) return new SourceRef(null, null);
        try {
            JsonNode source = objectMapper.readTree(data.data()).path("source");
            if (source.isMissingNode() || source.isNull()) return new SourceRef(null, null);
            return new SourceRef(uuid(source.path("dealId").asText(null)), uuid(source.path("offerEntryId").asText(null)));
        } catch (Exception ignored) {
            return new SourceRef(null, null);
        }
    }

    private static UUID uuid(String value) {
        try { return value == null || value.isBlank() ? null : UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static java.time.Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record ContractFile(byte[] data, String objectKey, String fileName) { }
    public record ContractStorageRecord(UUID contractId, byte[] data, String objectKey, String fileName) { }
    private record SourceRef(UUID dealId, UUID offerEntryId) { }
}
