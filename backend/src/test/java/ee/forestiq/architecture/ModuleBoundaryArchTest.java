package ee.forestiq.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import java.util.Set;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

class ModuleBoundaryArchTest {
    private static final Set<String> DOMAIN_PACKAGES = Set.of(
        "admin",
        "cadastre",
        "contract",
        "deal",
        "decision",
        "geospatial",
        "inheritance",
        "integration",
        "management",
        "notification",
        "owner",
        "ownership",
        "phonebook",
        "portfolio",
        "registry",
        "reminder",
        "sales",
        "security",
        "workflow"
    );

    private final JavaClasses classes = new ClassFileImporter()
        .withImportOption(new ImportOption.DoNotIncludeTests())
        .importPackages("ee.forestiq");

    @Test
    void repositoriesMustNotDependOnControllers() {
        noClasses()
            .that().haveSimpleNameEndingWith("Repository")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
            .because("repositories are persistence adapters and must not know HTTP controllers")
            .check(classes);
    }

    @Test
    void controllersMustNotUseRepositoriesFromOtherDomainsDirectly() {
        noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should(dependOnForeignDomainRepository())
            .because("controllers should go through their own application service boundary")
            .check(classes);
    }

    @Test
    void ingestionAdaptersMustNotDependOnUiOrKeycloakAdmin() {
        noClasses()
            .that().resideInAnyPackage(
                "ee.forestiq.registry..",
                "ee.forestiq.geospatial..",
                "ee.forestiq.inheritance..",
                "ee.forestiq.ownership..",
                "ee.forestiq.portfolio..")
            .and().areNotAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("ee.forestiq.identity.KeycloakIdentityAdapter")
            .because("provider ingestion adapters run behind the durable integration boundary")
            .check(classes);
    }

