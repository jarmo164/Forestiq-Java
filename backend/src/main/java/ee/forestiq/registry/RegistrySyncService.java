package ee.forestiq.registry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import ee.forestiq.registry.ExternalRegistryService.ForestRecord;
import ee.forestiq.registry.ExternalRegistryService.ForestRegistryResponse;
import ee.forestiq.registry.ExternalRegistryService.MaaametCadastre;
import ee.forestiq.registry.RegistryDataRepository.ParcelOwnerRef;
import ee.forestiq.registry.RegistryDataRepository.ParcelRef;
import ee.forestiq.security.OrganizationContext;

/**
 * Application service coordinating official registry providers, transaction isolation,
 * change-signal decisions and sync audit. SQL snapshot persistence lives in RegistryDataRepository.
 */
@Service
public class RegistrySyncService {

    private static final String SYSTEM_SUBJECT = "system-registry-refresh";
    private static final String MAAAMET_WFS = "MAAAMET_WFS";
    private static final String MAAAMET_WFS_DIAGNOSTIC = "MAAAMET_WFS_DIAGNOSTIC";

    private final ExternalRegistryService registryService;
    private final RegistryDataRepository dataRepository;
    private final OrganizationContext organizationContext;
    private final RegistrySyncAuditRepository auditRepository;
    private final TransactionTemplate isolatedTransaction;

    public RegistrySyncService(ExternalRegistryService registryService, RegistryDataRepository dataRepository,
                               OrganizationContext organizationContext, PlatformTransactionManager transactionManager,
                               RegistrySyncAuditRepository auditRepository) {
        this.registryService = registryService;
        this.dataRepository = dataRepository;
        this.organizationContext = organizationContext;
        this.auditRepository = auditRepository;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolatedTransaction.setName("registry-source-refresh");
    }

    @Transactional(readOnly = true)
    public ExternalRegistryService.RegistryHealth registryHealth() {
        return registryService.checkHealth();
    }

    public Map<String, SyncResult> refreshAll(String cadastralCode) {
        UUID orgId = organizationContext.requiredOrganizationId();
        return refreshAllForOrganization(orgId, cadastralCode, organizationContext.subject());
    }

    public Map<String, SyncResult> refreshAllForOrganization(UUID orgId, String cadastralCode) {
        return refreshAllForOrganization(orgId, cadastralCode, SYSTEM_SUBJECT);
    }

    private Map<String, SyncResult> refreshAllForOrganization(UUID orgId, String cadastralCode, String subject) {
        Map<String, SyncResult> results = new LinkedHashMap<>();
        results.put("cadastre", captureSync(MAAAMET_WFS_DIAGNOSTIC, cadastralCode,
            () -> refreshMaaamet(orgId, cadastralCode, subject)));
        results.put("forestNotifications", captureSync("METSAREGISTER_TEATISED", cadastralCode,
            () -> refreshForestNotifications(orgId, cadastralCode, subject)));
        results.put("forestPlan", captureSync("METSAREGISTER_ERALDISED", cadastralCode,
            () -> refreshForestPlan(orgId, cadastralCode, subject)));
        return results;
    }

    /** Runs a targeted Maa-amet WFS diagnostic without mutating canonical parcel data. */
    @Transactional
    public SyncResult refreshMaaamet(String cadastralCode) {
        return refreshMaaamet(organizationContext.requiredOrganizationId(), cadastralCode, organizationContext.subject());
    }

