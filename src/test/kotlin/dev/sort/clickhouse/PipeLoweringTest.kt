package dev.sort.clickhouse

import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.brikk.house.sql.intellij.pipe.pipeStagePhrases
import dev.brikk.house.sql.shape.PipeLowering
import dev.brikk.house.sql.shape.SqlFragment
import dev.brikk.house.sql.shape.lowerForExecution
import junit.framework.TestCase

class PipeLoweringTest : TestCase() {
    fun testCoreStageCoverage() {
        for (phrase in pipeStagePhrases("clickhouse")) {
            val input = "FROM events |> $phrase ${stageTail(phrase)}"
            val lowered = lowerForExecution(input, "clickhouse")
            assertTrue("$input: $lowered", lowered is PipeLowering.Ok)
            lowered as PipeLowering.Ok
            assertTrue("$input: ${lowered.blockers}", lowered.blockers.isEmpty())
            assertFalse(input, SqlFragment(lowered.sql, "clickhouse").containsPipe)
            assertEquals(input, 1, Dialects.forName("clickhouse").parse(lowered.sql).filterNotNull().size)
        }
    }

    fun testUnsupportedStagesRefuseOrBlockExecution() {
        for (stage in listOf("CALL", "RENAME", "NATURAL JOIN", "STRAIGHT_JOIN", "PIVOT", "UNPIVOT")) {
            val tail = if (stage == "STRAIGHT_JOIN") "events AS other ON events.id = other.id" else stageTail(stage)
            when (val lowered = lowerForExecution("FROM events |> $stage $tail", "clickhouse")) {
                is PipeLowering.Refused -> Unit
                is PipeLowering.Ok -> assertTrue(stage, lowered.blockers.isNotEmpty())
                PipeLowering.NotPipe -> fail("$stage must not pass through as SQL")
            }
        }
    }

    fun testSelectFirstAndNestedOnlyLower() {
        for (input in listOf(
            "SELECT id FROM events |> WHERE id > 1 |> LIMIT 2",
            "SELECT * FROM (FROM events |> WHERE id > 1) AS filtered",
        )) {
            val lowered = lowerForExecution(input, "clickhouse")
            assertTrue("$input: $lowered", lowered is PipeLowering.Ok)
            lowered as PipeLowering.Ok
            assertTrue(lowered.blockers.toString(), lowered.blockers.isEmpty())
            assertFalse(SqlFragment(lowered.sql, "clickhouse").containsPipe)
        }
    }

    fun testSelectFirstStageCanRunAndIncompleteOrMultipleStatementsRefuse() {
        assertTrue(lowerForExecution("SELECT 1 AS id", "clickhouse", allowFirstStage = true) is PipeLowering.Ok)
        for (input in listOf("FROM events |> WHERE (id > 1", "FROM events |> SELECT id; SELECT 2")) {
            assertTrue(input, lowerForExecution(input, "clickhouse") is PipeLowering.Refused)
        }
        assertEquals(PipeLowering.NotPipe, lowerForExecution("SELECT 1", "clickhouse"))
        assertFalse(SqlScriptSnapshot.parse("SELECT '|>'", "clickhouse").statements.any { it.hasPipe })
    }

    private fun stageTail(phrase: String): String = when {
        phrase.endsWith("JOIN") -> when {
            phrase == "ARRAY JOIN" -> "tags AS tag"
            phrase.startsWith("CROSS") || phrase.startsWith("NATURAL") -> "events AS other"
            else -> "events AS other ON events.id = other.id"
        }
        phrase.startsWith("UNION") || phrase.startsWith("INTERSECT") || phrase.startsWith("EXCEPT") ->
            "(SELECT * FROM events)"
        else -> when (phrase) {
            "AGGREGATE" -> "sum(value) AS total GROUP BY id"
            "AS" -> "e"
            "CALL" -> "some_table_function(1)"
            "DISTINCT" -> ""
            "DROP" -> "value"
            "EXTEND" -> "id + 1 AS next_id"
            "LIMIT" -> "3"
            "OFFSET" -> "2"
            "ORDER BY" -> "id"
            "PIVOT" -> "(sum(value) FOR id IN (1, 2))"
            "RENAME" -> "value AS renamed"
            "SELECT" -> "id"
            "SET" -> "value = value + 1"
            "TABLESAMPLE" -> "(10 PERCENT)"
            "UNPIVOT" -> "(v FOR k IN (id, value))"
            "WHERE" -> "id > 0"
            "WINDOW" -> "sum(value) OVER () AS total"
            else -> error("Add a ClickHouse fixture for $phrase")
        }
    }
}
