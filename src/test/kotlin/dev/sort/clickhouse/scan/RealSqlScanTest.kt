package dev.sort.clickhouse.scan

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.database.dataSource.DatabaseConnectionManager
import com.intellij.database.dataSource.DatabaseDriverManager
import com.intellij.database.dataSource.LocalDataSource
import com.intellij.database.dataSource.LocalDataSourceManager
import com.intellij.database.dialects.clickhouse.model.CHouseRoot
import com.intellij.database.dialects.clickhouse.model.CHouseLikeColumn
import com.intellij.database.types.DasType
import com.intellij.database.types.DasSimpleTypeClass
import com.intellij.database.model.ModelFactory
import com.intellij.database.model.ObjectKind
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.ObjectPath
import com.intellij.database.util.SearchPath
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.util.Disposer
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import dev.brikk.house.sql.ast.ColumnDef
import dev.brikk.house.sql.ast.Create
import dev.brikk.house.sql.ast.Expression
import dev.brikk.house.sql.ast.Schema
import dev.brikk.house.sql.ast.Table
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.dialects.sql
import dev.brikk.house.sql.intellij.catalog.CatalogScopes
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.scope.ColumnFact
import dev.brikk.house.sql.scope.Knowledge
import dev.brikk.house.sql.scope.RelationFact
import dev.brikk.house.sql.scope.RelationFacts
import dev.brikk.house.sql.scope.RelationScope
import dev.sort.clickhouse.ClickHouseDbms
import dev.sort.clickhouse.ClickHousePlatformTestCase
import dev.sort.clickhouse.pipe.ClickHousePipeSettings
import dev.sort.clickhouse.sql.ClickHouseDialect
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Opt-in only. No input data, paths, identifiers or literals are embedded in this harness. */
class RealSqlScanTest : ClickHousePlatformTestCase() {
    private data class UnitSql(val file: Int, val statement: Int, val sql: String, val namespace: String)
    private data class Definition(val kind: String, val namespace: String, val name: String,
                                  val columns: MutableList<String>, val types: MutableMap<String, String>, val query: String?)

