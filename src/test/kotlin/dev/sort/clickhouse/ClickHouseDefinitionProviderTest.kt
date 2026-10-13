package dev.sort.clickhouse

import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.database.psi.DbPsiFacade
import com.intellij.openapi.util.Disposer
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.ast.toIdentifier
import dev.sort.clickhouse.catalog.ClickHouseDefinitionProvider
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseDefinitionProviderTest : ClickHousePlatformTestCase() {
    fun testObjectKindsAndQualifiedNamesUseSharedDefinitionQueries() {
        val model = ModelFactory.BLACK_HOLE.createModel(ClickHouseDbms.CLICKHOUSE_BRIKK)
        val root = model.root as CHouseRoot
        val schema = root.schemas.createOrGet("db`quoted")
        val table = schema.tables.createOrGet("ta`ble")
        val view = schema.views.createOrGet("view_name")
        val materialized = schema.matViews.createOrGet("mat_name")
        val dictionary = schema.tables.createOrGet("dictionary_name").apply { engine = "Dictionary" }
        val provider = ClickHouseDefinitionProvider()
        val source = LocalDataSource().apply {
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("clickhouse-sortdev"))
            name = "Offline ClickHouse definitions"
            url = "jdbc:offline:definitions"
            isAutoSynchronize = false
            isKeepAlive = false
            this.model = model
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        Disposer.register(testRootDisposable) { LocalDataSourceManager.getInstance(project).removeDataSource(source) }
        DbPsiFacade.getInstance(project).clearCaches()
        val file = myFixture.configureByText("definitions.sql", "SELECT 1")
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        RecordingConsole(project, file.virtualFile, source).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("different_database", ObjectKind.SCHEMA)))
            for ((obj, phrase) in listOf(
                table to "SHOW CREATE TABLE", view to "SHOW CREATE VIEW", materialized to "SHOW CREATE TABLE",
                dictionary to "SHOW CREATE DICTIONARY", schema to "SHOW CREATE DATABASE",
            )) {
                assertTrue(provider.isSupported(obj))
                val query = requireNotNull(provider.definitionQueryFor(obj))
                assertTrue(query.sql, query.sql.startsWith(phrase))
                val names = if (obj === schema) listOf(schema.name) else listOf(schema.name, obj.name)
                val qualified = names.joinToString(".") { name ->
                    Dialects.forName("clickhouse").generate(requireNotNull(toIdentifier(name, quoted = true)))
                }
                assertEquals("$phrase $qualified", query.sql)
                assertFalse(query.sql.contains("different_database"))
                assertEquals("CREATE object", query.definition(listOf(mapOf("statement" to "CREATE object"))))
            }
            assertEmpty(recording.requests)
        }
    }
}
