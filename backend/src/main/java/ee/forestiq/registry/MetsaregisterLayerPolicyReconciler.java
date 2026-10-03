package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.time.Instant;

import org.jooq.DSLContext;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies the checked-in layer manifest to the runtime discovery catalogue. */
@Service
class MetsaregisterLayerPolicyReconciler {
    private final DSLContext dsl;
    private final MetsaregisterLayerManifestService manifest;

    MetsaregisterLayerPolicyReconciler(DSLContext dsl, MetsaregisterLayerManifestService manifest) {
        this.dsl = dsl;
        this.manifest = manifest;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reconcileOnStartup() {
        reconcile();
    }

    @Transactional
    PolicySummary reconcile() {
        Instant now = Instant.now();

        // Unknown/discovered rows stay catalogue-only. This is the guardrail
        // that prevents a new provider layer from silently expanding storage.
        dsl.update(table("metsaregister_wfs_layer"))
            .set(field(name("approved"), Boolean.class), false)
            .set(field(name("enabled"), Boolean.class), false)
            .set(field(name("consumption_mode"), String.class), "DISABLED")
            .set(field(name("migration_disposition"), String.class), "RETIRE")
            .set(field(name("manifest_version"), String.class), (String) null)
            .set(field(name("updated_at"), Instant.class), now)
            .execute();

        int declared = 0;
        int local = 0;
        int external = 0;
        int disabled = 0;
        for (var layer : manifest.layers()) {
            boolean localDomain = layer.enabled()
                && layer.primaryMode() == MetsaregisterLayerManifestService.PrimaryMode.LOCAL_DOMAIN_DATA;
            boolean exists = dsl.fetchExists(
                dsl.selectOne().from(table("metsaregister_wfs_layer"))
                    .where(field(name("layer_name"), String.class).eq(layer.key())));
            if (!exists) {
                dsl.insertInto(table("metsaregister_wfs_layer"))
                    .columns(
                        field(name("layer_name")), field(name("title")), field(name("approved")),
                        field(name("enabled")), field(name("provider_present")), field(name("state")),
                        field(name("default_crs")), field(name("consumption_mode")),
                        field(name("migration_disposition")), field(name("manifest_version")),
                        field(name("updated_at")))
                    .values(layer.key(), layer.title(), localDomain, localDomain, false, "PENDING_SCHEMA",
                        layer.sourceCrs(), layer.primaryMode().name(), layer.disposition().name(),
                        manifest.version(), now)
                    .execute();
            } else {
                dsl.update(table("metsaregister_wfs_layer"))
                    .set(field(name("title"), String.class), layer.title())
                    .set(field(name("approved"), Boolean.class), localDomain)
                    .set(field(name("enabled"), Boolean.class), localDomain)
                    .set(field(name("consumption_mode"), String.class), layer.primaryMode().name())
                    .set(field(name("migration_disposition"), String.class), layer.disposition().name())
                    .set(field(name("manifest_version"), String.class), manifest.version())
                    .set(field(name("updated_at"), Instant.class), now)
                    .where(field(name("layer_name"), String.class).eq(layer.key()))
                    .execute();
            }
            declared++;
            if (localDomain) local++;
            else if (layer.primaryMode() == MetsaregisterLayerManifestService.PrimaryMode.EXTERNAL_MAP) external++;
            else disabled++;
        }
        return new PolicySummary(declared, local, external, disabled);
    }

    record PolicySummary(int declared, int localDomain, int externalMap, int disabledOrOther) { }
}
