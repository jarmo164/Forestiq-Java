package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bounded retention policy for immutable WFS generations. */
@Service
class MetsaregisterWfsGenerationMaintenanceService {
    private final DSLContext dsl;
    private final int retiredRetentionDays;

    MetsaregisterWfsGenerationMaintenanceService(
        DSLContext dsl,
        @Value("${forestiq.registry.metsaregister.retired-generation-retention-days:14}") int retiredRetentionDays
    ) {
        this.dsl = dsl;
        this.retiredRetentionDays = Math.max(1, retiredRetentionDays);
    }

    @Transactional
    CleanupResult cleanupRetired() {
        Instant cutoff = Instant.now().minus(retiredRetentionDays, ChronoUnit.DAYS);
        long featureRows = countRetiredFeaturesBefore(cutoff);
        int generations = dsl.deleteFrom(table("metsaregister_wfs_generation"))
            .where(field(name("state"), String.class).eq("RETIRED")
                .and(field(name("completed_at"), Instant.class).lt(cutoff))
                .and(field(name("id")).notIn(
                    dsl.select(field(name("current_generation_id")))
                        .from(table("metsaregister_wfs_layer"))
                        .where(field(name("current_generation_id")).isNotNull())))
                .and(field(name("id")).notIn(
                    dsl.select(field(name("layer_generation_id")))
                        .from(table("metsaregister_forestry_generation_member")))))
            .execute();
        return new CleanupResult(cutoff, retiredRetentionDays, generations, featureRows);
    }

    private long countRetiredFeaturesBefore(Instant cutoff) {
        Long count = dsl.fetchOne("""
            SELECT count(*)::bigint AS c
              FROM metsaregister_wfs_feature f
              JOIN metsaregister_wfs_generation g ON g.id=f.generation_id
             WHERE g.state='RETIRED'
               AND g.completed_at < CAST(? AS timestamptz)
               AND NOT EXISTS (
                   SELECT 1 FROM metsaregister_wfs_layer l
                    WHERE l.current_generation_id=g.id
               )
               AND NOT EXISTS (
                   SELECT 1 FROM metsaregister_forestry_generation_member m
                    WHERE m.layer_generation_id=g.id
               )
            """, cutoff).get("c", Long.class);
        return count == null ? 0L : count;
    }

    record CleanupResult(
        Instant cutoff,
        int retentionDays,
        int generationsDeleted,
        long featureRowsDeleted
    ) { }
}
