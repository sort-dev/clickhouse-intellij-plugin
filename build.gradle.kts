plugins {
    id("dev.sort.sql-dialect-plugin")
}

group = "dev.sort.clickhouse"
version = "0.1.0-SNAPSHOT"

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

tasks.withType<Test>().configureEach {
    if (providers.environmentVariable("BRIKK_CLICKHOUSE_URL").orNull.isNullOrBlank()) {
        exclude("**/ClickHouseServerVerificationTest.class")
    }
    systemProperty("clickhouse.driver.fixture", layout.buildDirectory.file("clickhouse-driver/clickhouse-jdbc-0.9.8-all.jar").get().asFile.absolutePath)
    systemProperty("clickhouse.catalog.fixture", layout.buildDirectory.dir("catalog-tests").get().asFile.absolutePath)
    for (name in listOf("BRIKK_CLICKHOUSE_URL", "BRIKK_CLICKHOUSE_DRIVER", "BRIKK_CLICKHOUSE_CONTAINER")) {
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