    @Test
    void workflowContractAndManagementMustUseIdentityPortInsteadOfAdminOrKeycloakAdapter() {
        noClasses()
            .that().resideInAnyPackage(
                "ee.forestiq.workflow..",
                "ee.forestiq.contract..",
                "ee.forestiq.management..")
            .should().dependOnClassesThat().resideInAnyPackage("ee.forestiq.admin..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("ee.forestiq.identity.KeycloakIdentityAdapter")
            .because("application modules use the typed identity directory, never admin HTTP DTOs or Keycloak transport")
            .check(classes);
    }

    @Test
    void dealWorkflowAndManagementMustUseOwnerCollaborationPort() {
        noClasses()
            .that().resideInAnyPackage(
                "ee.forestiq.deal..",
                "ee.forestiq.workflow..",
                "ee.forestiq.management..")
            .should(dependOnOwnerImplementationOutsideCollaborationPort())
            .because("owner state, assignment and timeline writes belong to the owner module behind OwnerCollaborationPort")
            .check(classes);
    }

    @Test
    void managementApplicationServicesMustUseThePublishedReadAndCommandBoundaries() {
        noClasses()
            .that().resideInAnyPackage("ee.forestiq.management..")
            .and().haveSimpleNameEndingWith("Service")
            .should().dependOnClassesThat().resideInAnyPackage(
                "ee.forestiq.admin..",
                "ee.forestiq.owner.persistence..",
                "ee.forestiq.owner.repository..")
            .orShould().dependOnClassesThat().haveFullyQualifiedName("ee.forestiq.identity.KeycloakIdentityAdapter")
            .because("management uses identity, owner-command and purpose-built management read-model boundaries")
            .check(classes);
    }

    @Test
    void workflowMustUsePublishedIdentityOwnerDealAndReminderApis() {
        noClasses()
            .that().resideInAnyPackage("ee.forestiq.workflow..")
            .should(dependOnWorkflowForeignImplementation())
            .because("workflow orchestration must use typed identity, owner, deal and work-item/reminder APIs")
            .check(classes);
    }

    @Test
    void workflowServiceMustNotDependOnJooq() {
        noClasses()
            .that().haveFullyQualifiedName("ee.forestiq.workflow.WorkflowService")
            .should().dependOnClassesThat().resideInAnyPackage("org.jooq..")
            .because("WorkflowService orchestrates use cases and must not contain persistence SQL")
            .check(classes);
    }

    @Test
    void contractMustUseTheWonDealSourcePortInsteadOfDealImplementation() {
        noClasses()
            .that().resideInAnyPackage("ee.forestiq.contract..")
            .should(dependOnDealImplementationOutsideContractSourcePort())
            .because("contract reads a WON deal only through the immutable deal-owned source port")
            .check(classes);
    }

    private static ArchCondition<JavaClass> dependOnForeignDomainRepository() {
        return new ArchCondition<>("depend on another domain repository") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                String sourceDomain = domain(item);
                if (sourceDomain == null) return;

                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    JavaClass target = access.getTargetOwner();
                    if (!target.getSimpleName().endsWith("Repository")) continue;

                    String targetDomain = domain(target);
                    if (targetDomain == null || sourceDomain.equals(targetDomain)) continue;

                    events.add(SimpleConditionEvent.violated(item,
                        item.getName() + " directly depends on foreign repository " + target.getName()));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> dependOnOwnerImplementationOutsideCollaborationPort() {
        return new ArchCondition<>("depend on owner implementation outside collaboration port") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    JavaClass target = access.getTargetOwner();
                    String packageName = target.getPackageName();
                    if (!packageName.startsWith("ee.forestiq.owner")) continue;
                    if (packageName.startsWith("ee.forestiq.owner.collaboration")) continue;

                    events.add(SimpleConditionEvent.violated(item,
                        item.getName() + " directly depends on owner implementation " + target.getName()));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> dependOnDealImplementationOutsideContractSourcePort() {
        return new ArchCondition<>("depend on deal implementation outside contract source port") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    JavaClass target = access.getTargetOwner();
                    String packageName = target.getPackageName();
                    if (!packageName.startsWith("ee.forestiq.deal")) continue;
                    if (packageName.startsWith("ee.forestiq.deal.contractsource")) continue;

                    events.add(SimpleConditionEvent.violated(item,
                        item.getName() + " directly depends on deal implementation " + target.getName()));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> dependOnWorkflowForeignImplementation() {
        Set<String> identityApi = Set.of(
            "ee.forestiq.identity.OrganizationIdentityDirectory",
            "ee.forestiq.identity.OrganizationRole",
            "ee.forestiq.identity.OrganizationUser");
        return new ArchCondition<>("depend on a foreign module outside its published workflow API") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    JavaClass target = access.getTargetOwner();
                    String packageName = target.getPackageName();
                    boolean forbidden = false;
                    if (packageName.startsWith("ee.forestiq.identity")) {
                        forbidden = !identityApi.contains(target.getName());
                    } else if (packageName.startsWith("ee.forestiq.owner")) {
                        forbidden = !packageName.startsWith("ee.forestiq.owner.collaboration");
                    } else if (packageName.startsWith("ee.forestiq.deal")) {
                        forbidden = !packageName.startsWith("ee.forestiq.deal.workflow");
                    } else if (packageName.startsWith("ee.forestiq.reminder")) {
                        forbidden = !packageName.startsWith("ee.forestiq.reminder.workflow");
                    }
                    if (forbidden) {
                        events.add(SimpleConditionEvent.violated(item,
                            item.getName() + " directly depends on non-public workflow collaborator " + target.getName()));
                    }
                }
            }
        };
    }

    private static String domain(JavaClass javaClass) {
        String packageName = javaClass.getPackageName();
        if (!packageName.startsWith("ee.forestiq.")) return null;

        String remainder = packageName.substring("ee.forestiq.".length());
        int separator = remainder.indexOf('.');
        String candidate = separator < 0 ? remainder : remainder.substring(0, separator);
        return DOMAIN_PACKAGES.contains(candidate) ? candidate : null;
    }
}
