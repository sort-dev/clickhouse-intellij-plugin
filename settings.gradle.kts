pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            from(files("build-logic/gradle/libs.versions.toml"))
            version("brikk-sql-core", "0.22.0-SNAPSHOT")
            version("brikk-sql-verify", "0.22.0-SNAPSHOT")
            version("brikk-sql-intellij-support", "0.2.5-SNAPSHOT")
        }
    }
}

rootProject.name = "clickhouse-intellij-plugin"
