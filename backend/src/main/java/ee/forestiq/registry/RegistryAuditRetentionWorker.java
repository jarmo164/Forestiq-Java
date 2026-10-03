package ee.forestiq.registry;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodic, idempotent retention for registry audit evidence. */
@Component
@ConditionalOnExpression("'${forestiq.runtime.role:all}' == 'worker' || '${forestiq.runtime.role:all}' == 'all'")
class RegistryAuditRetentionWorker {
    private final RegistrySyncAuditRepository auditRepository;
    private final AtomicLong rowCount = new AtomicLong();
    private final AtomicLong largestPayloadBytes = new AtomicLong();
    private final Counter deletedRows;
    private final Counter trimmedPayloads;

    RegistryAuditRetentionWorker(RegistrySyncAuditRepository auditRepository, MeterRegistry meterRegistry) {
        this.auditRepository = auditRepository;
        this.deletedRows = Counter.builder("forestiq.registry.audit.deleted.rows")
            .description("Registry sync audit rows deleted by retention")
            .register(meterRegistry);
        this.trimmedPayloads = Counter.builder("forestiq.registry.audit.trimmed.payloads")
            .description("Short-lived troubleshooting payloads removed from retained audit rows")
            .register(meterRegistry);
        Gauge.builder("forestiq.registry.audit.rows", rowCount, AtomicLong::get)
            .description("Current registry sync audit row count")
            .register(meterRegistry);
        Gauge.builder("forestiq.registry.audit.largest.payload.bytes", largestPayloadBytes, AtomicLong::get)
            .description("Largest currently stored registry audit payload in bytes")
            .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${forestiq.registry.audit.cleanup-fixed-delay-ms:21600000}")
    void cleanup() {
        RegistrySyncAuditRepository.CleanupResult result = auditRepository.cleanupExpired();
        if (result.rowsDeleted() > 0) deletedRows.increment(result.rowsDeleted());
        if (result.troubleshootingPayloadsTrimmed() > 0) {
            trimmedPayloads.increment(result.troubleshootingPayloadsTrimmed());
        }
        rowCount.set(auditRepository.rowCount());
        largestPayloadBytes.set(auditRepository.largestStoredPayloadBytes());
    }
}
