package dev.sort.clickhouse

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.dialects.clickhouse.CHouseDialect

/** Minimal public-builtin shapes; no identifiers or literals from private input. */
class ClickHouseScalarMetadataResolveTest : ClickHouseTableFunctionTestCase() {
    fun testBundledScalarNamesResolveWithoutInventedSignatures() {
        for (sql in listOf("SELECT toJSONString(1)", "SELECT formatReadableQuantity(1)", "SELECT row_number() OVER ()", "SELECT rank() OVER ()")) {
            open(sql)
            assertEmpty(sql, myFixture.doHighlighting(HighlightSeverity.ERROR))
            val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).first()
            assertNotNull(call.nameElement?.resolveSymbol())
        }
    }

    fun testUnknownFunctionsStillReport() {
        open("SELECT nonexistent_scalar_function(1)")
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).any {
            it.description.orEmpty().contains("nonexistent_scalar_function")
        })
    }

    fun testStockBuiltinSignaturesRemainThePlatformDefinitions() {
        open("SELECT toDateTime('2026-01-01')")
        val call = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlFunctionCallExpression::class.java).single()
        val stock = requireNotNull(CHouseDialect.INSTANCE.supportedFunctions.get("toDateTime"))
        assertSame(stock, call.functionDefinition)
        assertTrue(stock.prototypes.isNotEmpty())
    }
}
