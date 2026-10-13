package dev.sort.clickhouse

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiManager
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseCompletionTest : ClickHousePlatformTestCase() {
    private fun complete(sql: String): List<LookupElement> {
        val file = myFixture.configureByText("completion.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        assertSame(ClickHouseDialect, requireNotNull(PsiManager.getInstance(project).findFile(file.virtualFile)).language)
        val settings = CodeInsightSettings.getInstance()
        val previous = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            return myFixture.completeBasic()?.toList().orEmpty()
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = previous
        }
    }

    private fun result(): String {
        val text = myFixture.editor.document.text
        val offset = myFixture.editor.caretModel.offset
        return text.substring(0, offset) + "<caret>" + text.substring(offset)
    }

    private fun insertFunction(sql: String, name: String, char: Char = Lookup.NORMAL_SELECT_CHAR): String {
        val item = complete(sql).single { (it.`object` as? FunctionPresentation)?.name == name }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(char)
        return result()
    }

    fun testCamelCaseFunctionInsertionUsesCatalogSpelling() {
        assertEquals("SELECT toDateTime(<caret>)", insertFunction("SELECT todateTi<caret>", "toDateTime"))
        assertEquals("SELECT toYear(<caret>)", insertFunction("SELECT toye<caret>", "toYear"))
    }

    fun testExistingParenthesesAndTypedParenthesisAreNotDuplicated() {
        assertEquals("SELECT toDateTime(<caret>ts)", insertFunction("SELECT todateti<caret>(ts)", "toDateTime"))
        assertEquals("SELECT toDateTime(<caret>)", insertFunction("SELECT todateti<caret>", "toDateTime", '('))
    }

    fun testAggregateWithMissingSignaturesStillInsertsACall() {
        assertEquals("SELECT sum(<caret>)", insertFunction("SELECT su<caret>", "sum"))
    }

    fun testKeywordInsertionIsUppercase() {
        val item = complete("SELECT 1; sel<caret>").single { it.lookupString.equals("SELECT", true) }
        myFixture.lookup.currentItem = item
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("SELECT 1; SELECT <caret>", result())
    }

    fun testMetadataFunctionsAreAbsentAfterAQualifierOrDigit() {
        for (sql in listOf("SELECT events.toDa<caret>", "SELECT 1<caret>")) {
            assertEmpty(sql, complete(sql).filter { it.`object` is FunctionPresentation })
        }
    }

    fun testScalarFunctionsAreNotOfferedAsTables() {
        assertEmpty(complete("SELECT * FROM toDa<caret>").filter { it.`object` is FunctionPresentation })
    }
}
