plugins {
    java
    jacoco
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.owasp.dependencycheck") version "12.2.2"
}

group = "ee.forestiq"
version = "2.0.0"

description = "ForestIQ CRM and geospatial API"

// Spring Boot 3.5.16 brings the application onto the patched Spring Framework,
// Spring Security, Tomcat, Jackson and Micrometer lines. These three overrides
// are intentionally one patch line ahead of the 3.5.16 BOM because the current
// image vulnerability database requires fixes newer than the BOM defaults.
extra["postgresql.version"] = "42.7.12"
extra["rabbit-amqp-client.version"] = "5.33.1"
extra["netty.version"] = "4.1.136.Final"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val jooqCodegenSourceSet = sourceSets.create("jooqCodegen") {
    java.srcDir("src/jooqCodegen/java")
}

repositories {
    mavenCentral()
}

dependencies {
    // Generate META-INF/spring.components so classpath component discovery can
    // use an index instead of scanning every class in the application package.
    annotationProcessor("org.springframework:spring-context-indexer")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-amqp")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    implementation("org.springframework.boot:spring-boot-starter-jooq")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.hibernate.orm:hibernate-spatial")
    implementation("org.locationtech.jts:jts-core:1.20.0")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("com.github.ben-manes.caffeine:caffeine")

    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.sentry:sentry-spring-boot-starter-jakarta:8.58.0")
    implementation("software.amazon.awssdk:s3:2.55.8")
    implementation("com.openhtmltopdf:openhtmltopdf-pdfbox:1.0.10")

    // ForestIQ has a PostgreSQL-specific bulk ingestion adapter that uses the
    // driver's COPY API directly; keep the driver available at compile time.
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("io.rest-assured:rest-assured:5.5.0")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
    testRuntimeOnly("com.h2database:h2")

    "jooqCodegenImplementation"("org.jooq:jooq-codegen")
    "jooqCodegenImplementation"("org.jooq:jooq-meta")
    "jooqCodegenImplementation"("org.flywaydb:flyway-core")
    "jooqCodegenImplementation"("org.flywaydb:flyway-database-postgresql")
    "jooqCodegenRuntimeOnly"("org.postgresql:postgresql")
}

sourceSets.named("main") {
    java.srcDir("src/generated/jooq")
}

val backendIntegrationTestPatterns = listOf(
    "**/*IntegrationTest.class",
    "**/*Postgis*Test.class",
    "**/FlywayPostgisApplicationContextTest.class"
)

val integrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs backend integration tests that require PostGIS, Testcontainers or application startup."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    shouldRunAfter(tasks.test)
    include(backendIntegrationTestPatterns)
}

