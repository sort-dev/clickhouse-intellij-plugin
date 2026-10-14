package dev.sort.clickhouse

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.database.Dbms
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.openapi.util.Disposer
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.RealSqlCase
import dev.brikk.house.sql.intellij.testing.RealSqlCases
import dev.brikk.house.sql.intellij.testing.RealSqlContract
import dev.brikk.house.sql.intellij.testing.RealSqlFeature
import dev.brikk.house.sql.intellij.testing.RealSqlModel
import dev.brikk.house.sql.intellij.testing.RealSqlTarget
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseRealSqlContractTest : ClickHousePlatformTestCase() {
    fun testEverydaySqlThroughRegisteredEditorComponents() {
        assertEmpty(check("clickhouse-sortdev", ClickHouseDbms.CLICKHOUSE_BRIKK, ClickHouseDialect))
    }
    fun testEverydaySqlIsNoWorseThanStock() {
        val stock = check("clickhouse", Dbms.CLICKHOUSE, CHouseDialect.INSTANCE)
        val own = check("clickhouse-sortdev", ClickHouseDbms.CLICKHOUSE_BRIKK, ClickHouseDialect)
        assertTrue("Plugin-only reports: ${own - stock.toSet()}", stock.containsAll(own))
    }
    fun testExternalRelationsAcrossModelStates() {
        check("clickhouse-sortdev", ClickHouseDbms.CLICKHOUSE_BRIKK, ClickHouseDialect, checkStates = true)
    }

    private fun check(driver: String, dbms: Dbms, language: SqlLanguageDialect, checkStates: Boolean = false): List<String> {
        val resolve = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".SqlResolveInspection") }
        myFixture.enableInspections(resolve.instantiateTool() as LocalInspectionTool)
        fun buildModel(spec: RealSqlModel) = ModelFactory.BLACK_HOLE.createModel(dbms).also { model ->
            val root = model.root as CHouseRoot
            for ((path, columns) in spec.tables) {
                val (_, schema, name) = path.split('.')
                val table = root.schemas.createOrGet(schema).tables.createOrGet(name)
                columns.forEach { table.columns.createOrGet(it) }
            }
            spec.bare.forEach { path ->
                val (_, schema, name) = path.split('.')
                root.schemas.createOrGet(schema).tables.createOrGet(name)
            }
            spec.namespaces.filter { it.split('.').size == 2 }.forEach { path -> root.schemas.createOrGet(path.split('.')[1]) }
        }
        val initial = buildModel(RealSqlModel.STANDARD)
        val source = LocalDataSource().apply {
            name = "ClickHouse everyday SQL fixture"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver(driver))
            url = "jdbc:offline:everyday-sql"
            isAutoSynchronize = false
            isKeepAlive = false
            this.model = initial
            introspectionScope = (initial.root as CHouseRoot).schemas.map { CatalogScopes.select(listOf(ObjectKind.SCHEMA), listOf(it.name)) }
                .reduce(CatalogScopes::widen)
        }
        val sources = LocalDataSourceManager.getInstance(project)
        sources.addDataSource(source)
        Disposer.register(testRootDisposable) { sources.removeDataSource(source); DbPsiFacade.getInstance(project).clearCaches() }
        DbPsiFacade.getInstance(project).clearCaches()
        var console: RecordingConsole? = null
        val settings = CodeInsightSettings.getInstance()
        val previousAutocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        val target = object : RealSqlTarget {
            override val dialect = "clickhouse"
            override val fixture get() = myFixture
            override val features = setOf(
                RealSqlFeature.STAR_EXCEPT, RealSqlFeature.STAR_REPLACE, RealSqlFeature.LATERAL_ALIAS,
                RealSqlFeature.GROUP_BY_ALIAS, RealSqlFeature.FULL_JOIN, RealSqlFeature.INTERSECT_EXCEPT,
                RealSqlFeature.NAMED_WINDOW, RealSqlFeature.UNNEST, RealSqlFeature.CREATE_TABLE_AS,
                RealSqlFeature.DOUBLE_QUOTED_IDENTIFIERS, RealSqlFeature.BACKTICK_IDENTIFIERS,
            )
            override fun open(sql: String) {
                console?.close()
                val file = myFixture.configureByText("everyday-clickhouse.sql", sql).virtualFile
                setSqlDialectMapping(project, file, language, testRootDisposable)
                myFixture.configureFromExistingVirtualFile(file)
                console = RecordingConsole(project, file, source).also {
                    it.console.setSearchPath(SearchPath.of(ObjectPath.create("sales", ObjectKind.SCHEMA)))
                }
            }
            override fun install(model: RealSqlModel) {
                console?.close()
                console = null
                val replacement = buildModel(model)
                source.model = replacement
                source.introspectionScope = (replacement.root as CHouseRoot).schemas
                    .map { CatalogScopes.select(listOf(ObjectKind.SCHEMA), listOf(it.name)) }.reduce(CatalogScopes::widen)
                DbPsiFacade.getInstance(project).clearCaches()
            }
        }
        val variants = mapOf(
            "unnest" to "SELECT u.name, tag FROM sales.users u ARRAY JOIN ['a', 'b'] AS tag",
            "create-table-as" to "CREATE TABLE sales.big_orders ENGINE=Memory AS SELECT order_id, amount FROM sales.orders WHERE amount > 100",
            "unquoted-case-folding" to "SELECT o.order_id, o.user_id FROM sales.orders o",
        )
        val cases = RealSqlCases.ALL.map { case ->
            variants[case.name]?.let { case.copy(variants = case.variants + ("clickhouse" to it)) } ?: case
        } + listOf(
            RealSqlCase("where-alias", "SELECT amount * 2 AS doubled FROM sales.orders WHERE doubled > 10"),
            RealSqlCase("minimal-window-row-number", "SELECT row_number() OVER ()"),
            RealSqlCase("minimal-window-rank", "SELECT rank() OVER ()"),
            RealSqlCase("minimal-exists", "SELECT 1 WHERE EXISTS (SELECT 1)"),
        )
        try {
            if (checkStates) {
                val external = RealSqlCases.EXTERNAL.map { case ->
                    val sql = case.sql.replace("ext_catalog.archive_db.facts", "archive_db.facts")
                        .replace("ts AS ts, * EXCEPT (ts), 0 AS k", "ts AS ts, 0 AS k, * EXCEPT (ts)")
                    case.copy(needs = case.needs - RealSqlFeature.THREE_PART_NAMES,
                        variants = case.variants + ("clickhouse" to sql),
                        reported = case.reported.map { it.replace("ext_catalog.archive_db.facts", "archive_db.facts") }.toSet())
                }
                RealSqlContract(target).checkModelStates(cases = external)
                return emptyList()
            }
            val contract = RealSqlContract(target, cases)
            return cases.filter { target.features.containsAll(it.needs) }.flatMap(contract::failures)
        } finally {
            console?.close()
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previousAutocomplete
        }
    }
}
