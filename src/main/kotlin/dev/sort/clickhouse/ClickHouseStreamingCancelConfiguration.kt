package dev.sort.clickhouse

import dev.brikk.house.sql.intellij.StreamingCancelConfiguration
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver

class ClickHouseStreamingCancelConfiguration : StreamingCancelConfiguration(DialectDataSourceResolver(ClickHouseDbms.CLICKHOUSE_BRIKK))
