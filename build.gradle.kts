import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask

plugins {
    id("dev.sort.sql-dialect-plugin")
}

group = "dev.sort.clickhouse"
version = "0.1.0"

sqlDialectPlugin {
    legalFiles = listOf("LICENSE", "THIRD_PARTY_NOTICES.md")
    embeddedLibraries = with(libs.versions) {
        setOf(
            "brikk-sql-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-sql-metadata-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-sql-intellij-support-${brikk.sql.intellij.support.get()}.jar",
            "brikk-sql-verify-api-${brikk.sql.core.get()}.jar",
            "brikk-sql-verify-clickhouse-${brikk.sql.core.get()}.jar",
            "brikk-engine-kotlin-intellij-support-${brikk.engine.kotlin.intellij.support.get()}.jar",
        )
    }
    requiredNotices = listOf("brikk-sql-intellij-support", "brikk-sql-metadata", "Apache License 2.0")
}

dependencies {
    implementation(libs.brikk.sql.jvm)
    implementation(libs.brikk.sql.metadata.jvm)
    implementation(libs.brikk.sql.intellij.support)
    implementation(libs.brikk.engine.kotlin.intellij.support)
    implementation("dev.brikk.house:brikk-sql-verify-clickhouse:${libs.versions.brikk.sql.core.get()}")
    testImplementation(libs.brikk.sql.intellij.test.support)
}

val clickHouseTestDriver = configurations.create("clickHouseTestDriver") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    add(clickHouseTestDriver.name, "com.clickhouse:clickhouse-jdbc:0.9.8:all")
    add(clickHouseTestDriver.name, "org.slf4j:slf4j-api:2.0.7")
}
tasks.register<Copy>("fetchClickHouseTestDriver") {
    group = "verification"
    description = "Fetches JDBC jars for the opt-in ClickHouse server fixture, never the plugin distribution."
    from(clickHouseTestDriver)
    into(layout.buildDirectory.dir("clickhouse-driver"))
}

val dependencyProvenance = layout.projectDirectory.file("dependency-provenance.properties")
val provenance = providers.fileContents(dependencyProvenance).asText.map { text ->
    val properties = Properties().apply { load(text.reader()) }
    properties.stringPropertyNames().filter { it.endsWith(".jar") }.associateWith(properties::getProperty)
}
fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

val verifyDependencyProvenance = tasks.register("verifyDependencyProvenance") {
    group = "verification"
    description = "Rejects shared snapshot bytes not recorded in the commit's dependency provenance."
    val jars = configurations.named("testRuntimeClasspath")
    inputs.file(dependencyProvenance)
    inputs.files(jars)
    doLast {
        val actual = jars.get().files.filter { it.name.startsWith("brikk-") && it.extension == "jar" }
            .associate { it.name to sha256(it.readBytes()) }
        check(actual == provenance.get()) { "Shared dependency payload differs from dependency-provenance.properties: $actual" }
    }
}
tasks.named<ProcessResources>("processResources") {
    from(dependencyProvenance) { into("META-INF") }
}
tasks.named<BuildPluginTask>("buildPlugin") {
    dependsOn(verifyDependencyProvenance)
    inputs.file(dependencyProvenance)
    doLast {
        ZipFile(archiveFile.get().asFile).use { zip ->
            val embedded = zip.entries().asSequence().filter { it.name.substringAfterLast('/') in provenance.get() }
                .associate { entry -> entry.name.substringAfterLast('/') to sha256(zip.getInputStream(entry).use { it.readBytes() }) }
            val expected = provenance.get().filterKeys { !it.startsWith("brikk-sql-intellij-test-support-") }
            check(embedded == expected) { "ZIP payload does not match the recorded shared dependencies: $embedded" }
        }
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(verifyDependencyProvenance)
    inputs.file(dependencyProvenance)
    val realSqlDirectory = providers.gradleProperty("realSql.dir").orNull
    if (realSqlDirectory == null) {
        exclude("**/RealSqlScanTest.class")
    } else {
        include("**/RealSqlScanTest.class")
        systemProperty("realSql.dir", file(realSqlDirectory).absolutePath)
        providers.gradleProperty("realSql.probes").orNull?.let { systemProperty("realSql.probes", file(it).absolutePath) }
        val privateDirectory = layout.buildDirectory.dir("realsql").get().asFile
        systemProperty("realSql.output", privateDirectory.absolutePath)
        systemProperty("java.io.tmpdir", privateDirectory.resolve("tmp").absolutePath)
        systemProperty("idea.log.path", privateDirectory.resolve("ide-log").absolutePath)
        systemProperty("idea.system.path", privateDirectory.resolve("ide-system").absolutePath)
        systemProperty("idea.config.path", privateDirectory.resolve("ide-config").absolutePath)
        binaryResultsDirectory.set(privateDirectory.resolve("binary"))
        reports.junitXml.outputLocation.set(privateDirectory.resolve("xml"))
        reports.html.outputLocation.set(privateDirectory.resolve("html"))
        doFirst { privateDirectory.resolve("tmp").mkdirs() }
    }
    if (providers.environmentVariable("BRIKK_CLICKHOUSE_URL").orNull.isNullOrBlank()) {
        exclude("**/ClickHouseServerVerificationTest.class")
    }
    systemProperty("clickhouse.driver.fixture", layout.buildDirectory.file("clickhouse-driver/clickhouse-jdbc-0.9.8-all.jar").get().asFile.absolutePath)
    systemProperty("clickhouse.catalog.fixture", layout.buildDirectory.dir("catalog-tests").get().asFile.absolutePath)
    for (name in listOf("BRIKK_CLICKHOUSE_URL", "BRIKK_CLICKHOUSE_DRIVER", "BRIKK_CLICKHOUSE_CONTAINER")) {
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
