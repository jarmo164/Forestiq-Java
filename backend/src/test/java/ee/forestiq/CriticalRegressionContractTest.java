package ee.forestiq;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Keeps the highest-risk negative authorization and integration recovery
 * regressions explicit. Normal test execution still runs the real tests; this
 * contract makes CI fail if one of the pinned regression methods is removed or
 * accidentally renamed without replacing it in the critical set.
 */
class CriticalRegressionContractTest {

    private static final List<RegressionTest> REQUIRED = List.of(
        new RegressionTest("ee.forestiq.owner.OwnerAuthorizationServiceTest",
            "callerCannotReadOwnerAssignedToAnotherSubject"),
        new RegressionTest("ee.forestiq.owner.OwnerAuthorizationServiceTest",
            "callerCannotReadCoOwnedParcelWhenNoActiveOwnerIsAssignedToCaller"),
        new RegressionTest("ee.forestiq.owner.OwnerStatusDefinitionCacheTest",
            "organizationIdIsPartOfCacheKey"),
        new RegressionTest("ee.forestiq.cadastre.MapTileRepositoryIsolationTest",
            "callerScopedParcelSummaryDoesNotExposeOtherAssignedOwners"),
        new RegressionTest("ee.forestiq.deal.DealQueryRepositoryIsolationTest",
            "detailReadCannotAttachChildRowsFromAnotherOrganization"),
        new RegressionTest("ee.forestiq.messaging.MessageServiceTest",
            "sameSubjectCannotReadCountOrNoticeMessagesFromAnotherOrganization"),
        new RegressionTest("ee.forestiq.inheritance.InheritanceServiceTest",
            "ownerFromAnotherOrganizationCannotStartInheritanceCase"),
        new RegressionTest("ee.forestiq.contract.ContractRepositoryIsolationTest",
            "contractFileAndObjectKeyCannotCrossOrganizationBoundary"),
        new RegressionTest("ee.forestiq.integration.IntegrationJobTenantIsolationTest",
            "claimedWorkerRunUsesDurableOrganizationInsteadOfAmbientRequestContext"),
        new RegressionTest("ee.forestiq.admin.AdminUserAssignmentGuardTest",
            "activeAssignmentsBlockDeletionAndExposeImpactCounts"),
        new RegressionTest("ee.forestiq.identity.KeycloakIdentityAdapterTest",
            "returnsUsersAndRoleMappingsBeyondFirstKeycloakPage"),
        new RegressionTest("ee.forestiq.ownership.OwnershipTransitionServiceTest",
            "providerFailurePersistsFailureMetadataWithoutAdvancingSuccessCursor"),
        new RegressionTest("ee.forestiq.portfolio.MetsisPortfolioSyncServiceTest",
            "providerFailurePersistsFailureMetadataWithoutAdvancingSuccessCursor"),
        new RegressionTest("ee.forestiq.portfolio.MetsisPortfolioSyncServiceTest",
            "pageCapPreservesLastKnownGoodRelationAndDoesNotAdvanceCursor"),
        new RegressionTest("ee.forestiq.registry.RegistryBulkRecoveryServiceTest",
            "pageCapKeepsLiveSnapshotAndNextRunResumesThenAppliesWholeBatch"),
        new RegressionTest("ee.forestiq.registry.MetsaregisterWfsClientTest",
            "stopsWhenConfiguredPageSafetyLimitIsReached")
    );

    @Test
    void criticalRegressionTestsRemainPresentAndExecutableByJunit() throws Exception {
        for (RegressionTest regression : REQUIRED) {
            Class<?> testClass = Class.forName(regression.className());
            Method method = testClass.getDeclaredMethod(regression.methodName());
            assertThat(method.getAnnotation(Test.class))
                .as(regression.className() + "#" + regression.methodName() + " must remain a JUnit test")
                .isNotNull();
        }
    }

    private record RegressionTest(String className, String methodName) { }
}
