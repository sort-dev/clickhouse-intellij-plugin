package dev.sort.clickhouse

import dev.sort.clickhouse.validation.maskClickHouseIdeBinds
import junit.framework.TestCase

class ClickHouseVerificationTextTest : TestCase() {
    fun testOnlyIdeBindsAreMaskedWithoutChangingOffsets() {
        val sql = "SELECT :name, ?, {name:UInt64}, {table:Identifier}, ':literal ?', 1 -- :comment ?\r\nFROM events"
        val masked = maskClickHouseIdeBinds(sql)
        assertEquals(sql.length, masked.length)
        assertEquals(sql.replace(":name,", "0    ,").replace(", ?,", ", 0,"), masked)
    }

    fun testClickHouseTernarySyntaxIsNotAnIdeBind() {
        val sql = "SELECT flag?a:b, flag ? 1 : 0 FROM events"
        assertEquals(sql, maskClickHouseIdeBinds(sql))
    }
}
