package dev.sort.clickhouse

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlInsertDmlInstruction
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlDasTypeAwareElement
import com.intellij.database.types.DasTableType
import dev.brikk.house.sql.metadata.FunctionKind
import dev.sort.clickhouse.sql.ClickHouseCompletionProfile
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Data-driven catalog × position sweep; it neither connects nor executes any table function. */
class ClickHouseTableFunctionSweepTest : ClickHouseTableFunctionTestCase() {
    private enum class Position { FROM, ALIAS, JOIN, DESCRIBE, INSERT_SELECT, INSERT_PARTITION, INSERT_VALUES, INSERT_FORMAT }
    private data class Cell(val function: String, val position: Position, val sql: String)

    // Stable writable backends. Reader-only and version/feature-gated lake writers are not claimed here.
    private val writable = setOf("s3", "s3Cluster", "url", "urlCluster", "file", "fileCluster", "remote", "remoteSecure",
        "cluster", "clusterAllReplicas", "mysql", "postgresql", "hdfs", "hdfsCluster", "azureBlobStorage",
        "azureBlobStorageCluster", "gcs", "oss", "cosn", "null", "redis", "sqlite")

    private fun args(name: String): String = when {
        name in setOf("numbers", "numbers_mt", "zeros", "zeros_mt", "primes") -> "3"
        name in setOf("generateSeries", "generate_series") -> "1, 3"
        name in setOf("input", "null", "generateRandom") -> "'field_a UInt64, field_b String'"
        name in setOf("values", "SQLStandardValues") -> "'field_a UInt64, field_b String', (1, 'sample')"
        name == "format" -> "'TSV', 'field_a UInt64, field_b String', '1\\tsample'"
        name in setOf("view", "viewExplain", "viewIfPermitted") -> "SELECT 1 AS field_a, 'sample' AS field_b"
        name in setOf("cluster", "clusterAllReplicas") -> "'cluster_demo', 'db1', 'one_column'"
        name == "merge" -> "'db1', 'one_column'"
        name == "dictionary" -> "'db1.dictionary_demo'"
        name in setOf("mysql", "postgresql") -> "'example.invalid:9000', 'db_demo', 'table_demo', 'USER', 'PASSWORD'"
        name == "sqlite" -> "'/nonexistent/example.db', 'table_demo'"
        name in setOf("remote", "remoteSecure") -> "'example.invalid:9000', 'db1', 'one_column', 'USER', 'PASSWORD'"
        name in setOf("file", "filesystem") -> "'/nonexistent/example.tsv', 'TSV', 'field_a UInt64, field_b String'"
        name == "fileCluster" -> "'cluster_demo', '/nonexistent/example.tsv', 'TSV', 'field_a UInt64, field_b String'"
        name in setOf("url", "hdfs", "gcs", "oss", "cosn") -> "'https://example.invalid/object', 'TSV', 'field_a UInt64, field_b String'"
        name in setOf("urlCluster", "hdfsCluster") -> "'cluster_demo', 'https://example.invalid/object', 'TSV', 'field_a UInt64, field_b String'"
        name == "s3" -> "'https://example.invalid/object', 'KEY', 'SECRET', 'TSV', 'field_a UInt64, field_b String'"
        name == "s3Cluster" -> "'cluster_demo', 'https://example.invalid/object', 'KEY', 'SECRET', 'TSV', 'field_a UInt64, field_b String'"
        name.startsWith("azureBlobStorage") -> (if (name.endsWith("Cluster")) "'cluster_demo', " else "") +
            "'https://example.invalid', 'container_demo', 'object_demo', 'ACCOUNT', 'KEY', 'TSV', 'field_a UInt64, field_b String'"
        name in setOf("jdbc", "odbc") -> "'connection_demo', 'db_demo', 'table_demo'"
        name == "executable" -> "'command_demo', 'TSV', 'field_a UInt64, field_b String'"
        name == "redis" -> "'example.invalid:6379', 'key String, value String'"
        name == "loop" -> "numbers(3)"
        else -> "'https://example.invalid/object'"
    }

    private fun cells(function: String): List<Cell> {
        val call = "$function(${args(function)})"
        val projection = "SELECT 1 AS field_a, 'sample' AS field_b"
        val result = mutableListOf(
            Cell(function, Position.FROM, "SELECT * FROM $call"),
            Cell(function, Position.ALIAS, "SELECT * FROM $call AS t"),
            Cell(function, Position.JOIN, "SELECT * FROM db1.one_column a CROSS JOIN $call AS t"),
            Cell(function, Position.DESCRIBE, "DESCRIBE TABLE $call"),
        )
        if (function in writable) result += listOf(
            Cell(function, Position.INSERT_SELECT, "INSERT INTO FUNCTION $call $projection"),
            Cell(function, Position.INSERT_PARTITION, "INSERT INTO FUNCTION $call PARTITION BY toString(1) $projection"),
            Cell(function, Position.INSERT_VALUES, "INSERT INTO FUNCTION $call VALUES (1, 'sample')"),
            Cell(function, Position.INSERT_FORMAT, "INSERT INTO FUNCTION $call FORMAT TSV\n1\tsample\n;"),
        )
        return result
    }

