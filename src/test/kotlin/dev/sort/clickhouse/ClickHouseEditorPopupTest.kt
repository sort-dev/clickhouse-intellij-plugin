package dev.sort.clickhouse

import com.intellij.sql.dialects.clickhouse.CHouseDialect
import dev.brikk.house.sql.intellij.testing.EditorPopupContract
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping

class ClickHouseEditorPopupTest : ClickHousePipeTestCase() {
    private fun popup() = EditorPopupContract(myFixture, "ClickHousePipe.EditorMenu",
        listOf("ClickHousePipe.Preview", "ClickHousePipe.RunToStage"))

    private fun openValidPipeContext() {
        open("SELECT 1 AS id |> WHE<caret>RE id > 0")
    }

    fun testRegisteredEditorPopupContexts() {
        popup().check(unrelated = mapOf("ordinary.kt" to "val count = 1", "notes.txt" to "plain text")) {
            openValidPipeContext()
        }
    }

    fun testDialectSyntaxAndSettingControls() {
        val popup = popup()
        openValidPipeContext()
        popup.assertShown(popup.context())

        open("SELECT 1")
        popup.assertAbsent(what = "ClickHouse SQL without PIPE")

        val wrongDialect = myFixture.configureByText("stock-clickhouse.sql", "SELECT 1 AS id |> WHERE id > 0")
        setSqlDialectMapping(project, wrongDialect.virtualFile, CHouseDialect.INSTANCE, testRootDisposable)
        popup.assertAbsent(what = "a different SQL dialect")

        openValidPipeContext()
        settings.enabled = false
        popup.assertAbsent(what = "ClickHouse PIPE disabled")
    }
}
