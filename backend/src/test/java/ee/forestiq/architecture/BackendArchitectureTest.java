package ee.forestiq.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import ee.forestiq.integration.ExternalSystemBoundary;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "ee.forestiq", importOptions = ImportOption.DoNotIncludeTests.class)
class BackendArchitectureTest {

    @ArchTest
    static final ArchRule controllers_do_not_own_transactions = classes()
        .that().areAnnotatedWith(RestController.class)
        .should().notBeAnnotatedWith(Transactional.class)
        .because("application services own use-case transactions");

    @ArchTest
    static final ArchRule persistence_does_not_depend_on_http_controllers = noClasses()
        .that().haveSimpleNameEndingWith("Repository")
        .should().dependOnClassesThat().areAnnotatedWith(RestController.class)
        .because("persistence adapters must remain callable without the HTTP adapter");

    @ArchTest
    static final ArchRule deal_does_not_reach_into_unrelated_domains = noClasses()
        .that().resideInAPackage("..deal..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..inheritance..", "..registry..", "..portfolio..", "..ownership..");

    @ArchTest
    static final ArchRule inheritance_does_not_reach_into_unrelated_domains = noClasses()
        .that().resideInAPackage("..inheritance..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..deal..", "..registry..", "..portfolio..", "..ownership..");

    @ArchTest
    static final ArchRule registry_does_not_reach_into_commercial_workflows = noClasses()
        .that().resideInAPackage("..registry..")
        .should().dependOnClassesThat().resideInAnyPackage("..deal..", "..inheritance..");

    @ArchTest
    static final ArchRule integration_runner_does_not_depend_on_business_workflows = noClasses()
        .that().resideInAPackage("..integration..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "..deal..", "..inheritance..", "..registry..", "..portfolio..", "..ownership..");

    @ArchTest
    static final ArchRule external_http_boundaries_are_explicitly_classified = noClasses()
        .that().resideInAPackage("..forestiq..")
        .and().areNotAnnotatedWith(ExternalSystemBoundary.class)
        .should().dependOnClassesThat().resideInAnyPackage(
            "org.springframework.web.client..",
            "org.springframework.web.reactive.function.client..",
            "org.springframework.http.client..",
            "java.net.http..")
        .because("external HTTP boundaries must be classified; scheduled batch ETL belongs in the Python ingest runtime");
}