    fun testEveryBundledTableFunctionInItsSyntacticPositions() {
        open("SELECT 1")
        val functions = ClickHouseCompletionProfile.functions(myFixture.file).filter { it.kind == FunctionKind.TABLE_VALUED }
            .map { it.name }.distinct().sorted()
        assertTrue("The entire table-function category must be covered", functions.size >= 87)
        val failures = mutableListOf<String>()
        val results = functions.flatMap(::cells).map { cell ->
            open(cell.sql)
            val reports = myFixture.doHighlighting(HighlightSeverity.WARNING)
            val problems = mutableListOf<String>()
            if (reports.any { it.description.orEmpty().contains("Unknown database function '${cell.function}'", ignoreCase = true) }) problems += "unknown-function"
            if (reports.any { it.toolId.toString().contains("SqlInsertValues") || it.toolId.toString().contains("SqlType") }) problems += "target-count-or-type"
            if (reports.any { it.severity == HighlightSeverity.ERROR && (it.description.orEmpty().contains("expected", true) || it.description.orEmpty().contains("unexpected", true)) }) problems += "parser"
            if (cell.position in setOf(Position.INSERT_SELECT, Position.INSERT_PARTITION)) {
                val insert = PsiTreeUtil.findChildOfType(myFixture.file, SqlInsertDmlInstruction::class.java)
                val count = insert?.targetType?.columnCount
                if (count !in setOf(0, 2)) problems += "target-not-open-or-declared"
                val star = cell.sql.lastIndexOf('*')
                if (count == 0 && star >= 0 && star in hintOffsets()) problems += "target-hint"
            }
            if (problems.isNotEmpty()) failures += "${cell.function}/${cell.position}: ${problems.joinToString()}"
            buildJsonObject {
                put("function", cell.function); put("position", cell.position.name)
                put("problems", JsonArray(problems.map(::JsonPrimitive)))
                put("details", JsonArray(reports.map { JsonPrimitive("${it.toolId}: ${it.description}") }))
            }
        }
        val directory = Files.createDirectories(Path.of("build", "table-function-sweep"))
        Files.writeString(directory.resolve("matrix.json"), buildJsonObject {
            put("ide", ApplicationInfo.getInstance().build.asString()); put("functions", functions.size)
            put("cells", JsonArray(results)); put("failures", failures.size)
        }.toString())
        assertEmpty(failures)
    }

    fun testDeclaredStructureColumnsAndArgumentCompletion() {
        val calls = listOf(
            "input('field_a UInt64, field_b String')", "null('field_a UInt64, field_b String')",
            "generateRandom('field_a UInt64, field_b String')", "format('TSV', 'field_a UInt64, field_b String', '1\\tsample')",
            "values('field_a UInt64, field_b String', (1, 'sample'))",
            "s3('https://example.invalid/object', 'TSV', 'field_a UInt64, field_b String')",
            "s3(collection_demo, structure = 'field_a UInt64, field_b String')",
            "view(SELECT 1 AS field_a, 'sample' AS field_b)",
        )
        val failures = mutableListOf<String>()
        val settings = CodeInsightSettings.getInstance()
        val previous = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            for (call in calls) {
                open("SELECT t.<caret> FROM $call AS t")
                val names = myFixture.completeBasic().orEmpty().map { it.lookupString }
                if (!names.containsAll(listOf("field_a", "field_b"))) {
                    val parsed = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java)
                    failures += "${call.substringBefore('(')}: declared columns absent; PSI=${parsed.map { it.javaClass.simpleName + "/" + it.parent.javaClass.simpleName }}"
                }
            }
            open("SELECT * FROM s3(<caret>)")
            val tables = myFixture.completeBasic().orEmpty().filter { it.lookupString in setOf("v_export", "one_column", "db1") }
            if (tables.isNotEmpty()) failures += "s3: tables offered inside arguments"
            open("SELECT * FROM cluster('cluster_demo', view(SELECT 1 AS field_a))")
            if (myFixture.doHighlighting(HighlightSeverity.WARNING).any { it.description.orEmpty().contains("Unknown database function") }) failures += "cluster/view: nested table function unresolved"
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previous
        }
        assertEmpty(failures)
    }

    fun testUnspecifiedFromColumnsAreOpen() {
        open("SELECT * FROM s3('https://example.invalid/object', 'TSV') AS t")
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java)
            .single { it.nameElement?.name == "s3" }
        val type = (call.parent as? SqlDasTypeAwareElement)?.dasType
        assertEquals("Unspecified table-function columns must remain open", 0, (type as? DasTableType)?.columnCount)
    }

    fun testArgumentCompletionDoesNotOfferTablesAcrossTheCatalog() {
        open("SELECT 1")
        val functions = ClickHouseCompletionProfile.functions(myFixture.file).filter { it.kind == FunctionKind.TABLE_VALUED }.map { it.name }.distinct()
        val failures = mutableListOf<String>()
        val settings = CodeInsightSettings.getInstance()
        val previous = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            functions.forEach { name ->
                open("SELECT * FROM $name(<caret>)")
                val tables = myFixture.completeBasic().orEmpty().filter { it.lookupString in setOf("v_export", "one_column", "db1") }
                if (tables.isNotEmpty()) failures += "$name: tables offered in arguments"
                myFixture.lookup?.hideLookup(true)
            }
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previous
        }
        assertEmpty(failures)
    }

    fun testStockSampleComparison() {
        val sample = listOf("s3", "numbers", "values", "view", "cluster", "input", "format", "merge", "dictionary")
        val records = mutableListOf<kotlinx.serialization.json.JsonObject>()
        for (stock in listOf(true, false)) {
            for (name in sample) {
                for (cell in cells(name)) {
                    open(cell.sql, stock)
                    val reports = myFixture.doHighlighting(HighlightSeverity.WARNING)
                    records += buildJsonObject {
                        put("stock", stock); put("function", name); put("position", cell.position.name)
                        put("reports", JsonArray(reports.map { JsonPrimitive("${it.toolId}: ${it.description}") }))
                    }
                }
            }
        }
        val directory = Files.createDirectories(Path.of("build", "table-function-sweep"))
        Files.writeString(directory.resolve("stock-sample.json"), JsonArray(records).toString())
        assertEquals(sample.sumOf { cells(it).size } * 2, records.size)
    }
}
