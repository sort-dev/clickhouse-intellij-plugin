package dev.sort.clickhouse

import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.parseParameterOccurrences
import junit.framework.TestCase

class QueryParameterTest : TestCase() {
    fun testTypedAndIdeParametersStayDistinct() {
        val sql = "SELECT {id:UInt64}, :other FROM {table:Identifier}"
        val parameters = parseParameterOccurrences(sql, "clickhouse")
        assertEquals(listOf("{id:UInt64}", ":other", "{table:Identifier}"), parameters.map { sql.substring(it.range.start, it.range.end) })
        assertEquals(listOf("id", "other", "table"), parameters.map { it.name })
        assertEquals(listOf(ParamStyle.NAMED_TYPED, ParamStyle.NAMED_COLON, ParamStyle.NAMED_TYPED), parameters.map { it.style })
    }
}
