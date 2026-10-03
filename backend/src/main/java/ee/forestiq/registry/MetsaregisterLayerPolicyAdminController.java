package ee.forestiq.registry;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;

import java.util.List;

import org.jooq.DSLContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/integrations/metsaregister-wfs/policy")
@PreAuthorize("hasRole('ORG_ADMIN')")
class MetsaregisterLayerPolicyAdminController {
    private final DSLContext dsl;
    private final MetsaregisterLayerPolicyReconciler reconciler;

    MetsaregisterLayerPolicyAdminController(DSLContext dsl, MetsaregisterLayerPolicyReconciler reconciler) {
        this.dsl = dsl;
        this.reconciler = reconciler;
    }

    @GetMapping
    List<LayerPolicyView> policy() {
        return dsl.select(
                field(name("layer_name"), String.class),
                field(name("title"), String.class),
                field(name("provider_present"), Boolean.class),
                field(name("approved"), Boolean.class),
                field(name("enabled"), Boolean.class),
                field(name("consumption_mode"), String.class),
                field(name("migration_disposition"), String.class),
                field(name("manifest_version"), String.class),
                field(name("state"), String.class),
                field(name("last_error"), String.class))
            .from(name("metsaregister_wfs_layer"))
            .orderBy(field(name("consumption_mode")).asc(), field(name("layer_name")).asc())
            .fetch(record -> new LayerPolicyView(
                record.value1(), record.value2(), Boolean.TRUE.equals(record.value3()),
                Boolean.TRUE.equals(record.value4()), Boolean.TRUE.equals(record.value5()),
                record.value6(), record.value7(), record.value8(), record.value9(), record.value10()));
    }

    @PostMapping("/reconcile")
    MetsaregisterLayerPolicyReconciler.PolicySummary reconcile() {
        return reconciler.reconcile();
    }

    record LayerPolicyView(
        String layerName,
        String title,
        boolean discovered,
        boolean approvedForLocalIngestion,
        boolean scheduledIngestionEnabled,
        String consumptionMode,
        String migrationDisposition,
        String manifestVersion,
        String runtimeState,
        String lastError
    ) { }
}
