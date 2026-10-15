package dev.sort.clickhouse

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.util.PathUtil
import dev.brikk.house.sql.intellij.catalog.AutoIntrospectService
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** Checks the classes actually loaded by each worker, not merely Gradle's resolved file list. */
internal object ClickHouseDependencyProvenance {
    private val verified: Unit by lazy {
        val properties = Properties().apply {
            requireNotNull(ClickHouseDependencyProvenance::class.java.getResourceAsStream("/META-INF/dependency-provenance.properties")).use { load(it) }
        }
        val support = "brikk-sql-intellij-support-0.2.0-SNAPSHOT.jar"
        val expected = requireNotNull(properties.getProperty(support))
        val loader = requireNotNull(PluginManagerCore.getPlugin(PluginId.getId("dev.sort.clickhouse-intellij-plugin"))?.pluginClassLoader)
        val classes = listOf(AutoIntrospectService::class.java, loader.loadClass(AutoIntrospectService::class.java.name))
        for (type in classes.distinct()) {
            val path = Path.of(PathUtil.getJarPathForClass(type))
            val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).toHexString()
            check(expected == actual) { "Loaded support jar $path has $actual, expected $expected" }
            println("DEPENDENCY_RUNTIME source=${properties.getProperty("brikk.sql.source")} sha256=$actual jar=$path")
        }
    }

    fun verify() { verified }
}
