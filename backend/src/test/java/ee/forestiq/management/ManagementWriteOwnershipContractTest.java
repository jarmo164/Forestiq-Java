package ee.forestiq.management;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;

class ManagementWriteOwnershipContractTest {

    @Test
    void segmentAssignmentUsesReadModelAndDoesNotWriteForeignOwnerState() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/ee/forestiq/management/SegmentAssignmentService.java"));
        String normalized = source.toLowerCase(Locale.ROOT);

        assertThat(source).contains("ManagementReadModelPort");
        assertThat(source).contains("OwnerCollaborationPort");
        assertThat(source).contains("OrganizationIdentityDirectory");
        assertThat(normalized).doesNotContain("update owner ");
        assertThat(normalized).doesNotContain("insert into owner_activity");
        assertThat(source).doesNotContain("KeycloakAdminService");
        assertThat(source).doesNotContain("AdminController.");
    }

    @Test
    void crossDomainReadSqlLivesInReadModelAdapterAndIsSelectOnly() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/ee/forestiq/management/readmodel/JooqManagementReadModelAdapter.java"));
        String normalized = source.toLowerCase(Locale.ROOT);

        assertThat(normalized).contains("from owner");
        assertThat(normalized).contains("from reminder");
        assertThat(normalized).doesNotContain(" update ");
        assertThat(normalized).doesNotContain(" insert ");
        assertThat(normalized).doesNotContain(" delete ");
    }

    @Test
    void dataQualityOwnsOnlyItsIssueAndScanWrites() throws Exception {
        String source = Files.readString(Path.of(
            "src/main/java/ee/forestiq/management/DataQualityService.java"));
        String normalized = source.toLowerCase(Locale.ROOT);

        assertThat(source).contains("OrganizationIdentityDirectory");
        assertThat(source).doesNotContain("KeycloakAdminService");
        assertThat(source).doesNotContain("AdminController.");
        assertThat(normalized).doesNotContain("update owner ");
        assertThat(normalized).doesNotContain("insert into owner_activity");
        assertThat(normalized).doesNotContain("update deal_case ");
    }
}
