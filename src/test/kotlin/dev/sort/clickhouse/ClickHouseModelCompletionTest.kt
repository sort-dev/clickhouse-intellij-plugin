package dev.sort.clickhouse

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectName
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.TreePattern
import com.intellij.database.util.SearchPath
import com.intellij.database.util.ObjectPath
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.IndexingTestUtil
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.brikk.ClickHouseRelationProfile
import com.intellij.codeInsight.lookup.LookupElement

class ClickHouseModelCompletionTest : ClickHousePlatformTestCase() {
    private lateinit var source: LocalDataSource
    override fun setUp() {
        super.setUp()
        val model = ModelFactory.BLACK_HOLE.createModel(ClickHouseDbms.CLICKHOUSE_BRIKK)
        val table = (model.root as CHouseRoot).schemas.createOrGet("analytics").tables.createOrGet("gate_events")
        table.columns.createOrGet("event_id")
        table.columns.createOrGet("event_value")
        source = LocalDataSource().apply {
            name = "ClickHouse model completion"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("clickhouse-sortdev"))
            url = "jdbc:offline:model-completion"
            isAutoSynchronize = false
            isKeepAlive = false
            this.model = model
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        Disposer.register(testRootDisposable) {
            LocalDataSourceManager.getInstance(project).removeDataSource(source)
            DbPsiFacade.getInstance(project).clearCaches()
        }
        DbPsiFacade.getInstance(project).clearCaches()
    }

    private fun complete(sql: String, dialect: SqlLanguageDialect = ClickHouseDialect): List<LookupElement> {
        val file = myFixture.configureByText("model-completion.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, dialect)
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(source.uniqueId)))))
        Disposer.register(testRootDisposable) { SqlResolveMappings.getInstance(project).setMapping(file.virtualFile, null) }
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        return RecordingConsole(project, file.virtualFile, source).use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("analytics", ObjectKind.SCHEMA)))
            val settings = CodeInsightSettings.getInstance()
            val previous = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
            try {
                myFixture.completeBasic().orEmpty().toList()
            } finally {
                settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previous
            }
        }
    }

    fun testTableCompletionMatchesStockInTheOfflineModelFixture() {
        val sql = "SELECT * FROM analytics.gate_e<caret>"
        val stock = complete(sql, CHouseDialect.INSTANCE).filter { it.lookupString == "gate_events" }
        val own = complete(sql).filter { it.lookupString == "gate_events" }
        assertEquals(stock.map { it.lookupString }, own.map { it.lookupString })
        // Neither dialect currently offers the table in this offline fixture. Do not call this insertion coverage.
        assertNotEmpty(ClickHouseRelationProfile.providers.mapNotNull { it.facts(myFixture.file) })
    }

    fun testColumnCompletionInsertsOnceFromTheModel() {
        val items = complete("SELECT e.event_i<caret> FROM analytics.gate_events e").filter { it.lookupString == "event_id" }
        assertEquals(items.toString(), 1, items.size)
        myFixture.lookup.currentItem = items.single()
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT e.event_id FROM analytics.gate_events e", myFixture.editor.document.text)
    }
}
