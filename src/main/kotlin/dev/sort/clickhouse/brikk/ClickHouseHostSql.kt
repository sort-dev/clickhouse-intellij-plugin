package dev.sort.clickhouse.brikk

import dev.brikk.house.sql.intellij.host.HostSqlChannel
import dev.brikk.house.sql.intellij.host.HostSqlPlaceholderErrorFilter

object ClickHouseHostSql : HostSqlChannel("dev.sort.clickhouse.brikk")
class ClickHousePlaceholderErrorFilter : HostSqlPlaceholderErrorFilter(ClickHouseHostSql)
