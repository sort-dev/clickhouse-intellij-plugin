package dev.sort.clickhouse

import com.intellij.database.console.client.SessionClientHolder
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiDocumentManager
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.sort.clickhouse.pipe.ClickHousePipeDialect

class ClickHousePipeRefusalTest : ClickHousePipeTestCase() {
    fun testUnsupportedClickHouseStagesNeverSubmitOrDelegate() {
        for (stage in listOf(
            "CALL f(1)", "RENAME id AS renamed", "NATURAL JOIN other",
            "STRAIGHT_JOIN other ON events.id = other.id",
            "PIVOT (sum(id) FOR value IN (1, 2))", "UNPIVOT (v FOR k IN (id, value))",
        )) {
            val sql = "FROM events |> $stage"
            val editor = open(sql)
            val file = requireNotNull(PsiDocumentManager.getInstance(project).getPsiFile(editor.document))
            RecordingConsole(project, file.virtualFile, "clickhouse-sortdev").use { recording ->
                val notifications = mutableListOf<Notification>()
                val bus = project.messageBus.connect()
                bus.subscribe(Notifications.TOPIC, object : Notifications {
                    override fun notify(notification: Notification) {
                        if (notification.groupId == ClickHousePipeDialect.notificationGroup) {
                            notification.setSuppressShowingPopup(true)
                            notifications += notification
                        }
                    }
                })
                val context = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.EDITOR, editor)
                    .add(CommonDataKeys.PSI_FILE, file).add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                    .add(SessionClientHolder.CLIENT_KEY, recording.console).build()
                val event = AnActionEvent.createEvent(context, Presentation(), "ClickHouseRefusalTest", ActionUiKind.NONE, null)
                val previous = object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) = fail("$stage must not delegate")
                }
                editor.selectionModel.setSelection(0, sql.length)
                try {
                    CooperativeExecuteAction(1, previous, PipeExecuteInterceptor(ClickHousePipeDialect)).actionPerformed(event)
                    assertEmpty(stage, recording.requests)
                    assertTrue(stage, notifications.any { it.type == NotificationType.ERROR })
                } finally {
                    bus.disconnect()
                    notifications.forEach { it.expire() }
                }
            }
        }
    }
}
