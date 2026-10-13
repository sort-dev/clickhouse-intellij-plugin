package dev.sort.clickhouse

import com.intellij.database.dataSource.DatabaseDriverManager
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseDriverTest : ClickHousePlatformTestCase() {
    fun testDocumentedFacadeUsesTheIdeManagedStockArtifact() {
        val manager = DatabaseDriverManager.getInstance()
        val stock = requireNotNull(manager.getDriver("clickhouse"))
        val own = requireNotNull(manager.getDriver("clickhouse-sortdev"))
        assertEquals("com.clickhouse.jdbc.ClickHouseDriver", own.driverClass)
        assertNotEmpty(stock.artifacts)
        assertEquals(stock.artifacts.map { it.id }, own.artifacts.map { it.id })
        assertSame(ClickHouseDbms.CLICKHOUSE_BRIKK, own.forcedDbms)
        assertEquals(ClickHouseDialect.id, own.sqlDialect)
        assertTrue(own.matchesUrl(stock.sampleUrl))
    }
}
