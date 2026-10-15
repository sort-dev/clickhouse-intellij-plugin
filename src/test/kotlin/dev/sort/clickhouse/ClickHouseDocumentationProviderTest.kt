package dev.sort.clickhouse

import dev.brikk.house.sql.intellij.testing.DocumentationProviderContract
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseDocumentationProviderTest : ClickHousePlatformTestCase() {
    fun testEngineDocumentationContract() {
        DocumentationProviderContract(myFixture, ClickHouseDialect, testRootDisposable)
            .check("CREATE <caret>TABLE t (x UInt8) ENGINE = Memory", "clickhouse.com", table = "orders")
    }
}
