package dev.sort.clickhouse

import dev.brikk.house.sql.intellij.catalog.CatalogRow
import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import dev.brikk.house.sql.intellij.catalog.SessionFacts
import dev.brikk.house.sql.metadata.FunctionKind
import dev.sort.clickhouse.catalog.ClickHouseCatalogHarvester
import junit.framework.TestCase

class ClickHouseCatalogHarvesterTest : TestCase() {
    private fun rows(functions: List<List<String?>> = listOf(
        listOf("toDateTime", "0", "System"), listOf("sum", "1", "System"),
        listOf("__internal", "0", "System"), listOf("__user_function", "0", "SQLUserDefined"),
    ), tables: List<List<String?>> = listOf(listOf("numbers"), listOf("s3"))): CatalogRowSource = CatalogRowSource { sql, read ->
        val values = when (sql) {
            ClickHouseCatalogHarvester.SQL_VERSION -> listOf(listOf("26.9.17.8"))
            ClickHouseCatalogHarvester.SQL_FUNCTIONS -> functions
            ClickHouseCatalogHarvester.SQL_TABLE_FUNCTIONS -> tables
            else -> error("Unexpected catalog query: $sql")
        }
        values.forEach { value -> read(CatalogRow { column -> value.getOrNull(column - 1) }) }
    }

    fun testNamesKindsAndTableFunctionsAreCopiedWithoutInventingSignatures() {
        val harvest = requireNotNull(ClickHouseCatalogHarvester.harvest(rows()))
        val defs = harvest.shared.functions.map { it.def }
        assertEquals(listOf(
            "toDateTime" to FunctionKind.SCALAR, "sum" to FunctionKind.AGGREGATE,
            "__user_function" to FunctionKind.SCALAR, "numbers" to FunctionKind.TABLE_VALUED, "s3" to FunctionKind.TABLE_VALUED,
        ), defs.map { it.name to it.kind })
        assertTrue(defs.all { it.overloads.isEmpty() })
        assertFalse(harvest.shared.signatures)
        assertEquals(SessionFacts.NONE, harvest.session)
    }

    fun testIncompleteInventoriesAreNeverReportedAsComplete() {
        assertNull(ClickHouseCatalogHarvester.harvest(rows(functions = emptyList())))
        assertNull(ClickHouseCatalogHarvester.harvest(rows(tables = emptyList())))
        assertNull(ClickHouseCatalogHarvester.harvest(rows(functions = listOf(listOf("sum", null, "System")))))
    }
}
