package dev.sort.clickhouse

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.PlatformTestUtil
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.pipe.ClickHousePipeSettings
import dev.sort.clickhouse.sql.ClickHouseDialect
import java.util.UUID

abstract class ClickHousePipeTestCase : ClickHousePlatformTestCase() {
    protected val settings get() = project.service<ClickHousePipeSettings>()
    private var previousFlag: String? = null
    private var previousEnabled = false

    override fun setUp() {
        super.setUp()
        previousFlag = System.getProperty(ClickHousePipeDialect.vetoProperty)
        previousEnabled = settings.enabled
        System.clearProperty(ClickHousePipeDialect.vetoProperty)
        settings.enabled = true
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
    }

    override fun tearDown() {
        try {
            settings.enabled = previousEnabled
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val flag = previousFlag
            if (flag == null) System.clearProperty(ClickHousePipeDialect.vetoProperty)
            else System.setProperty(ClickHousePipeDialect.vetoProperty, flag)
        } finally {
            super.tearDown()
        }
    }

    protected fun open(sql: String): Editor {
        val file = myFixture.configureByText("clickhouse-pipe-${UUID.randomUUID()}.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        return myFixture.editor
    }
}
