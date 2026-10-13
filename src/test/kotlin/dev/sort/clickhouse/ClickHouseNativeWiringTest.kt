package dev.sort.clickhouse

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.validation.ClickHouseNativeErrorAnnotator

class ClickHouseNativeWiringTest : ClickHousePlatformTestCase() {
    fun testRegisteredAnnotatorAndPsiFilterShowPositionedLocalErrorsWithoutPlatformDuplicates() {
        val sql = "SELECT FROM WHERE = 1"
        val file = myFixture.configureByText("registered-native-errors.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        val errors = myFixture.doHighlighting(HighlightSeverity.ERROR)
        val start = sql.indexOf("WHERE")
        val error = errors.single { it.startOffset == start && it.description.orEmpty().startsWith("ClickHouse (brikk-sql):") }
        assertEquals(TextRange(start, start + "WHERE".length), TextRange(error.startOffset, error.endOffset))
        assertTrue(errors.toString(), errors.all { it.toolId == ClickHouseNativeErrorAnnotator::class.java })
    }

    fun testOneCombinedErrorPerMalformedStatement() {
        val sql = "SELECT FROM WHERE = 1"
        val file = myFixture.configureByText("single-combined-error.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        assertEquals(1, myFixture.doHighlighting(HighlightSeverity.ERROR).size)
    }
}
