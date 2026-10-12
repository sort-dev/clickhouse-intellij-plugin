package dev.sort.clickhouse

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.psi.SqlStatement
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.parser.SqlScriptSnapshot

class StockBoundaryTest : ClickHousePlatformTestCase() {
    fun testCoreBoundariesMatchCorpus() {
        for (case in boundaryCorpus) {
            val snapshot = SqlScriptSnapshot.parse(case.sql, "clickhouse")
            val ranges = snapshot.statements.filter { it.hasSql }
            assertEquals(case.name, case.statements, ranges.map { case.sql.substring(it.start, it.end).trim() })
            assertTrue(case.name, ranges.all { it.boundaryError == null })
            var start = 0
            for ((index, statement) in case.statements.withIndex()) {
                val end = case.sql.indexOf(statement, start) + statement.length
                assertEquals(case.name, TextRange(start, end), ranges[index].let { TextRange(it.start, it.end) })
                for (offset in start until end) assertEquals(case.name, end, snapshot.at(offset)?.end)
                start = end
            }
        }
    }

    fun testStockBoundariesWithExplicitKnownFailures() {
        val failures = buildList {
            for ((index, case) in boundaryCorpus.withIndex()) {
                val configured = myFixture.configureByText("boundary-$index.sql", case.sql)
                setSqlDialectMapping(project, configured.virtualFile, CHouseDialect.INSTANCE)
                val file = requireNotNull(PsiManager.getInstance(project).findFile(configured.virtualFile))
                val statements = PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java)
                    .filter { PsiTreeUtil.getParentOfType(it, SqlStatement::class.java) == null }
                val stock = statements.map { it.text }
                val expected = when (case.name) {
                    "owner multiline partitioned s3 insert" -> listOf(
                        case.sql.substringBefore("\nSELECT *"),
                        case.sql.substring(case.sql.indexOf("SELECT *")).removeSuffix(";"),
                    )
                    // Stock loses the INSERT's SELECT when this comment separates the clauses.
                    "multiline insert with comments" -> listOf(
                        case.sql.substringBefore("\n/* SELECT").trimEnd(),
                        case.sql.substring(case.sql.indexOf("SELECT * FROM logs")).removeSuffix(";"),
                    )
                    // Stock treats everything after JSONEachRow as data, including the next SELECT.
                    "json data tail", "json followed by select" -> listOf(case.sql)
                    else -> case.statements.map { it.removeSuffix(";") }
                }
                if (stock != expected) {
                    add("${case.name}:\nexpected=$expected\nstock=$stock")
                } else {
                    var from = 0
                    for ((part, statement) in expected.zip(statements)) {
                        val start = case.sql.indexOf(part, from)
                        assertEquals(case.name, TextRange(start, start + part.length), statement.textRange)
                        from = start + part.length
                    }
                }
            }
        }
        assertTrue(failures.joinToString("\n\n"), failures.isEmpty())
    }
}
