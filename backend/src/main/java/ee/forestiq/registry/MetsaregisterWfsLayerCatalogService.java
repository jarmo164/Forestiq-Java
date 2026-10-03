package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;
import java.util.List;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reconciles the approved local layer catalogue with live provider capabilities. */
@Service
class MetsaregisterWfsLayerCatalogService {
    private final DSLContext dsl;
    private final MetsaregisterWfsMetadataClient metadataClient;

    MetsaregisterWfsLayerCatalogService(DSLContext dsl, MetsaregisterWfsMetadataClient metadataClient) {
        this.dsl = dsl;
        this.metadataClient = metadataClient;
    }

    @Transactional
    ReconcileResult reconcileCapabilities() {
        List<MetsaregisterWfsMetadataClient.FeatureType> discovered = metadataClient.getCapabilities();
        Instant now = Instant.now();

        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("provider_present"), Boolean.class), false)
            .set(field(name("updated_at"), Instant.class), now)
            .execute();

        int added = 0;
        int present = 0;
        for (var featureType : discovered) {
            boolean exists = dsl.fetchExists(
                dsl.selectOne().from(table("metsaregister_wfs_layer"))
                    .where(field(name("layer_name"), String.class).eq(featureType.name())));
            if (!exists) added++;

            dsl.insertInto(table("metsaregister_wfs_layer"))
                .columns(
                    field(name("layer_name")), field(name("title")), field(name("approved")),
                    field(name("enabled")), field(name("provider_present")), field(name("state")),
                    field(name("default_crs")), field(name("last_seen_at")), field(name("updated_at")))
                .values(featureType.name(), featureType.title(), false, false, true, "PENDING_SCHEMA",
                    featureType.defaultCrs(), now, now)
                .onConflict(field(name("layer_name")))
                .doUpdate()
                .set(field(name("title"), String.class), featureType.title())
                .set(field(name("provider_present"), Boolean.class), true)
                .set(field(name("default_crs"), String.class), featureType.defaultCrs())
                .set(field(name("last_seen_at"), Instant.class), now)
                .set(field(name("updated_at"), Instant.class), now)
                .execute();
            present++;
        }

        int missingApproved = dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("state"), String.class), "SCHEMA_DRIFT")
            .set(field(name("last_error"), String.class), "Approved WFS feature type is missing from GetCapabilities")
            .set(field(name("updated_at"), Instant.class), now)
            .where(field(name("approved"), Boolean.class).isTrue()
                .and(field(name("provider_present"), Boolean.class).isFalse()))
            .execute();

        long approvedPresent = dsl.selectCount().from(table("metsaregister_wfs_layer"))
            .where(field(name("approved"), Boolean.class).isTrue()
                .and(field(name("provider_present"), Boolean.class).isTrue()))
            .fetchOne(0, long.class);

        return new ReconcileResult(present, added, missingApproved, approvedPresent);
    }

    record ReconcileResult(int providerLayers, int newlyDiscovered, int missingApproved, long approvedPresent) { }
}
