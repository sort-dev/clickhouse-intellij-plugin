package dev.sort.clickhouse

import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.DatabaseDialects
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.registerOrReplaceServiceInstance
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.intellij.catalog.NamespaceTarget
import dev.brikk.house.sql.intellij.testing.BackgroundErrors
import dev.brikk.house.sql.intellij.testing.HeldIntrospections
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.catalog.ClickHouseAutoIntrospectService
import dev.sort.clickhouse.catalog.ClickHouseCatalogProfile
import dev.sort.clickhouse.catalog.clickHouseDataSources
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseNamespaceTest : ClickHousePlatformTestCase() {
    fun testStockUseStatementQuotesASingleDatabaseName() {
        val dialect = requireNotNull(DatabaseDialects.findByDbms(ClickHouseDbms.CLICKHOUSE_BRIKK))
        val name = "db`with.dot"
        val sql = requireNotNull(dialect.sqlSetSearchPath(SearchPath.of(ObjectPath.create(name, ObjectKind.SCHEMA))))
        assertTrue(sql, sql.startsWith("USE ", true))
        assertEquals(name, (Dialects.forName("clickhouse").parseOne(sql).thisArg as Expression).name)
    }

    fun testConsoleNamespaceUsesTheNativeSingleLevel() {
        val file = myFixture.configureByText("namespace-console.sql", "SELECT 1")
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        RecordingConsole(project, file.virtualFile, "clickhouse-sortdev").use { recording ->
            recording.console.setSearchPath(SearchPath.of(ObjectPath.create("analytics", ObjectKind.SCHEMA)))
            assertEquals(listOf("analytics"), clickHouseDataSources.consoleNamespace(myFixture.file))
            assertSame(recording.console.session, clickHouseDataSources.consoleSession(myFixture.file))
        }
    }

    fun testSharedAutoIntrospectionRefreshesOnlyTheListedDatabase() {
        val errors = BackgroundErrors(testRootDisposable)
        val held = HeldIntrospections(testRootDisposable)
        val scope = errors.newScope(testRootDisposable)
        val service = ClickHouseAutoIntrospectService(project, scope)
        assertSame(ClickHouseCatalogProfile, project.service<ClickHouseAutoIntrospectService>().profile)
        project.registerOrReplaceServiceInstance(ClickHouseAutoIntrospectService::class.java, service, testRootDisposable)
        val model = ModelFactory.BLACK_HOLE.createModel(ClickHouseDbms.CLICKHOUSE_BRIKK)
        val root = model.root as CHouseRoot
        val schema = root.schemas.createOrGet("analytics")
        root.schemas.createOrGet("untouched")
        val source = LocalDataSource().apply {
            name = "Offline ClickHouse namespace"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("clickhouse-sortdev"))
            url = "jdbc:offline:namespace"
            isAutoSynchronize = false
            isKeepAlive = false
            this.model = model
        }
        LocalDataSourceManager.getInstance(project).addDataSource(source)
        Disposer.register(testRootDisposable) { LocalDataSourceManager.getInstance(project).removeDataSource(source) }
        DbPsiFacade.getInstance(project).clearCaches()
        val file = myFixture.configureByText("introspect.sql", "SELECT * FROM analytics.<caret>")
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        try {
            myFixture.completeBasic()
            val refresh = held.next()
            assertNotNull(service.pending(file.virtualFile))
            refresh.finish { schema.tables.createOrGet("loaded_table") }
            errors.awaitScopes()
            PlatformTestUtil.waitWithEventsDispatching("ClickHouse introspection clears its banner", { service.pending(file.virtualFile) == null }, 10)
            assertTrue(schema.hasChildren())
            assertFalse(root.schemas.createOrGet("untouched").hasChildren())
            assertFalse(service.request(myFixture.file, source, NamespaceTarget(listOf("analytics"), schema)))
            errors.assertNone()
        } finally {
            held.finishAll()
            errors.awaitScopes()
            errors.assertNone()
        }
    }
}
