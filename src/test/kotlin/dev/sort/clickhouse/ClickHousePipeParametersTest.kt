package dev.sort.clickhouse

import com.intellij.database.actions.ShowSqlParametersPanelAction
import com.intellij.database.console.client.SessionClientHolder
import com.intellij.database.datagrid.DataRequest
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.psi.PsiDocumentManager
import com.intellij.ui.UiInterceptors
import dev.brikk.house.sql.intellij.CooperativeExecuteAction
import dev.brikk.house.sql.intellij.catalog.DataSourceResolution
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.pipe.PipeTranslation
import dev.brikk.house.sql.intellij.pipe.lower
import dev.brikk.house.sql.intellij.testing.RecordingConsole
import dev.brikk.house.sql.shape.SqlFragment
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHousePipeParametersTest : ClickHousePipeTestCase() {
    fun testIdeBindIsPromptedAndTypedParametersPassThroughUntouched() {
        val sql = "FROM {table:Identifier} |> WHERE id > {floor:UInt64} AND id < :limit |> SELECT id"
        val editor = open(sql)
        val file = requireNotNull(PsiDocumentManager.getInstance(project).getPsiFile(editor.document))
        RecordingConsole(project, file.virtualFile, "clickhouse-sortdev").use { recording ->
            assertSame(ClickHouseDbms.CLICKHOUSE_BRIKK, recording.console.session.connectionPoint.dbms)
            assertSame(ClickHouseDialect, file.language)
            assertTrue(DialectDataSourceResolver(ClickHouseDbms.CLICKHOUSE_BRIKK).resolve(file) is DataSourceResolution.Console)
            assertEquals(listOf("limit"), ClickHousePipeDialect.parameterSpans(sql).map { it.name })
            assertTrue(ClickHousePipeDialect.maskParameters(sql).contains("{floor:UInt64}"))
            var prompted = false
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<DialogWrapper>(DialogWrapper::class.java) {
                override fun doIntercept(component: DialogWrapper) {
                    prompted = true
                    ShowSqlParametersPanelAction.getStorage(recording.console).putValue("limit", "17")
                    component.close(DialogWrapper.OK_EXIT_CODE)
                }
            })
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.PSI_FILE, file)
                .add(CommonDataKeys.VIRTUAL_FILE, file.virtualFile)
                .add(SessionClientHolder.CLIENT_KEY, recording.console)
                .build()
            val event = AnActionEvent.createEvent(context, Presentation(), "ClickHouseParameterTest", ActionUiKind.NONE, null)
            val previous = object : AnAction() {
                override fun actionPerformed(e: AnActionEvent) = fail("Owned PIPE must not delegate")
            }
            editor.selectionModel.setSelection(0, sql.length)
            try {
                CooperativeExecuteAction(1, previous, PipeExecuteInterceptor(ClickHousePipeDialect)).actionPerformed(event)
            } finally {
                UiInterceptors.clear()
            }
            assertTrue("The IDE bind was not prompted", prompted)
            val query = (recording.requests.single() as DataRequest.QueryRequest).query
            assertTrue(query, query.contains("{table:Identifier}"))
            assertTrue(query, query.contains("{floor:UInt64}"))
            assertFalse(query, query.contains(":limit"))
            val expected = ClickHousePipeDialect.lower(sql.replace(":limit", "17")) as PipeTranslation.Lowered
            assertEquals(SqlFragment(expected.sql, "clickhouse").ast, SqlFragment(query, "clickhouse").ast)
        }
    }

    fun testMissingNativeValidatorHasNoAcceptedVerdict() {
        val translation = ClickHousePipeDialect.lower("SELECT {value:UInt64} AS value |> WHERE value > 0") as PipeTranslation.Lowered
        assertNull(ClickHousePipeDialect.validator)
        assertNull(translation.verdict)
        assertNull(translation.blocker)
        assertTrue(translation.sql.contains("{value:UInt64}"))
    }
}
