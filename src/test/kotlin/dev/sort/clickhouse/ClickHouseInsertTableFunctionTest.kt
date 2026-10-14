package dev.sort.clickhouse

import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.database.Dbms
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.model.ObjectName
import com.intellij.database.util.TreePattern
import com.intellij.sql.dialects.SqlImportUtil
import com.intellij.sql.dialects.SqlResolveMappings
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.types.DasSimpleTypeClass
import com.intellij.database.types.DasType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.SqlLanguageDialect
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.inlays.SqlColumnNamesInlayHintsProvider
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlInsertDmlInstruction
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect
import java.lang.reflect.Proxy

abstract class ClickHouseTableFunctionTestCase : ClickHousePlatformTestCase() {
    protected val export = """
        INSERT INTO FUNCTION s3('https://example.invalid/bucket/path/{_partition_id}.parquet', 'KEY', 'SECRET', 'Parquet')
        PARTITION BY concat('year=', toString(toYear(toDateTime(ts))), '/')
        SELECT *
        FROM db1.v_export
        WHERE (event_time >= toDate('2026-10-13') AND event_time < toDate('2026-10-14'))
        SETTINGS max_memory_usage = 1000, max_insert_threads = 4
    """.trimIndent()

    override fun setUp() {
        super.setUp()
        val tools = setOf("SqlResolveInspection", "SqlInsertValuesInspection", "SqlTypeInspection")
        LocalInspectionEP.LOCAL_INSPECTION.extensionList.filter { it.implementationClass.substringAfterLast('.') in tools }
            .forEach { myFixture.enableInspections(it.instantiateTool() as LocalInspectionTool) }
    }

    private val created = mutableMapOf<Boolean, LocalDataSource>()

    private fun prepareSource(stock: Boolean) {
        if (stock in created) return
        val dbms = if (stock) Dbms.CLICKHOUSE else ClickHouseDbms.CLICKHOUSE_BRIKK
        val model = ModelFactory.BLACK_HOLE.createModel(dbms)
        val schema = (model.root as CHouseRoot).schemas.createOrGet("db1")
        val view = schema.views.createOrGet("v_export")
        val names = listOf("ts", "event_time") + (1..32).map { "value_$it" }
        names.forEach { name ->
            val sqlType = if (name in setOf("ts", "event_time")) "DateTime" else "UInt64"
            view.columns.createOrGet(name).storedType = object : DasType {
                override fun getTypeClass() = DasSimpleTypeClass(sqlType)
                override fun getSpecification() = sqlType
                override fun getDescription() = sqlType
            }
        }
        schema.tables.createOrGet("one_column").columns.createOrGet("value")
        val source = LocalDataSource().apply {
            name = "Sanitized table-function export"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver(if (stock) "clickhouse" else "clickhouse-sortdev"))
            url = "jdbc:offline:insert-table-function"
            isAutoSynchronize = false
            isKeepAlive = false
            this.model = model
            introspectionScope = CatalogScopes.select(listOf(ObjectKind.SCHEMA), listOf("db1"))
        }
        val sources = LocalDataSourceManager.getInstance(project)
        sources.addDataSource(source)
        created[stock] = source
        Disposer.register(testRootDisposable) { sources.removeDataSource(source); DbPsiFacade.getInstance(project).clearCaches() }
        DbPsiFacade.getInstance(project).clearCaches()
        assertEquals(34, view.columns.size())
    }

    protected fun open(sql: String, stock: Boolean = false) {
        prepareSource(stock)
        val file = myFixture.configureByText("sanitized-export.sql", sql)
        val language: SqlLanguageDialect = if (stock) CHouseDialect.INSTANCE else ClickHouseDialect
        setSqlDialectMapping(project, file.virtualFile, language)
        SqlResolveMappings.getInstance(project).setMapping(file.virtualFile,
            TreePattern(SqlImportUtil.createDataSources(arrayOf(ObjectName.quoted(requireNotNull(created[stock]).uniqueId)))))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }

    protected fun hintOffsets(): List<Int> {
        val positions = mutableListOf<Int>()
        val sink = Proxy.newProxyInstance(InlayTreeSink::class.java.classLoader, arrayOf(InlayTreeSink::class.java)) { _, method, args ->
            when (method.name) {
                "whenOptionEnabled" -> { (args!![1] as Function0<*>).invoke(); null }
                "addPresentation" -> { (args!![0] as? InlineInlayPosition)?.let { positions += it.offset }; null }
                else -> null
            }
        } as InlayTreeSink
        val collector = SqlColumnNamesInlayHintsProvider().createCollector(myFixture.file, myFixture.editor)
        PsiTreeUtil.collectElements(myFixture.file) { true }.forEach { collector.collectFromElement(it, sink) }
        return positions
    }
}

class ClickHouseInsertTableFunctionTest : ClickHouseTableFunctionTestCase() {
    fun testSanitizedExportHasNoFalseProblemsOrTargetHint() {
        open(export)
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING))
        val insert = requireNotNull(PsiTreeUtil.findChildOfType(myFixture.file, SqlInsertDmlInstruction::class.java))
        assertEquals("An opaque table-function target must not acquire one synthetic column", 0, insert.targetType.columnCount)
        val star = export.indexOf("SELECT *") + "SELECT ".length
        assertFalse("No target-column hint before the SELECT star", star in hintOffsets())
    }

    fun testStockBaselineOnTheSameManyColumnView() {
        open(export, stock = true)
        val reports = myFixture.doHighlighting(HighlightSeverity.WARNING)
        println("Stock sanitized export: reports=${reports.size}; insertCounts=${reports.count { it.toolId.toString().contains("SqlInsertValues") }}; hints=${hintOffsets().size}")
    }

    fun testKnownTableFunctionsResolveInInsertAndFromPositions() {
        open("INSERT INTO FUNCTION s3('https://example.invalid/out', 'Parquet') SELECT 1; SELECT * FROM numbers(1)")
        val calls = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java)
        for (name in listOf("s3", "numbers")) {
            val call = calls.single { it.nameElement?.name == name }
            assertNotNull(name, call.nameElement?.resolveSymbol())
        }
    }

    fun testUnknownFunctionAndOrdinaryInsertMismatchStayReported() {
        val sql = "INSERT INTO FUNCTION unknown_table_function(1) SELECT 1; INSERT INTO db1.one_column SELECT * FROM db1.v_export"
        open(sql)
        val reports = myFixture.doHighlighting(HighlightSeverity.WARNING)
        assertTrue(reports.toString(), reports.any { it.description.orEmpty().contains("unknown_table_function") })
        assertTrue(reports.toString(), reports.any { it.toolId.toString().contains("SqlInsertValues") })
        assertTrue("Ordinary insert target-column hints remain enabled", sql.lastIndexOf('*') in hintOffsets())
    }
}
