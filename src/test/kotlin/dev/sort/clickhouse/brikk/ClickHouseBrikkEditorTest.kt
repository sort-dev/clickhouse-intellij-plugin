package dev.sort.clickhouse.brikk

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.util.PsiTreeUtil
import dev.sort.clickhouse.brikk.kotlin.ClickHouseBrikkLineMarker
import dev.sort.clickhouse.brikk.kotlin.ClickHouseBrikkActionBanner
import dev.brikk.house.intellij.BrikkHosts
import com.intellij.testFramework.DumbModeTestUtils

class ClickHouseBrikkEditorTest : ClickHouseBrikkTestCase() {
    fun testClaimedHostGetsOneGutterMarkerAtItsFirstLeaf() {
        val host = configure("@BrikkSql fun query(id: Long) = Sql.clickhouse(\"SELECT ${'$'}id\")")
        injected(host)
        val leaf = PsiTreeUtil.getDeepestFirst(host)
        val markers = mutableListOf<LineMarkerInfo<*>>()
        ClickHouseBrikkLineMarker().collectSlowLineMarkers(listOf(leaf), markers)
        assertEquals(1, markers.size)
        assertEquals(leaf.textRange, requireNotNull(markers.single().element).textRange)
        assertTrue(markers.single().lineMarkerTooltip.orEmpty().contains("inspection only"))
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val pending = mutableListOf<LineMarkerInfo<*>>()
            ClickHouseBrikkLineMarker().collectSlowLineMarkers(listOf(leaf), pending)
            assertEquals(1, pending.size)
            assertTrue(pending.single().lineMarkerTooltip.orEmpty().contains("waiting for indexing"))
        }
    }

    fun testHealthyBrikkHostHasNoRoutineActionBanner() {
        val host = configure("@BrikkSql fun query() = Sql.clickhouse(\"SELECT 1\")")
        injected(host)
        assertNull(BrikkHosts.actionNeeded(host.containingFile))
        assertNull(ClickHouseBrikkActionBanner().collectNotificationData(project, host.containingFile.virtualFile))
    }
}