tasks.named<Test>("test") {
    exclude(backendIntegrationTestPatterns)
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    doLast {
        val reportFile = reports.xml.outputLocation.get().asFile
        if (!reportFile.isFile) {
            logger.warn("JaCoCo XML report was not generated: {}", reportFile)
            return@doLast
        }

        val documentFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val document = documentFactory.newDocumentBuilder().parse(reportFile)
        val xpath = javax.xml.xpath.XPathFactory.newInstance().newXPath()

        fun directCounter(element: org.w3c.dom.Element, type: String): Pair<Long, Long>? {
            val children = element.childNodes
            for (index in 0 until children.length) {
                val child = children.item(index)
                if (child is org.w3c.dom.Element && child.tagName == "counter" && child.getAttribute("type") == type) {
                    return child.getAttribute("missed").toLong() to child.getAttribute("covered").toLong()
                }
            }
            return null
        }

        fun percentage(missed: Long, covered: Long): Double {
            val total = missed + covered
            return if (total == 0L) 100.0 else covered * 100.0 / total
        }

        val report = document.documentElement
        val globalLine = directCounter(report, "LINE") ?: (0L to 0L)
        val globalBranch = directCounter(report, "BRANCH") ?: (0L to 0L)

        val criticalPrefixes = listOf(
            "ee/forestiq/security",
            "ee/forestiq/owner",
            "ee/forestiq/inheritance",
            "ee/forestiq/deal",
            "ee/forestiq/registry",
            "ee/forestiq/portfolio",
            "ee/forestiq/ownership",
            "ee/forestiq/integration"
        )
        val packages = xpath.evaluate("/report/package", document, javax.xml.xpath.XPathConstants.NODESET) as org.w3c.dom.NodeList
        var criticalLineMissed = 0L
        var criticalLineCovered = 0L
        var criticalBranchMissed = 0L
        var criticalBranchCovered = 0L
        for (index in 0 until packages.length) {
            val pkg = packages.item(index) as org.w3c.dom.Element
            if (criticalPrefixes.none { pkg.getAttribute("name").startsWith(it) }) continue
            directCounter(pkg, "LINE")?.let { (missed, covered) ->
                criticalLineMissed += missed
                criticalLineCovered += covered
            }
            directCounter(pkg, "BRANCH")?.let { (missed, covered) ->
                criticalBranchMissed += missed
                criticalBranchCovered += covered
            }
        }

        logger.lifecycle(
            "JaCoCo measured coverage: global line={}%, branch={}%; critical line={}%, branch={}%",
            "%.2f".format(percentage(globalLine.first, globalLine.second)),
            "%.2f".format(percentage(globalBranch.first, globalBranch.second)),
            "%.2f".format(percentage(criticalLineMissed, criticalLineCovered)),
            "%.2f".format(percentage(criticalBranchMissed, criticalBranchCovered))
        )
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.30".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.18".toBigDecimal()
            }
        }
        rule {
            element = "PACKAGE"
            includes = listOf(
                "ee.forestiq.security*",
                "ee.forestiq.owner*",
                "ee.forestiq.inheritance*",
                "ee.forestiq.deal*",
                "ee.forestiq.registry*",
                "ee.forestiq.portfolio*",
                "ee.forestiq.ownership*",
                "ee.forestiq.integration*"
            )
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.35".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.20".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification, integrationTest)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-parameters")
}

tasks.register<JavaExec>("generateJooq") {
    group = "jOOQ"
    description = "Generate jOOQ classes from a PostGIS database migrated with Flyway."
    dependsOn(tasks.named(jooqCodegenSourceSet.classesTaskName))
    classpath = jooqCodegenSourceSet.runtimeClasspath
    mainClass = "ee.forestiq.codegen.JooqCodegen"
    args(
        layout.projectDirectory.dir("src/main/resources/db/migration").asFile.absolutePath,
        layout.projectDirectory.dir("src/generated/jooq").asFile.absolutePath
    )
}

tasks.register<JavaExec>("downloadCadastreSnapshot") {
    group = "geospatial"
    description = "Download and verify the current Maa- ja Ruumiamet nationwide cadastral GPKG snapshot."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ee.forestiq.geospatial.cadastre.CadastreSnapshotDownloadCli"
}


dependencyCheck {
    failBuildOnCVSS = 7.0f
    failOnError = true
    formats = listOf("HTML", "SARIF")
}


tasks.register<JavaExec>("migrateDatabase") {
    group = "database"
    description = "Applies and validates ForestIQ Flyway migrations using dedicated migration credentials."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ee.forestiq.db.FlywayMigrationCli"
}

tasks.register<JavaExec>("verifyProductionDatabaseRoles") {
    group = "database"
    description = "Verifies production API/ingest/Keycloak runtime role separation."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ee.forestiq.db.ProductionDatabaseRoleVerifierCli"
}


tasks.register<JavaExec>("verifyProductionRuntimeHeartbeats") {
    group = "database"
    description = "Verifies recent production Python scheduler and worker heartbeats."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "ee.forestiq.db.ProductionRuntimeHeartbeatCli"
}
