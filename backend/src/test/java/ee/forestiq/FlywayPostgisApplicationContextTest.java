package ee.forestiq;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.cache.type=simple",
        "spring.quartz.auto-startup=false",
        "forestiq.cors.allowed-origins=http://localhost:3000",
        "forestiq.scheduling.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("core")
class FlywayPostgisApplicationContextTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName.parse("postgis/postgis:17-3.5")
        .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
        .withDatabaseName("forestiq")
        .withUsername("forestiq")
        .withPassword("forestiq");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @MockBean private JwtDecoder jwtDecoder;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void applicationContextAppliesFlywayMigrationsToPostgisSchema() {
        UUID demoOrg = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID demoOwner = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String postgisVersion = jdbcTemplate.queryForObject("select postgis_version()", String.class);
        Integer flywayMigrations = jdbcTemplate.queryForObject("select count(*) from flyway_schema_history where success", Integer.class);
        Integer defaultStatuses = jdbcTemplate.queryForObject("select count(*) from owner_status_definition where organization_id = ? and is_protected = true", Integer.class, demoOrg);
        Boolean tenantOwnerForeignKeyPresent = jdbcTemplate.queryForObject("select exists (select 1 from information_schema.table_constraints where constraint_name = 'reminder_organization_owner_fk' and table_name = 'reminder')", Boolean.class);
        Boolean postgisGeometryWorks = jdbcTemplate.queryForObject("select ST_Area(ST_GeomFromText('POLYGON((0 0, 1 0, 1 1, 0 0))', 4326)::geography) > 0", Boolean.class);
        Integer workflowOutcomes = jdbcTemplate.queryForObject("select count(*) from contact_outcome_definition where organization_id = ? and active", Integer.class, demoOrg);
        Boolean reminderEventTablePresent = table("reminder_event");
        Boolean notificationDeliveryTablePresent = table("reminder_notification_delivery");
        Boolean evidenceTablePresent = table("evaluation_evidence_snapshot");
        Boolean workbasketMemberTablePresent = table("saved_workbasket_parcel");
        Boolean handoffTablePresent = table("saved_workbasket_share");
        Boolean lossOutcomeTablePresent = table("deal_loss_outcome");
        Boolean contractVersionTablePresent = table("contract_version_audit");
        Boolean signingStateTablePresent = table("contract_signing_state");
        Boolean signingEventTablePresent = table("contract_signing_event");
        Boolean stageProbabilityTablePresent = table("stage_probability_config");
        Boolean targetSegmentTablePresent = table("sales_target_segment");
        Boolean assignmentAuditTablePresent = table("management_assignment_run");
        Boolean dataQualityIssueTablePresent = table("data_quality_issue");
        Boolean dataQualityEventTablePresent = table("data_quality_issue_event");
        Boolean dataQualityScanTablePresent = table("data_quality_scan_run");
        Boolean evidenceAttachmentTriggerPresent = trigger("trg_deal_evaluation_attach_evidence");
        Boolean contractVersionImmutableTriggerPresent = trigger("trg_contract_version_audit_immutable");
        Boolean contractSigningAutoTriggerPresent = trigger("trg_contract_version_create_signing_state");
        Boolean signingEventImmutableTriggerPresent = trigger("trg_contract_signing_event_immutable");
        Boolean assignmentRunImmutableTriggerPresent = trigger("trg_management_assignment_run_immutable");
        Boolean dataQualityAuditTriggerPresent = trigger("trg_data_quality_issue_audit");
        Boolean dataQualityImmutableEventTriggerPresent = trigger("trg_data_quality_issue_event_immutable");
        Integer defaultLossReasons = jdbcTemplate.queryForObject("select count(*) from deal_loss_reason_definition where organization_id = ? and active", Integer.class, demoOrg);
        Integer defaultStageProbabilities = jdbcTemplate.queryForObject("select count(*) from stage_probability_config where organization_id = ? and valid_to is null", Integer.class, demoOrg);

        jdbcTemplate.update("""
            insert into owner (id, organization_id, display_name, personal_or_registry_code, status)
            values (?, ?, 'Flyway compatibility owner', '39001010003', 'NEW')
            on conflict do nothing
            """, demoOwner, demoOrg);

        Long legacyReminderId = jdbcTemplate.queryForObject("""
            insert into reminder (organization_id, creator, owner_id, reminder_text, due_time)
            values (?, 'legacy-test-user', ?, 'Legacy compatibility test', now() + interval '1 day') returning id
            """, Long.class, demoOrg, demoOwner);
        String defaultAssignee = jdbcTemplate.queryForObject("select assignee_subject from reminder where id = ?", String.class, legacyReminderId);

        UUID phoneActivityId = jdbcTemplate.queryForObject("""
            insert into owner_activity
                (organization_id, owner_id, activity_type, content, created_by_subject, source_type, contact_channel, outcome_code)
            values (?, ?, 'PHONE', 'Migration contract contact', 'workflow-test-user', 'USER', 'PHONE', 'CONTACTED') returning id
            """, UUID.class, demoOrg, demoOwner);

        jdbcTemplate.update("""
            insert into user_notification_preference
                (organization_id, user_subject, lead_minutes, in_app_enabled, email_enabled, email_address)
            values (?, 'notification-test-user', 30, true, true, 'notify@example.test')
            """, demoOrg);
        Integer preferenceCount = jdbcTemplate.queryForObject("select count(*) from user_notification_preference where organization_id = ? and user_subject = 'notification-test-user'", Integer.class, demoOrg);

        UUID qualityIssue = jdbcTemplate.queryForObject("""
            insert into data_quality_issue
                (organization_id, owner_id, issue_type, source_name, source_key, summary, impact, recommended_subject, updated_by_subject)
            values (?, ?, 'MISSING_CONTACT', 'test-rule', 'test-owner-signal', 'Test signal', 'HIGH', 'quality-user', 'quality-test')
            returning id
            """, UUID.class, demoOrg, demoOwner);
        jdbcTemplate.update("""
            update data_quality_issue set status = 'ASSIGNED', assigned_subject = 'quality-user', action_reason = 'Töö määrati', updated_by_subject = 'quality-manager'
            where id = ?
            """, qualityIssue);
        jdbcTemplate.update("""
            update data_quality_issue set status = 'RESOLVED', resolved_at = now(), action_reason = 'Andmed parandatud', updated_by_subject = 'quality-user'
            where id = ?
            """, qualityIssue);
        jdbcTemplate.update("""
            update data_quality_issue set status = 'OPEN', resolved_at = null, action_reason = 'Reegel tuvastas rikkumise uuesti', updated_by_subject = 'system-data-quality'
            where id = ?
            """, qualityIssue);
        List<String> qualityEvents = jdbcTemplate.queryForList("""
            select event_type from data_quality_issue_event where issue_id = ? order by created_at, id
            """, String.class, qualityIssue);

        assertThat(postgisVersion).isNotBlank();
        assertThat(flywayMigrations).isGreaterThanOrEqualTo(50);
        assertThat(defaultStatuses).isGreaterThanOrEqualTo(8);
        assertThat(tenantOwnerForeignKeyPresent).isTrue();
        assertThat(postgisGeometryWorks).isTrue();
        assertThat(workflowOutcomes).isGreaterThanOrEqualTo(5);
        assertThat(reminderEventTablePresent).isTrue();
        assertThat(notificationDeliveryTablePresent).isTrue();
        assertThat(evidenceTablePresent).isTrue();
        assertThat(workbasketMemberTablePresent).isTrue();
        assertThat(handoffTablePresent).isTrue();
        assertThat(lossOutcomeTablePresent).isTrue();
        assertThat(contractVersionTablePresent).isTrue();
        assertThat(signingStateTablePresent).isTrue();
        assertThat(signingEventTablePresent).isTrue();
        assertThat(stageProbabilityTablePresent).isTrue();
        assertThat(targetSegmentTablePresent).isTrue();
        assertThat(assignmentAuditTablePresent).isTrue();
        assertThat(dataQualityIssueTablePresent).isTrue();
        assertThat(dataQualityEventTablePresent).isTrue();
        assertThat(dataQualityScanTablePresent).isTrue();
        assertThat(evidenceAttachmentTriggerPresent).isTrue();
        assertThat(contractVersionImmutableTriggerPresent).isTrue();
        assertThat(contractSigningAutoTriggerPresent).isTrue();
        assertThat(signingEventImmutableTriggerPresent).isTrue();
        assertThat(assignmentRunImmutableTriggerPresent).isTrue();
        assertThat(dataQualityAuditTriggerPresent).isTrue();
        assertThat(dataQualityImmutableEventTriggerPresent).isTrue();
        assertThat(defaultLossReasons).isGreaterThanOrEqualTo(7);
        assertThat(defaultStageProbabilities).isGreaterThanOrEqualTo(10);
        assertThat(defaultAssignee).isEqualTo("legacy-test-user");
        assertThat(phoneActivityId).isNotNull();
        assertThat(preferenceCount).isEqualTo(1);
        assertThat(qualityEvents).containsExactly("DETECTED", "ASSIGNED", "RESOLVED", "REOPENED");
    }

    private Boolean table(String name) {
        return jdbcTemplate.queryForObject("select to_regclass('public.' || ?) is not null", Boolean.class, name);
    }

    private Boolean trigger(String name) {
        return jdbcTemplate.queryForObject("select exists (select 1 from pg_trigger where tgname = ? and not tgisinternal)", Boolean.class, name);
    }
}
