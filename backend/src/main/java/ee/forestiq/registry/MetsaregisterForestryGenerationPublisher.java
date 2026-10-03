package ee.forestiq.registry;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes a new atomic forestry bundle only when no retained source layer is mid-refresh. */
@Component
class MetsaregisterForestryGenerationPublisher {
    private static final Logger log = LoggerFactory.getLogger(MetsaregisterForestryGenerationPublisher.class);

    private final DSLContext dsl;
    private final MetsaregisterForestryGenerationService generations;
    private final boolean enabled;

    MetsaregisterForestryGenerationPublisher(
        DSLContext dsl,
        MetsaregisterForestryGenerationService generations,
        @Value("${forestiq.registry.auto-refresh.enabled:true}") boolean enabled
    ) {
        this.dsl = dsl;
        this.generations = generations;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${forestiq.registry.metsaregister.forestry-publish-fixed-delay-ms:60000}")
    void publishSettledBundle() {
        if (!enabled || hasActiveRefresh()) return;
        try {
            generations.publishIfChanged("system:metsaregister-forestry-publisher",
                "All retained Metsaregister source layers are settled");
        } catch (IllegalStateException exception) {
            // Startup/incomplete source state is expected until every required layer has a good generation.
            log.debug("Forestry generation not publishable yet: {}", exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("Unable to publish Metsaregister forestry generation", exception);
        }
    }

    private boolean hasActiveRefresh() {
        return dsl.fetchOne("""
            SELECT 1 AS present
              FROM metsaregister_wfs_generation generation
              JOIN metsaregister_wfs_layer layer ON layer.layer_name = generation.layer_name
             WHERE generation.state = 'STAGING'
               AND layer.consumption_mode = 'LOCAL_DOMAIN_DATA'
               AND layer.approved = TRUE
               AND layer.enabled = TRUE
             LIMIT 1
            """) != null;
    }
}
