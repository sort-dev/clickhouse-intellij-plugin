package dev.sort.clickhouse.sql

import com.intellij.database.Dbms
import dev.brikk.house.sql.intellij.dialect.CHouseFamilyDialect
import dev.sort.clickhouse.ClickHouseDbms

object ClickHouseDialect : CHouseFamilyDialect("ClickHouseSQL") {
    override fun getDbms(): Dbms = ClickHouseDbms.CLICKHOUSE_BRIKK
}
