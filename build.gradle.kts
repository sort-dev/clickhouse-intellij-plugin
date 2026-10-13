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
        )
    }
    requiredNotices = listOf("brikk-sql-intellij-support", "brikk-sql-metadata", "Apache License 2.0")
}

dependencies {
    implementation(libs.brikk.sql.jvm)
    implementation(libs.brikk.sql.metadata.jvm)
    implementation(libs.brikk.sql.intellij.support)
    testImplementation(libs.brikk.sql.intellij.test.support)
}
