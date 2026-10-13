package dev.sort.clickhouse.brikk

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.codeInsight.lookup.Lookup
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.intellij.relation.RelationFactsResult
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.Nullability

class ClickHouseBrikkRelationTest : ClickHouseBrikkTestCase() {
    private val rows = """
        interface EventRow : Shape { val id: Long; val tenant: String; val amount: Double? }
        @BrikkTrait interface EventTrait : Partial { val id: Long; val tenant: String }
    """.trimIndent()

    fun testSlotsResolveAndNavigateToKotlinColumns() {
        val host = configure("$rows\n@BrikkSql fun query(events: Rel<EventRow>) = Sql.clickhouse(\"SELECT e.amount FROM ${'$'}events() AS e\")")
        val file = injected(host)
        val reference = PsiTreeUtil.findChildrenOfType(file, SqlReferenceExpression::class.java).first { it.text == "e.amount" }
        val resolved = requireNotNull(reference.reference.resolve())
        val navigation = resolved.navigationElement
        assertTrue(navigation.text, navigation.text.contains("amount"))
        assertEquals("BrikkSample.kt", navigation.containingFile.name)
    }

    fun testPipeKeepsContractColumnsAndNullability() {
        val host = configure("$rows\n@BrikkSql fun query(events: Rel<EventRow>) = Sql.clickhouse(\"FROM ${'$'}events() |> WHERE id > 0 |> SELECT id, amount\")")
        val file = injected(host)
        val facts = ClickHouseRelationProfile.providers.mapNotNull { it.facts(file) }.filterIsInstance<RelationFactsResult.Available>().first().facts
        val scope = RelationScope.analyze(PipeText.of(file).text, "clickhouse", facts)
        val columns = requireNotNull(scope.output).columns
        assertContainsElements(columns.map { it.name }, "id", "amount")
        assertEquals(Nullability.NULLABLE, columns.first { it.name == "amount" }.nullability)
    }

    fun testCompletionInsertsAContractColumnExactlyOnce() {
        configure("$rows\n@BrikkSql fun query(events: Rel<EventRow>) = Sql.clickhouse(\"SELECT e.am<caret> FROM ${'$'}events() AS e\")")
        val items = myFixture.completeBasic().orEmpty().filter { it.lookupString == "amount" }
        assertEquals(items.toString(), 1, items.size)
        myFixture.lookup.currentItem = items.single()
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertTrue(myFixture.editor.document.text, myFixture.editor.document.text.contains("SELECT e.amount FROM"))
    }
}
