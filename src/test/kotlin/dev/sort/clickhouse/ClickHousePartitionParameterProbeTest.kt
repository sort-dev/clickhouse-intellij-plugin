package dev.sort.clickhouse

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.UiInterceptors
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHousePartitionParameterProbeTest : ClickHousePlatformTestCase() {
    fun testKnownMaskedPartitionBindIsNotPrompted() {
        val sql = "INSERT INTO FUNCTION null('id UInt64') PARTITION BY toString(:partition) SELECT 1 AS id;"
        val file = myFixture.configureByText("partition-bind.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        val mask = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiComment::class.java)
            .single { it.text.startsWith("PARTITION") }
        assertTrue(mask.text.contains(":partition"))
        RecordingConsole(project, file.virtualFile, "clickhouse-sortdev").use { recording ->
            val model = recording.console.scriptModel
            assertEmpty(model.parameters().map { it.name() }.toList())
            var prompted = false
            UiInterceptors.registerPossible(testRootDisposable, object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    prompted = true
                    component.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
            })
            assertTrue(recording.console.beforeExecuteQueries(model))
            assertFalse("The current masked bind unexpectedly prompted", prompted)
            assertEmpty(recording.requests)
        }
    }
}
