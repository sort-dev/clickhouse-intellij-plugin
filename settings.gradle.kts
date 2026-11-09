pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            version("brikk-sql-core", "1.0.1")
            version("brikk-sql-verify", "1.0.1")
            version("brikk-sql-intellij-support", "1.0.1")
        }
    }
}

rootProject.name = "clickhouse-intellij-plugin"
