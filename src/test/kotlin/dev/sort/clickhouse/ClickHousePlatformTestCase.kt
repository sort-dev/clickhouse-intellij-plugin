package dev.sort.clickhouse

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.testing.BackgroundErrors

abstract class ClickHousePlatformTestCase : BasePlatformTestCase() {
    private var backgroundErrors: BackgroundErrors? = null

    override fun setUp() {
        super.setUp()
        backgroundErrors = BackgroundErrors(testRootDisposable)
    }

    override fun tearDown() {
        try {
            super.tearDown()
        } finally {
            backgroundErrors?.assertNone()
        }
    }
}
