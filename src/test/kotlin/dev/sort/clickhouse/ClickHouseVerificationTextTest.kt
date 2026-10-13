package dev.sort.clickhouse

import dev.sort.clickhouse.validation.maskClickHouseIdeBinds
import junit.framework.TestCase

class ClickHouseVerificationTextTest : TestCase() {
    fun testOnlyIdeBindsAreMaskedWithoutChangingOffsets() {
        val sql = "SELECT :name, ?, {name:UInt64}, {table:Identifier}, ':literal ?', 1 -- :comment ?\r\nFROM events"
        val masked = requireNotNull(maskClickHouseIdeBinds(sql))
        assertEquals(sql.length, masked.length)
        assertEquals(sql.replace(":name,", "0    ,").replace(", ?,", ", 0,"), masked)
    }

    fun testClickHouseTernarySyntaxIsNotAnIdeBind() {
        val sql = "SELECT flag?a:b, flag ? 1 : 0 FROM events"
        assertEquals(sql, maskClickHouseIdeBinds(sql))
    }

    fun testTernarySyntaxInPartitionedInsertRemainsUnchanged() {
        val sql = "INSERT INTO FUNCTION null('id UInt64, ts DateTime') PARTITION BY toYYYYMM(ts) (id, ts) SELECT flag?a:b AS id, ts FROM events;"
        assertEquals(sql, maskClickHouseIdeBinds(sql))
    }

    fun testPartitionedInsertPositionalBindsAreMasked() {
        val sql = "INSERT INTO FUNCTION null('id UInt64, ts DateTime') PARTITION BY toYYYYMM(ts) (id, ts) SELECT ?, ts FROM events;"
        assertEquals(sql.replace("SELECT ?", "SELECT 0"), maskClickHouseIdeBinds(sql))
    }

    fun testQuestionCastWithoutScannerProofWithholdsVerdict() {
        assertNull(maskClickHouseIdeBinds("SELECT ?::UInt64"))
    }

    fun testMixedTernaryAndIdeBindsOnlyMaskTheBinds() {
        assertEquals("SELECT x ? y : z", maskClickHouseIdeBinds("SELECT x ? y : z"))
        assertEquals("SELECT x ? 0  : 0", maskClickHouseIdeBinds("SELECT x ? :p : ?"))
    }

    fun testAmbiguousAndUnparseableTextGetNoVerificationText() {
        for (sql in listOf(
            "SELECT x ?",
            "SELECT x ?:y",
            "SELECT 'unfinished ? :name",
            "SELECT FROM WHERE ? +;",
            "SELECT FROM WHERE :name +;",
        )) {
            val masked = maskClickHouseIdeBinds(sql)
            assertNull("$sql -> $masked", masked)
        }
    }
}
