package dev.sort.clickhouse

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry

class PluginLoadTest : BasePlatformTestCase() {
    fun testPluginLoadsWithSharedLibraries() {
        val descriptor = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.clickhouse-intellij-plugin")))
        assertTrue(descriptor.isEnabled)
        assertNotNull(Dialects.forName("clickhouse"))
        assertEquals("clickhouse", SqlDialectRegistry.forLanguageId("ClickHouse")?.coreDialect)
    }
}
