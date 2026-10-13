package dev.sort.clickhouse.brikk

import dev.brikk.house.sql.intellij.testing.HostSqlExecutionContract
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.pipe.ClickHousePreviewIntention
import dev.sort.clickhouse.pipe.ClickHouseRunToStageIntention
import dev.sort.clickhouse.brikk.kotlin.ClickHouseBrikkInspectionRule
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.notification.Notifications
import com.intellij.notification.Notification
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.notification.NotificationType

class ClickHouseBrikkExecutionTest : ClickHouseBrikkTestCase() {
    fun testHostEditorExecuteIsRefusedByTheSharedContract() {
        HostSqlExecutionContract(ClickHousePipeDialect) {
            val host = configure("@BrikkSql fun query() = Sql.clickhouse(\"FROM events |> WHERE <caret>id > 0 |> SELECT id\")")
            injected(host)
            com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil.getTopLevelEditor(myFixture.editor)
        }.refusesInspectionOnlySqlFromTheHostEditor()
    }

    fun testBrikkInspectionRuleIncludesColdPendingHosts() {
        val host = configure("@BrikkSql fun query() = Sql.clickhouse(\"SELECT 1\")")
        dev.brikk.house.intellij.BrikkHosts.forget(host)
        val editor = com.intellij.psi.impl.source.tree.injected.InjectedLanguageUtil.getTopLevelEditor(myFixture.editor)
        editor.caretModel.moveToOffset(host.textOffset + 2)
        val notifications = mutableListOf<Notification>()
        val bus = project.messageBus.connect()
        bus.subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                notification.setSuppressShowingPopup(true)
                notifications += notification
            }
        })
        val event = AnActionEvent.createEvent(SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.PSI_FILE, host.containingFile).build(), Presentation(), "ColdBrikkHost", ActionUiKind.NONE, null)
        try {
            DumbModeTestUtils.runInDumbModeSynchronously(project) {
                assertTrue(ClickHouseBrikkInspectionRule().isInspectionOnly(host.containingFile, host.textOffset + 2))
                requireNotNull(ActionManager.getInstance().getAction("Console.Jdbc.Execute")).actionPerformed(event)
                assertEquals(NotificationType.WARNING, notifications.single().type)
            }
        } finally {
            bus.disconnect()
            notifications.forEach { it.expire() }
        }
    }

    fun testPreviewIsInspectionOnlyAndRunToStageIsUnavailable() {
        val host = configure("@BrikkSql fun query() = Sql.clickhouse(\"FROM events |> WHERE <caret>id > 0\")")
        val file = injected(host)
        assertTrue(ClickHousePipeDialect.isInspectionHost(file))
        assertTrue(ClickHousePreviewIntention().isAvailable(project, myFixture.editor, file))
        assertFalse(ClickHouseRunToStageIntention().isAvailable(project, myFixture.editor, file))
    }
}
