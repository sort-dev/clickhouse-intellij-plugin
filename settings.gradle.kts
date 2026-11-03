pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            version("brikk-sql-core", "0.23.0")
            version("brikk-sql-verify", "0.23.0")
            version("brikk-sql-intellij-support", "0.4.0")
        }
    }
}

rootProject.name = "clickhouse-intellij-plugin"
