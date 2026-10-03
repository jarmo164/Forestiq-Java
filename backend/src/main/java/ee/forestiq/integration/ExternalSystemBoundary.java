package ee.forestiq.integration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks Java classes that intentionally call systems outside ForestIQ.
 * Batch ETL provider calls should migrate to the Python ingest runtime; new
 * Java external HTTP boundaries must declare why they are allowed to remain.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExternalSystemBoundary {
    Kind value();

    String reason();

    enum Kind {
        LEGACY_JAVA_INGESTION,
        REAL_TIME_DOMAIN_OPERATION,
        PLATFORM_ADMIN_CLIENT
    }
}