    fun testLocalEditorAnalysis() {
        val input = Path.of(requireNotNull(System.getProperty("realSql.dir")))
        val output = Files.createDirectories(Path.of(requireNotNull(System.getProperty("realSql.output"))))
        val prevented = AtomicInteger()
        val interceptorType = DatabaseConnectionManager.ConnectInterceptor::class.java
        val interceptor = Proxy.newProxyInstance(interceptorType.classLoader, arrayOf(interceptorType)) { proxy, method, args ->
            when (method.name) {
                "establishConnection" -> { prevented.incrementAndGet(); null }
                "toString" -> "Local-only scan connection barrier"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected connection barrier method")
            }
        } as DatabaseConnectionManager.ConnectInterceptor
        DatabaseConnectionManager.setInterceptor(testRootDisposable, interceptor)
        val resolve = LocalInspectionEP.LOCAL_INSPECTION.extensionList.single { it.implementationClass.endsWith(".SqlResolveInspection") }
        myFixture.enableInspections(resolve.instantiateTool() as LocalInspectionTool)
        val files = Files.walk(input).use { paths -> paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".sql", true) }.sorted().toList() }
        val units = mutableListOf<UnitSql>()
        val definitions = mutableListOf<Definition>()
        var modelParseFailures = 0
        var boundaryFailures = 0
        for ((fileIndex, path) in files.withIndex()) {
            val text = Files.readString(path)
            var namespace = "scan_default"
            val snapshot = SqlScriptSnapshot.parse(text, "clickhouse")
            for ((statementIndex, range) in snapshot.statements.withIndex()) {
                if (range.boundaryError != null) boundaryFailures++
                val sql = text.substring(range.start, range.end)
                if (sql.trim().trim(';').isBlank()) continue
                val expression = runCatching { Dialects.forName("clickhouse").parseOne(sql) }.getOrNull()
                if (expression?.javaClass?.simpleName == "Use") namespace = (expression.thisArg as? Expression)?.name?.ifEmpty { namespace } ?: namespace
                units += UnitSql(fileIndex, statementIndex, sql, namespace)
                if (expression is Create) {
                    val schema = expression.thisArg as? Schema
                    val table = (schema?.thisArg ?: expression.thisArg) as? Table
                    val kind = expression.text("kind").uppercase()
                    if (table != null && kind in setOf("TABLE", "VIEW", "DICTIONARY", "MATERIALIZED VIEW")) {
                        val columns = (schema?.args?.get("expressions") as? List<*>).orEmpty()
                            .filterIsInstance<ColumnDef>().map { it.name }.toMutableList()
                        val types = (schema?.args?.get("expressions") as? List<*>).orEmpty().filterIsInstance<ColumnDef>()
                            .mapNotNull { column -> (column.args["kind"] as? Expression)?.sql("clickhouse")?.let { column.name to it } }.toMap().toMutableMap()
                        val query = (expression.args["expression"] as? Expression)?.sql("clickhouse")
                        definitions += Definition(kind, table.db.ifEmpty { namespace }, table.name, columns, types, query)
                    }
                } else if (expression == null && sql.trimStart().startsWith("CREATE", true)) modelParseFailures++
            }
        }
        // Resolve view/CTAS output through the same shared analyzer, including dependent definitions.
        repeat(definitions.size.coerceAtLeast(1)) {
            val facts = RelationFacts(tables = definitions.associate { definition ->
                "${definition.namespace}.${definition.name}" to RelationFact(definition.columns.map { ColumnFact(it, sqlType = definition.types[it]) },
                    if (definition.columns.isEmpty()) Knowledge.UNKNOWN else Knowledge.FULL)
            })
            definitions.filter { it.columns.isEmpty() && it.query != null }.forEach { definition ->
                val query = requireNotNull(definition.query)
                val derived = runCatching {
                    RelationScope.analyze(query, "clickhouse", facts.copy(searchPath = listOf(definition.namespace))).output
                }.getOrNull()
                if (derived?.knowledge == Knowledge.FULL) {
                    definition.columns += derived.columns.map { it.name }
                    derived.columns.forEach { column -> column.sqlType?.let { definition.types[column.name] = it } }
                }
            }
        }
        val native = ModelFactory.BLACK_HOLE.createModel(ClickHouseDbms.CLICKHOUSE_BRIKK)
        val root = native.root as CHouseRoot
        definitions.forEach { definition ->
            val schema = root.schemas.createOrGet(definition.namespace)
            when (definition.kind) {
                "VIEW" -> schema.views.createOrGet(definition.name).also { table -> definition.columns.forEach { type(table.columns.createOrGet(it), definition.types[it]) } }
                "MATERIALIZED VIEW" -> schema.matViews.createOrGet(definition.name).also { table -> definition.columns.forEach { type(table.columns.createOrGet(it), definition.types[it]) } }
                else -> schema.tables.createOrGet(definition.name).also { table ->
                    if (definition.kind == "DICTIONARY") table.engine = "Dictionary"
                    definition.columns.forEach { type(table.columns.createOrGet(it), definition.types[it]) }
                }
            }
        }
        val source = LocalDataSource().apply {
            name = "Local scan model"
            databaseDriver = requireNotNull(DatabaseDriverManager.getInstance().getDriver("clickhouse-sortdev"))
            url = "jdbc:offline:local-analysis-only"
            isAutoSynchronize = false
            isKeepAlive = false
            model = native
            if (!root.schemas.isEmpty()) introspectionScope = root.schemas.map {
                CatalogScopes.select(listOf(ObjectKind.SCHEMA), listOf(it.name))
            }.reduce(CatalogScopes::widen)
        }
        val sources = LocalDataSourceManager.getInstance(project)
        Disposer.register(testRootDisposable) { sources.removeDataSource(source); DbPsiFacade.getInstance(project).clearCaches() }
        val settings = CodeInsightSettings.getInstance()
        val previousAutocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        val pipe = project.service<ClickHousePipeSettings>()
        val previousPipe = pipe.enabled
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        val counts = linkedMapOf<String, Int>()
        var samples = 0
        try {
            Files.newBufferedWriter(output.resolve("findings.jsonl")).use { findings ->
                for (withModel in listOf(false, true)) {
                    if (withModel) sources.addDataSource(source)
                    DbPsiFacade.getInstance(project).clearCaches()
                    for (pipes in listOf(false, true)) {
                        pipe.enabled = pipes
                        val mode = "${if (withModel) "model" else "no-model"}-${if (pipes) "pipe-on" else "pipe-off"}"
                        for (unit in units) {
                            var console: RecordingConsole? = null
                            try {
                                val file = myFixture.configureByText("local-scan.sql", unit.sql).virtualFile
                                setSqlDialectMapping(project, file, ClickHouseDialect)
                                myFixture.configureFromExistingVirtualFile(file)
                                if (withModel) console = RecordingConsole(project, file, source).also {
                                    it.console.setSearchPath(SearchPath.of(ObjectPath.create(unit.namespace, ObjectKind.SCHEMA)))
                                }
                                for (info in myFixture.doHighlighting(HighlightSeverity.ERROR)) {
                                    val description = info.description.orEmpty()
                                    val category = category(description)
                                    counts.merge("$mode/$category", 1, Int::plus)
                                    val record = buildJsonObject {
                                        put("file", unit.file); put("statement", unit.statement); put("mode", mode)
                                        put("category", category); put("start", info.startOffset); put("end", info.endOffset)
                                        put("description", description); put("sql", unit.sql)
                                    }
                                    findings.appendLine(record.toString())
                                }
                                if (withModel && !pipes) {
                                    val references = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlReferenceExpression::class.java)
                                        .filter { it.referenceElementType.targetKind in setOf(ObjectKind.COLUMN, ObjectKind.TABLE) }
                                        .take(2)
                                    for (reference in references) {
                                        val offset = reference.identifier?.textOffset ?: continue
                                        myFixture.editor.caretModel.moveToOffset(offset)
                                        val offered = myFixture.completeBasic().orEmpty().map { it.lookupString }
                                        samples++
                                        findings.appendLine(buildJsonObject {
                                            put("file", unit.file); put("statement", unit.statement); put("mode", mode)
                                            put("category", "completion"); put("offset", offset)
                                            put("offered", JsonArray(offered.map(::JsonPrimitive)))
                                        }.toString())
                                        myFixture.lookup?.hideLookup(true)
                                    }
                                }
                            } catch (failure: Throwable) {
                                if (failure is ControlFlowException || failure is java.util.concurrent.CancellationException || failure is VirtualMachineError) throw failure
                                counts.merge("$mode/analysis-exception", 1, Int::plus)
                                findings.appendLine(buildJsonObject {
                                    put("file", unit.file); put("statement", unit.statement); put("mode", mode)
                                    put("category", "analysis-exception"); put("failure", failure.toString()); put("sql", unit.sql)
                                }.toString())
                            } finally {
                                console?.close()
                            }
                        }
                    }
                }
            }
        } finally {
            pipe.enabled = previousPipe
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previousAutocomplete
        }
        val summary = buildJsonObject {
            put("files", files.size); put("statements", units.size); put("definitions", definitions.size)
            put("modelParseFailures", modelParseFailures); put("boundaryFailures", boundaryFailures)
            put("completionSamples", samples); put("preventedConnectionAttempts", prevented.get())
            put("counts", buildJsonObject { counts.forEach { (key, value) -> put(key, value) } })
        }
        Files.writeString(output.resolve("summary.json"), summary.toString())
        println("Local-only scan summary: $summary")
    }

    private fun type(column: CHouseLikeColumn, sqlType: String?) {
        if (sqlType == null) return
        column.storedType = object : DasType {
            override fun getTypeClass() = DasSimpleTypeClass(sqlType)
            override fun getDescription() = sqlType
            override fun getSpecification() = sqlType
        }
    }

    private fun category(description: String): String = when {
        description.contains("function", true) && description.contains("unknown", true) -> "function-resolve"
        description.contains("column", true) && (description.contains("resolve", true) || description.contains("missing", true)) -> "column-resolve"
        description.contains("resolve table", true) || description.contains("resolve schema", true) -> "table-resolve"
        description.contains("FORMAT", true) -> "format-or-settings"
        description.contains("parameter", true) -> "parameter"
        description.startsWith("ClickHouse (brikk-sql)") -> "core-parse"
        else -> "psi-or-other"
    }
}
