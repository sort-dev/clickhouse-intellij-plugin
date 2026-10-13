package dev.sort.clickhouse.brikk.kotlin

import com.intellij.ide.BrowserUtil
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.ui.EditorNotifications
import dev.brikk.house.intellij.BrikkAction
import dev.brikk.house.intellij.BrikkActionKind
import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkHosts
import dev.brikk.house.intellij.BrikkPendingCause
import dev.brikk.house.sql.intellij.completion.SqlLookupIcons
import dev.brikk.house.sql.intellij.host.HostSqlActionBanner
import dev.brikk.house.sql.intellij.host.HostSqlClaim
import dev.brikk.house.sql.intellij.host.HostSqlLineMarkerProvider
import dev.brikk.house.sql.intellij.host.HostSqlState
import dev.sort.clickhouse.brikk.ClickHouseHostSql

class ClickHouseBrikkLineMarker : HostSqlLineMarkerProvider(ClickHouseHostSql, "ClickHouse Brikk SQL", SqlLookupIcons.BrikkColumn) {
    override fun tooltip(state: HostSqlState): String {
        val claim = state.claim as? HostSqlClaim.Injected
        val data = claim?.data as? ClickHouseBrikkHostData
        val cause = if (data != null && DumbService.isDumb(data.project)) BrikkPendingCause.INDEXING else data?.cause
        val pending = when (cause) {
            BrikkPendingCause.INDEXING -> "waiting for indexing"
            BrikkPendingCause.ANALYSIS_PROHIBITED -> "waiting for analysis"
            BrikkPendingCause.UNRESOLVED -> "waiting for resolution"
            BrikkPendingCause.RUNTIME_ENTRY_MISSING -> "Brikk runtime entry missing"
            BrikkPendingCause.GENERATED_SHAPES -> "waiting for generated shapes"
            BrikkPendingCause.COMPILER_PLUGIN_MISSING -> "Brikk compiler plugin missing"
            null -> if (claim == null || claim.pending) "waiting for recognition" else null
        }
        return "ClickHouse Brikk SQL: named binds and relation contracts; inspection only" + (pending?.let { "; $it" } ?: "")
    }
}

private fun relevant(action: BrikkAction): Boolean =
    action.kind != BrikkActionKind.DIALECT_PLUGIN_MISSING || action.dialect == BrikkDialect.CLICKHOUSE

class ClickHouseBrikkActionBanner : HostSqlActionBanner() {
    override fun actionNeeded(project: Project, file: VirtualFile): Action? {
        val psi = PsiManager.getInstance(project).findFile(file) ?: return null
        if (psi.language.id != "kotlin") return null
        val action = BrikkHosts.actionNeeded(psi)?.takeIf(::relevant) ?: return null
        if (action.buildFileUrl == null && action.docsUrl == null) return null
        return Action(action.message, action.fixText, Runnable {
            val build = action.buildFileUrl?.let { VirtualFileManager.getInstance().findFileByUrl(it) }
            if (build != null) OpenFileDescriptor(project, build).navigate(true)
            else action.docsUrl?.let(BrowserUtil::browse)
        })
    }
}

private val lastAction = Key.create<BrikkAction>("dev.sort.clickhouse.brikk.last.action")

internal fun updateClickHouseBrikkBanner(file: PsiFile) {
    val top = InjectedLanguageManager.getInstance(file.project).getTopLevelFile(file)
    val action = BrikkHosts.actionNeeded(top)?.takeIf(::relevant)
    if (top.getUserData(lastAction) == action) return
    top.putUserData(lastAction, action)
    EditorNotifications.getInstance(top.project).updateNotifications(top.virtualFile)
}
