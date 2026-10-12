package dev.sort.clickhouse

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class PluginIsolationTest : BasePlatformTestCase() {
    fun testSharedLibrariesBelongToTheirBundlingPlugin() {
        val plugins = listOf("clickhouse") + System.getProperty("test.siblingPlugins", "")
            .split(',').filter(String::isNotBlank)
        val supportCopies = plugins.map { name ->
            val descriptor = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.$name-intellij-plugin")))
            val loader = requireNotNull(descriptor.pluginClassLoader)
            val support = loader.loadClass("dev.brikk.house.sql.intellij.dialect.SqlDialectRegistry")
            assertSame(name, loader, support.classLoader)
            assertSame(name, loader, loader.loadClass("dev.brikk.house.sql.BrikkSql").classLoader)
            support
        }
        for ((index, copy) in supportCopies.withIndex()) {
            for (peer in supportCopies.drop(index + 1)) assertNotSame(copy, peer)
        }
    }
}