    private SyncResult refreshMaaamet(UUID orgId, String cadastralCode, String subject) {
        try {
            dataRepository.ensureParcelExists(orgId, cadastralCode);
            MaaametCadastre data = registryService.fetchMaaametCadastre(cadastralCode)
                .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "Katastriüksust Maa-ameti WFS-ist ei leitud"));
            inNewTransaction(() -> {
                auditRepository.logSync(orgId, cadastralCode, MAAAMET_WFS_DIAGNOSTIC, "SUCCESS", 0, null,
                    data.rawResponse(), null, subject);
                return null;
            });
            return new SyncResult(MAAAMET_WFS_DIAGNOSTIC, cadastralCode, 0,
                "Maa-ameti WFS diagnostika tehtud; kanoniseeritud katastriandmeid ei muudetud.", true);
        } catch (RuntimeException exception) {
            logFailure(orgId, cadastralCode, MAAAMET_WFS_DIAGNOSTIC, exception, subject);
            throw exception;
        }
    }

    /** Fetches and persists forest notifications exposed by the official Metsaregister WFS. */
    @Transactional
    public SyncResult refreshForestNotifications(String cadastralCode) {
        return refreshForestNotifications(organizationContext.requiredOrganizationId(), cadastralCode,
            organizationContext.subject());
    }

    private SyncResult refreshForestNotifications(UUID orgId, String cadastralCode, String subject) {
        try {
            dataRepository.ensureParcelExists(orgId, cadastralCode);
            ForestRegistryResponse response = registryService.fetchForestNotifications(cadastralCode);
            return inNewTransaction(() -> persistForestNotifications(orgId, cadastralCode, subject, response));
        } catch (RuntimeException exception) {
            logFailure(orgId, cadastralCode, "METSAREGISTER_TEATISED", exception, subject);
            throw exception;
        }
    }

    private SyncResult persistForestNotifications(UUID orgId, String cadastralCode, String subject,
                                                  ForestRegistryResponse response) {
        ParcelOwnerRef parcel = dataRepository.parcelOwnerRef(orgId, cadastralCode);
        Set<String> existingKeys = dataRepository.activeNotificationKeys(orgId, cadastralCode);
        int records = dataRepository.replaceForestNotifications(orgId, cadastralCode, response.records());
        for (ForestRecord record : response.records()) {
            String sourceKey = record.sourceId() != null ? String.valueOf(record.sourceId()) : record.title();
            if (sourceKey != null && !existingKeys.contains(sourceKey)) {
                for (UUID ownerId : parcel.ownerIds()) {
                    auditRepository.recordDataSignal(orgId, ownerId, parcel.parcelId(),
                        "NEW_FOREST_NOTIFICATION", "METSAREGISTER_TEATISED", cadastralCode + ":" + sourceKey,
                        "Uus metsateatis katastriüksusel " + cadastralCode
                            + (record.workCode() == null ? "" : " (" + record.workCode() + ")") + ".",
                        record.raw(), subject);
                }
            }
        }
        auditRepository.logSync(orgId, cadastralCode, "METSAREGISTER_TEATISED", "SUCCESS", records, null,
            response.rawResponse(), response.requestUrl(), subject);
        return new SyncResult("METSAREGISTER_TEATISED", cadastralCode, records,
            "Metsateatised värskendatud.", true);
    }

    /** Fetches and persists forest-plan / compartment records exposed by official Metsaregister WFS. */
    @Transactional
    public SyncResult refreshForestPlan(String cadastralCode) {
        return refreshForestPlan(organizationContext.requiredOrganizationId(), cadastralCode,
            organizationContext.subject());
    }

    private SyncResult refreshForestPlan(UUID orgId, String cadastralCode, String subject) {
        try {
            dataRepository.ensureParcelExists(orgId, cadastralCode);
            ForestRegistryResponse response = registryService.fetchForestPlan(cadastralCode);
            return inNewTransaction(() -> persistForestPlan(orgId, cadastralCode, subject, response));
        } catch (RuntimeException exception) {
            logFailure(orgId, cadastralCode, "METSAREGISTER_ERALDISED", exception, subject);
            throw exception;
        }
    }

    private SyncResult persistForestPlan(UUID orgId, String cadastralCode, String subject,
                                         ForestRegistryResponse response) {
        ParcelOwnerRef parcel = dataRepository.parcelOwnerRef(orgId, cadastralCode);
        Map<String, String> existingFeatures = dataRepository.forestPlanFeatureVersions(orgId, cadastralCode);
        int records = dataRepository.replaceForestPlan(orgId, cadastralCode, response.records());
        for (ForestRecord record : response.records()) {
            String id = record.sourceId() == null ? record.title() : String.valueOf(record.sourceId());
            String serialized = auditRepository.safeJson(record.raw());
            if (id != null && !serialized.equals(existingFeatures.get(id))) {
                String version = Integer.toUnsignedString(serialized.hashCode());
                for (UUID ownerId : parcel.ownerIds()) {
                    auditRepository.recordDataSignal(orgId, ownerId, parcel.parcelId(),
                        "NEW_FOREST_COMPARTMENT", "METSAREGISTER_ERALDISED",
                        cadastralCode + ":" + id + ":" + version,
                        "Uus või muutunud metsakorralduse eraldis katastriüksusel " + cadastralCode + ".",
                        record.raw(), subject);
                }
            }
        }
        auditRepository.logSync(orgId, cadastralCode, "METSAREGISTER_ERALDISED", "SUCCESS", records, null,
            response.rawResponse(), response.requestUrl(), subject);
        return new SyncResult("METSAREGISTER_ERALDISED", cadastralCode, records,
            "Metsakorralduskava andmed värskendatud.", true);
    }

    /** Refreshes only missing or stale source snapshots. A forest refresh never needlessly refreshes cadastre data. */
    @Transactional
    public BatchSyncResult refreshStalePublicData(int batchSize, int cadastreMaxAgeHours, int forestMaxAgeHours) {
        return refreshStalePublicData(null, batchSize, cadastreMaxAgeHours, forestMaxAgeHours);
    }

    /** Runs the same freshness policy for one organisation when an operator requests recovery. */
    @Transactional
    public BatchSyncResult refreshStalePublicDataForOrganization(UUID organizationId, int batchSize,
                                                                 int cadastreMaxAgeHours, int forestMaxAgeHours) {
        return refreshStalePublicData(organizationId, batchSize, cadastreMaxAgeHours, forestMaxAgeHours);
    }

    /** Runs one provider projection only, used by the unified integration job catalog. */
    public BatchSyncResult refreshStaleSourceForOrganization(UUID organizationId, String registry,
                                                              int batchSize, int maxAgeHours) {
        Instant cutoff = Instant.now().minus(Math.max(1, maxAgeHours), ChronoUnit.HOURS);
        List<ParcelRef> parcels = dataRepository.staleParcelsForSource(organizationId, registry, cutoff, batchSize);
        if (MAAAMET_WFS.equals(registry)) {
            return new BatchSyncResult(0, 0, 0);
        }
        int successful = 0;
        int failed = 0;
        for (ParcelRef parcel : parcels) {
            try {
                switch (registry) {
                    case MAAAMET_WFS -> throw new IllegalArgumentException(
                        "Maa-amet WFS is diagnostic-only and cannot be used for canonical per-parcel recovery.");
                    case "METSAREGISTER_TEATISED" -> refreshForestNotifications(parcel.organizationId(), parcel.cadastralCode(), SYSTEM_SUBJECT);
                    case "METSAREGISTER_ERALDISED" -> refreshForestPlan(parcel.organizationId(), parcel.cadastralCode(), SYSTEM_SUBJECT);
                    default -> throw new IllegalArgumentException("Unsupported registry job: " + registry);
                }
                successful++;
            } catch (RuntimeException exception) {
                failed++;
            }
        }
        return new BatchSyncResult(parcels.size(), successful, failed);
    }

    private BatchSyncResult refreshStalePublicData(UUID organizationId, int batchSize,
                                                   int cadastreMaxAgeHours, int forestMaxAgeHours) {
        Instant cadastreCutoff = Instant.now().minus(Math.max(1, cadastreMaxAgeHours), ChronoUnit.HOURS);
        Instant forestCutoff = Instant.now().minus(Math.max(1, forestMaxAgeHours), ChronoUnit.HOURS);
        List<ParcelRef> parcels = dataRepository.staleParcels(organizationId, cadastreCutoff, forestCutoff, batchSize);
        int successful = 0;
        int failed = 0;
        for (ParcelRef parcel : parcels) {
            boolean parcelFailed = false;
            boolean sourceAttempted = false;
            if (!dataRepository.sourceIsFresh(parcel.organizationId(), parcel.cadastralCode(),
                    "METSAREGISTER_TEATISED", forestCutoff)) {
                sourceAttempted = true;
                try {
                    refreshForestNotifications(parcel.organizationId(), parcel.cadastralCode(), SYSTEM_SUBJECT);
                } catch (RuntimeException exception) {
                    parcelFailed = true;
                }
            }
            if (!dataRepository.sourceIsFresh(parcel.organizationId(), parcel.cadastralCode(),
                    "METSAREGISTER_ERALDISED", forestCutoff)) {
                sourceAttempted = true;
                try {
                    refreshForestPlan(parcel.organizationId(), parcel.cadastralCode(), SYSTEM_SUBJECT);
                } catch (RuntimeException exception) {
                    parcelFailed = true;
                }
            }
            if (sourceAttempted && !parcelFailed) successful++;
            if (parcelFailed) failed++;
        }
        return new BatchSyncResult(parcels.size(), successful, failed);
    }

    @Transactional(readOnly = true)
    public int fetchRikCadastreCount(String code, String firstName, String lastNameOrCompany) {
        return registryService.fetchRikCadastreCount(code, firstName, lastNameOrCompany);
    }

    private SyncResult captureSync(String registry, String cadastralCode, Supplier<SyncResult> action) {
        try {
            return action.get();
        } catch (RuntimeException exception) {
            return new SyncResult(registry, cadastralCode, 0,
                "Värskendamine ebaõnnestus: " + safeMessage(exception), false);
        }
    }

    private static String safeMessage(RuntimeException exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException && current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
        }
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "välisandmeallikas ei vastanud" : message;
    }

    private <T> T inNewTransaction(Supplier<T> action) {
        return isolatedTransaction.execute(status -> action.get());
    }

    private void logFailure(UUID orgId, String cadastralCode, String registry,
                            RuntimeException exception, String subject) {
        try {
            inNewTransaction(() -> {
                auditRepository.logSync(orgId, cadastralCode, registry, "FAILED", 0,
                    safeMessage(exception), null, null, subject);
                return null;
            });
        } catch (RuntimeException ignored) {
            // A log-write issue must not hide the original provider error.
        }
    }

    public record SyncResult(String registry, String cadastralCode, int recordsUpdated, String message, boolean success) { }
    public record BatchSyncResult(int considered, int successful, int failed) { }
}
