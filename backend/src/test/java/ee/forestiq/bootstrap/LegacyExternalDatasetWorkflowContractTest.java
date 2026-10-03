package ee.forestiq.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LegacyExternalDatasetWorkflowContractTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void normalRuntimeDoesNotExposeExternalDatasetCurrentWriters() throws Exception {
        String controller = Files.readString(ROOT.resolve(
            "backend/src/main/java/ee/forestiq/bootstrap/CadastreCsvImportController.java"));
        String runner = Files.readString(ROOT.resolve(
            "backend/src/main/java/ee/forestiq/bootstrap/ExternalDataBootstrapRunner.java"));
        BootstrapProperties properties = new BootstrapProperties();

        assertThat(properties.isLegacyPublicDatasetsEnabled()).isFalse();
        assertThat(controller).contains(
            "@ConditionalOnProperty(name = \"forestiq.bootstrap.legacy-public-datasets-enabled\", havingValue = \"true\")");
        assertThat(runner).contains("if (properties.isLegacyPublicDatasetsEnabled())");
        assertThat(runner).contains("publicDatasets.synchronizeAll()");
        assertThat(runner).contains("publicDatasets.projectToTenantTables()");
    }
}
