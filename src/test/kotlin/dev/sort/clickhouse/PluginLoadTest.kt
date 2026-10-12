package dev.sort.clickhouse

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry

class PluginLoadTest : ClickHousePlatformTestCase() {
    fun testPluginLoadsWithSharedLibraries() {
        val descriptor = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.clickhouse-intellij-plugin")))
        assertTrue(descriptor.isEnabled)
        assertNotNull(Dialects.forName("clickhouse"))
        assertEquals("clickhouse", SqlDialectRegistry.forLanguageId("ClickHouse")?.coreDialect)
    }
}
