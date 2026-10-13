package dev.sort.clickhouse.catalog

import dev.brikk.house.sql.intellij.catalog.CatalogRowSource
import dev.brikk.house.sql.intellij.catalog.Harvest
import dev.brikk.house.sql.intellij.catalog.LiveCatalogHarvester
import dev.brikk.house.sql.intellij.catalog.LiveFunction
import dev.brikk.house.sql.intellij.catalog.LiveInventory
import dev.brikk.house.sql.metadata.FunctionDef
import dev.brikk.house.sql.metadata.FunctionKind

internal object ClickHouseCatalogHarvester : LiveCatalogHarvester {
    const val SQL_VERSION = "SELECT version()"
    const val SQL_FUNCTIONS = "SELECT name, is_aggregate, origin FROM system.functions"
    const val SQL_TABLE_FUNCTIONS = "SELECT name FROM system.table_functions"

    override fun harvest(rows: CatalogRowSource): Harvest? {
        var version: String? = null
        rows.forEachRow(SQL_VERSION) { version = it.string(1)?.takeIf(String::isNotBlank) }
        val functions = linkedMapOf<Pair<String, FunctionKind>, LiveFunction>()
        var complete = true
        rows.forEachRow(SQL_FUNCTIONS) { row ->
            val name = row.string(1)?.takeIf(NAME::matches)
            val kind = when (row.string(2)) {
                "0" -> FunctionKind.SCALAR
                "1" -> FunctionKind.AGGREGATE
                else -> null
            }
            if (name == null || kind == null) {
                complete = false
            } else if (!name.startsWith("__") || row.string(3) == "SQLUserDefined") {
                functions[name to kind] = LiveFunction(FunctionDef(name, kind))
            }
        }
        if (version == null || functions.isEmpty()) return null
        var tables = 0
        rows.forEachRow(SQL_TABLE_FUNCTIONS) { row ->
            val name = row.string(1)?.takeIf(NAME::matches)
            if (name == null) complete = false
            else if (!name.startsWith("__")) {
                functions[name to FunctionKind.TABLE_VALUED] = LiveFunction(FunctionDef(name, FunctionKind.TABLE_VALUED))
                tables++
            }
        }
        if (!complete || tables == 0) return null
        return Harvest(LiveInventory(version, functions.values.toList(), signatures = false))
    }

    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")
}
