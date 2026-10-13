package dev.sort.clickhouse

import com.intellij.database.Dbms

object ClickHouseDbms {
    @JvmField
    val CLICKHOUSE_BRIKK: Dbms = Dbms.create(
        "CLICKHOUSE_BRIKK", "ClickHouse (sort.dev)", { Dbms.CLICKHOUSE.icon }, Dbms.defaultPattern("clickhouse"),
    )
}
