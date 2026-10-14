package dev.sort.clickhouse

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.clickhouse.CHouseDialect

class ClickHouseKeywordResolveTest : ClickHouseTableFunctionTestCase() {
    fun testKeywordFormsDoNotResolveAsUnknownFunctions() {
        val problems = mutableListOf<String>()
        for (sql in listOf(
            "SELECT 1 WHERE EXISTS (SELECT 1)",
            "SELECT 1 WHERE NOT EXISTS (SELECT 1)",
            "SELECT a.value FROM db1.one_column a WHERE EXISTS (SELECT 1 FROM db1.one_column b WHERE b.value = a.value)",
            "SELECT a.value FROM db1.one_column a WHERE NOT EXISTS (SELECT 1 FROM db1.one_column b WHERE b.value = a.value)",
            "SELECT 1 WHERE 1 IN (SELECT 1)",
            "SELECT 1 WHERE 1 NOT IN (SELECT 1)",
            "SELECT a.value FROM db1.one_column a WHERE a.value IN (SELECT b.value FROM db1.one_column b WHERE b.value = a.value)",
            "SELECT a.value FROM db1.one_column a WHERE a.value NOT IN (SELECT b.value FROM db1.one_column b WHERE b.value = a.value)",
            "SELECT 1 WHERE 1 = ANY (SELECT 1)",
            "SELECT 1 WHERE 1 = ALL (SELECT 1)",
            "SELECT a.value FROM db1.one_column a WHERE a.value = ANY (SELECT b.value FROM db1.one_column b WHERE b.value = a.value)",
            "SELECT a.value FROM db1.one_column a WHERE a.value = ALL (SELECT b.value FROM db1.one_column b WHERE b.value = a.value)",
            "SELECT CASE WHEN 1 = 1 THEN 1 ELSE 0 END",
            "SELECT CASE 1 WHEN 1 THEN 1 ELSE 0 END",
            "SELECT CAST(1 AS UInt64)",
            "SELECT INTERVAL 1 DAY",
            "SELECT EXTRACT(YEAR FROM toDate('2026-01-01'))",
            "SELECT TRIM(BOTH 'x' FROM 'xvaluex')",
            "SELECT POSITION('a' IN 'value')",
            "SELECT SUBSTRING('value' FROM 2 FOR 3)",
        )) {
            open(sql)
            val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
            problems += errors.map { "$sql: ${it.startOffset}..${it.endOffset} ${it.description}" }
        }
        assertEmpty(problems)
    }

    fun testPredicatesAcceptCaseCommentsCtesAndComparisonOperators() {
        for (sql in listOf(
            "select 1 where exists (select 1)",
            "SELECT 1 WHERE eXiStS (/* query */ SELECT 1)",
            "SELECT 1 WHERE EXISTS (WITH q AS (SELECT 1 AS n) SELECT n FROM q)",
            "SELECT 1 WHERE EXISTS ((SELECT 1))",
        ) + listOf("=", "!=", "<>", "<", "<=", ">", ">=").map { "SELECT 1 WHERE 1 $it all (SELECT 1)" }) {
            open(sql)
            assertEmpty(sql, myFixture.doHighlighting(HighlightSeverity.ERROR))
            val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).first()
            val symbol = call.nameElement?.resolveSymbol() as? BuiltinFunction
            assertNotNull(sql, symbol)
            assertTrue(sql, requireNotNull(symbol).prototypes.isEmpty())
        }
    }

    fun testRealUnknownAndNonKeywordCallsStayRedAtTheirNameRanges() {
        for (sql in listOf(
            "SELECT nonexistent_scalar_function(1)",
            "SELECT nonexistent_scalar_function((SELECT 1))",
            "SELECT EXISTS(1)",
            "SELECT ALL(1)",
            "SELECT ALL(SELECT 1)",
            "SELECT 1 WHERE 1 + ALL(SELECT 1)",
            "SELECT \"EXISTS\"(SELECT 1)",
            "SELECT `ALL`(SELECT 1)",
        )) {
            open(sql)
            val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
            val name = requireNotNull(PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single().nameElement)
            assertNull(sql, name.resolveSymbol())
            val range = requireNotNull(name.identifier).textRange
            assertTrue("$sql: $errors", errors.any {
                it.inspectionToolId == "SqlResolveInspection" &&
                    it.startOffset == range.startOffset && it.endOffset == range.endOffset
            })
        }
    }

    fun testQualifiedPredicateNamesKeepStockResolveReports() {
        for (sql in listOf("SELECT db1.EXISTS(SELECT 1)", "SELECT 1 WHERE 1 = db1.ALL(SELECT 1)")) {
            fun reports(stock: Boolean): List<Pair<IntRange, String?>> {
                open(sql, stock)
                val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
                val name = requireNotNull(PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single().nameElement)
                assertNull(sql, name.resolveSymbol())
                return errors.filter { it.inspectionToolId == "SqlResolveInspection" }
                    .map { (it.startOffset until it.endOffset) to it.description }
            }
            val stock = reports(true)
            assertTrue(sql, stock.isNotEmpty())
            assertEquals(sql, stock, reports(false))
        }
    }

    fun testUnknownFunctionsInsidePredicateQueriesStayRed() {
        val unknown = "nonexistent_scalar_function"
        for (sql in listOf(
            "SELECT 1 WHERE EXISTS (SELECT $unknown(1))",
            "SELECT 1 WHERE NOT EXISTS (SELECT $unknown(1))",
            "SELECT 1 WHERE 1 = ALL (SELECT $unknown(1))",
            "SELECT 1 WHERE 1 = ANY (SELECT $unknown(1))",
        )) {
            open(sql)
            val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
            val start = sql.indexOf(unknown)
            assertTrue("$sql: $errors", errors.any {
                it.inspectionToolId == "SqlResolveInspection" &&
                    it.startOffset == start && it.endOffset == start + unknown.length
            })
        }
    }

    fun testOrdinaryAnyAggregateKeepsItsStockDefinition() {
        open("SELECT any(value) FROM db1.one_column")
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single()
        assertSame(requireNotNull(CHouseDialect.INSTANCE.supportedFunctions.get("any")), call.functionDefinition)
    }
}
