package dev.sort.clickhouse

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.psi.PsiManager
import com.intellij.sql.dialects.BuiltinFunction
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.metadata.FunctionPresentation
import dev.brikk.house.sql.metadata.FunctionKind
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
        assertEmpty(complete("SELECT * FROM toDa<caret>").filter { (it.`object` as? FunctionPresentation)?.kind == FunctionKind.SCALAR })
    }

    fun testTableFunctionsInsertFromMetadataInFromPosition() {
        for (name in listOf("numbers", "s3", "file", "url", "remote")) {
            assertEquals("SELECT * FROM $name(<caret>)", insertFunction("SELECT * FROM $name<caret>", name))
        }
    }

    fun testTableFunctionsDoNotLeakIntoSelectExpressions() {
        assertEmpty(complete("SELECT num<caret>").filter { (it.`object` as? FunctionPresentation)?.name == "numbers" })
    }

    fun testStockAndMetadataToDateTimeProduceOneCandidate() {
        val items = complete("SELECT toDateTi<caret>").filter { it.lookupString.equals("toDateTime", true) }
        assertEquals(listOf("toDateTime"), items.map { it.lookupString })
        assertTrue(items.single().`object` is FunctionPresentation)
    }

    fun testFunctionsAreAbsentInsideStringsCommentsAndInlineData() {
        for (sql in listOf(
            "SELECT 'toDa<caret>'", "SELECT 1 /* toDa<caret> */", "SELECT 1 -- toDa<caret>",
            "FROM events |> SELECT 'toDa<caret>'",
            "INSERT INTO events FORMAT JSONEachRow\n{\"message\":\"toDa<caret>\"}\n;",
            "INSERT INTO events FORMAT CSV\n1,toDa<caret>\n;",
        )) {
            assertEmpty(sql, complete(sql).filter { it.`object` is FunctionPresentation || it.`object` is BuiltinFunction.Prototype })
        }
    }

    fun testPipeQualifierUsesSourceTokensEvenWithoutReferencePsi() {
        assertEmpty(complete("FROM events |> WHERE events.toDa<caret>")
            .filter { it.`object` is FunctionPresentation || it.`object` is BuiltinFunction.Prototype })
    }

    fun testInternalFunctionsAreNotInTheMetadataCandidates() {
        assertEmpty(complete("SELECT __<caret>").filter { (it.`object` as? FunctionPresentation)?.name?.startsWith("__") == true })
    }
}
