plugins {
    id("dev.sort.sql-dialect-plugin")
}

group = "dev.sort.clickhouse"
version = "0.1.0-SNAPSHOT"

sqlDialectPlugin {
    kotlinTests = false
    legalFiles = listOf("LICENSE", "THIRD_PARTY_NOTICES.md")
    embeddedLibraries = with(libs.versions) {
        setOf(
            "brikk-sql-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-sql-metadata-jvmMain-${brikk.sql.core.get()}.jar",
            "brikk-sql-intellij-support-${brikk.sql.intellij.support.get()}.jar",
            "brikk-sql-verify-api-${brikk.sql.core.get()}.jar",
            "brikk-sql-verify-clickhouse-${brikk.sql.core.get()}.jar",
        )
    }
    requiredNotices = listOf("brikk-sql-intellij-support", "brikk-sql-metadata", "Apache License 2.0")
}

dependencies {
    implementation(libs.brikk.sql.jvm)
    implementation(libs.brikk.sql.metadata.jvm)
    implementation(libs.brikk.sql.intellij.support)
    implementation("dev.brikk.house:brikk-sql-verify-clickhouse:${libs.versions.brikk.sql.core.get()}")
    testImplementation(libs.brikk.sql.intellij.test.support)
}

tasks.withType<Test>().configureEach {
    // Pending shared diagnostic combination fix: this statement currently yields three distinct diagnostics.
    filter.excludeTestsMatching("dev.sort.clickhouse.ClickHouseNativeWiringTest.testKnownLibraryGapOneCombinedErrorPerMalformedStatement")
    // Known failing pending brikk-sql's ClickhouseParser PARTITION BY destination-column-list fix and diagnostic deduplication.
    filter.excludeTestsMatching("dev.sort.clickhouse.ClickHouseErrorHighlightTest.testMaskedBindFallbackHasNoErrorHighlightsAfterCoreColumnListFix")
    if (providers.environmentVariable("BRIKK_CLICKHOUSE_URL").orNull.isNullOrBlank()) {
        exclude("**/ClickHouseServerVerificationTest.class")
    }
    systemProperty("clickhouse.driver.fixture", layout.buildDirectory.file("clickhouse-driver/clickhouse-jdbc-0.9.8-all.jar").get().asFile.absolutePath)
    for (name in listOf("BRIKK_CLICKHOUSE_URL", "BRIKK_CLICKHOUSE_DRIVER", "BRIKK_CLICKHOUSE_CONTAINER")) {
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
