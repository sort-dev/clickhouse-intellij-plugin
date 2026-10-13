package dev.sort.clickhouse

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlStatement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.UiInterceptors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHousePartitionParameterProbeTest : ClickHousePlatformTestCase() {
    fun testMaskedPartitionBindIsPromptedInOneLenientStatement() {
        val sql = "INSERT INTO FUNCTION null('id UInt64') PARTITION BY toString(:partition) SELECT 1 AS id;"
        val file = myFixture.configureByText("partition-bind.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        val statement = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlStatement::class.java).single()
        assertEquals(SqlCompositeElementTypes.SQL_STATEMENT, statement.node.elementType)
        assertEquals(sql.removeSuffix(";"), statement.text)
        RecordingConsole(project, file.virtualFile, "clickhouse-sortdev").use { recording ->
            val model = recording.console.scriptModel
            assertEquals(listOf("partition"), model.parameters().map { it.name() }.toList())
            var prompted = false
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    prompted = true
                    ShowSqlParametersPanelAction.getStorage(recording.console).putValue("partition", "'partition-1'")
                    component.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            try {
                assertTrue(recording.console.beforeExecuteQueries(model))
                assertTrue("The masked bind was not prompted", prompted)
                assertEmpty(recording.requests)
            } finally {
                UiInterceptors.clear()
            }
        }
    }
}
