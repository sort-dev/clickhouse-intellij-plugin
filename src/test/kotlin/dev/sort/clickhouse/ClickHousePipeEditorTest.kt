package dev.sort.clickhouse

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.IndexingTestUtil
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.pipe.ClickHousePreviewIntention

class ClickHousePipeEditorTest : ClickHousePipeTestCase() {
    private fun complete(sql: String): List<String> {
        open(sql)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val codeInsight = CodeInsightSettings.getInstance()
        val previous = codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION
        codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            myFixture.completeBasic()
            return myFixture.lookupElementStrings.orEmpty()
        } finally {
            codeInsight.AUTOCOMPLETE_ON_CODE_COMPLETION = previous
        }
    }

    fun testOnlyRunnableClickHouseStagesAreOffered() {
        assertSameElements(complete("FROM events |> <caret>"), ClickHousePipeDialect.stagePhrases)
        assertSameElements(complete("SELECT * FROM (FROM events |> <caret>) AS p"), ClickHousePipeDialect.stagePhrases)
    }

    fun testMultiWordStageInsertionDoesNotRepeatThePrefix() {
        for (prefix in listOf("full o", "full  o", "full\n o")) {
            complete("FROM events |> $prefix<caret>")
            val item = myFixture.lookupElements.orEmpty().single { it.lookupString == "FULL OUTER JOIN" }
            myFixture.lookup.currentItem = item
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals("FROM events |> FULL OUTER JOIN", myFixture.editor.document.text)
        }
    }

    fun testCommentsDoNotHideStageCompletion() {
        complete("FROM events |> /* retain */ whe<caret>")
        myFixture.lookup.currentItem = myFixture.lookupElements.orEmpty().single { it.lookupString == "WHERE" }
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
        assertEquals("FROM events |> /* retain */ WHERE", myFixture.editor.document.text)
    }

    fun testStageInputColumnIsOfferedOnce() {
        val items = complete("SELECT 1 AS input_id |> WHERE input_<caret>")
        assertEquals(listOf("input_id"), items.filter { it == "input_id" })
    }

    fun testOnlySupportedStageHeadsAreColoredAtTheirOffsets() {
        val sql = "FROM events |> NATURAL JOIN other |> WHERE id > 0 |> ORDER BY id"
        open(sql)
        val ranges = myFixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == DefaultLanguageHighlighterColors.KEYWORD }
            .map { TextRange(it.startOffset, it.endOffset) }
        assertSameElements(ranges, listOf("WHERE", "ORDER BY").map { word ->
            TextRange(sql.indexOf(word), sql.indexOf(word) + word.length)
        })
    }

    fun testPreviewAvailabilityFollowsTheSettingAndVmVeto() {
        open("SELECT 1 AS id |> WHERE id > 0")
        val intention = ClickHousePreviewIntention()
        assertTrue(intention.isAvailable(project, myFixture.editor, myFixture.file))
        settings.enabled = false
        assertFalse(intention.isAvailable(project, myFixture.editor, myFixture.file))
        settings.enabled = true
        System.setProperty(ClickHousePipeDialect.vetoProperty, "false")
        assertFalse(intention.isAvailable(project, myFixture.editor, myFixture.file))
    }
}
