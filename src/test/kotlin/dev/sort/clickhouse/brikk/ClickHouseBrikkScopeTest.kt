package dev.sort.clickhouse.brikk

import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.intellij.relation.RelationFactsResult
import dev.brikk.house.sql.scope.RelationScope
import dev.brikk.house.sql.scope.ScopeProblem

/** ClickHouse-specific host fixtures until relation corpus v2.2 carries ClickHouse applicability. */
class ClickHouseBrikkScopeTest : ClickHouseBrikkTestCase() {
    private fun scope(sql: String): Pair<String, RelationScope> {
        val host = configure("""
            interface EventRow : Shape { val id: Long; val amount: Double? }
            @BrikkSql fun query(events: Rel<EventRow>) = Sql.clickhouse("$sql")
        """.trimIndent())
        val file = injected(host)
        val text = PipeText.of(file).text
        val facts = ClickHouseRelationProfile.providers.mapNotNull { it.facts(file) }
            .filterIsInstance<RelationFactsResult.Available>().first().facts
        return text to RelationScope.analyze(text, "clickhouse", facts)
    }

    fun testAliasHidesTheSlotWithExactQualifierRange() {
        val (text, result) = scope("SELECT events.id FROM ${'$'}events() AS e")
        val diagnostic = result.diagnostics.single { it.problem == ScopeProblem.UNKNOWN_QUALIFIER }
        assertEquals("events", text.substring(diagnostic.start, diagnostic.end))
        assertEquals(text.indexOf("events.id"), diagnostic.start)
    }

    fun testCteProjectionKeepsOnlyItsOutputColumns() {
        val (_, result) = scope("WITH picked AS (SELECT id FROM ${'$'}events()) SELECT p.id FROM picked AS p")
        assertEmpty(result.diagnostics)
        assertEquals(listOf("id"), requireNotNull(result.output).columns.map { it.name })
    }

    fun testMissingFullContractColumnHasAnExactDiagnosticRange() {
        val (text, result) = scope("SELECT e.missing FROM ${'$'}events() AS e")
        val diagnostic = result.diagnostics.single { it.problem == ScopeProblem.MISSING_COLUMN }
        assertEquals("missing", text.substring(diagnostic.start, diagnostic.end))
        assertEquals(text.indexOf("missing"), diagnostic.start)
    }

    fun testSelectFirstPipeKeepsItsProjectedOutput() {
        val (_, result) = scope("SELECT id, amount FROM ${'$'}events() |> WHERE id > 0")
        assertEmpty(result.diagnostics)
        assertEquals(listOf("id", "amount"), requireNotNull(result.output).columns.map { it.name })
    }
}
