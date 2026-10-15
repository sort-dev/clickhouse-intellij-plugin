package dev.sort.clickhouse

import dev.brikk.house.sql.intellij.testing.PluginRegistrationContract

class ClickHousePluginRegistrationTest : ClickHousePlatformTestCase() {
    fun testRegistrationContract() {
        PluginRegistrationContract("dev.sort.clickhouse-intellij-plugin", "dev.sort.clickhouse").check(project)
    }
}
