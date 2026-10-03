package ee.forestiq.contract;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ee.forestiq.contract.ContractRepository.ContractStorageRecord;

/** Bounded repair job for the intentionally duplicated DB + object-storage PDF copies. */
@Service
public class ContractStorageReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ContractStorageReconciliationService.class);
    private static final int MAX_BATCH = 500;

    private final ContractRepository repository;
    private final ContractStorageService storageService;

    public ContractStorageReconciliationService(ContractRepository repository, ContractStorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    public ReconciliationResult reconcile(UUID organizationId, int offset, int requestedLimit) {
        int safeOffset = Math.max(0, offset);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_BATCH));
        int checked = 0;
        int repaired = 0;
        int failed = 0;
        for (ContractStorageRecord record : repository.findStorageReconciliationBatch(organizationId, safeOffset, limit)) {
            checked++;
            byte[] databaseCopy = record.data();
            if (databaseCopy == null || databaseCopy.length == 0) {
                failed++;
                log.error("Contract {} has no immutable database PDF; object-storage reconciliation cannot repair it", record.contractId());
                continue;
            }
            try {
                boolean healthy = false;
                if (record.objectKey() != null && !record.objectKey().isBlank()) {
                    try {
                        byte[] objectCopy = storageService.get(record.objectKey());
                        healthy = objectCopy != null && objectCopy.length > 0
                            && MessageDigest.isEqual(sha256(databaseCopy), sha256(objectCopy));
                    } catch (RuntimeException exception) {
                        log.warn("Contract {} object copy could not be read during reconciliation", record.contractId(), exception);
                    }
                }
                if (!healthy) {
                    String fileName = record.fileName() == null || record.fileName().isBlank()
                        ? "leping-" + record.contractId() + ".pdf" : record.fileName();
                    String objectKey = storageService.putPdf(organizationId, record.contractId(), fileName, databaseCopy);
                    repository.updateObjectKey(organizationId, record.contractId(), objectKey);
                    repaired++;
                    log.info("Repaired object-storage copy for contract {}", record.contractId());
                }
            } catch (RuntimeException exception) {
                failed++;
                log.warn("Contract {} object-storage reconciliation failed", record.contractId(), exception);
            }
        }
        return new ReconciliationResult(safeOffset, limit, checked, repaired, failed);
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public record ReconciliationResult(int offset, int limit, int checked, int repaired, int failed) { }
}
