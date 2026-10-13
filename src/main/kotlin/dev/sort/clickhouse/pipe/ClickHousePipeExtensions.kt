package dev.sort.clickhouse.pipe

import dev.brikk.house.sql.intellij.CooperativeExecuteConfiguration
import dev.brikk.house.sql.intellij.CooperativeExecutePromoter
import dev.brikk.house.sql.intellij.pipe.PipeExecuteInterceptor
import dev.brikk.house.sql.intellij.pipe.PipePreviewAction
import dev.brikk.house.sql.intellij.pipe.PipePreviewIntention
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageAction
import dev.brikk.house.sql.intellij.pipe.PipeRunToStageIntention
import dev.brikk.house.sql.intellij.pipe.PipeStageCompletionContributor
import dev.brikk.house.sql.intellij.pipe.PipeStageKeywordAnnotator
import com.intellij.psi.PsiFile
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.CommonDataKeys
import dev.brikk.house.sql.intellij.ExecuteInterceptor
import dev.brikk.house.sql.intellij.ExecutionClaim
import dev.brikk.house.sql.intellij.notifyEscaped
import dev.sort.clickhouse.brikk.clickHouseInspectionRules

class ClickHouseExecuteConfiguration : CooperativeExecuteConfiguration() {
    private val pipes = PipeExecuteInterceptor(ClickHousePipeDialect)
    override val interceptor = ExecuteInterceptor { event, option ->
        val file = event.getData(CommonDataKeys.PSI_FILE)
        val editor = event.getData(CommonDataKeys.EDITOR)
        val offset = if (editor?.selectionModel?.hasSelection() == true) editor.selectionModel.selectionStart else editor?.caretModel?.offset ?: 0
        if (file != null && clickHouseInspectionRules.extensionList.any { it.isInspectionOnly(file, offset) }) {
            notifyEscaped(event.project, ClickHousePipeDialect.notificationGroup, ClickHousePipeDialect.label,
                "Brikk SQL is an inspection-only view; execute it through the Brikk runtime.", NotificationType.WARNING)
            ExecutionClaim.HANDLED
        } else pipes.execute(event, option)
    }
}

class ClickHouseExecutePromoter : CooperativeExecutePromoter()
class ClickHousePreviewIntention : PipePreviewIntention(ClickHousePipeDialect)
class ClickHouseRunToStageIntention : PipeRunToStageIntention(ClickHousePipeDialect)
class ClickHousePreviewAction : PipePreviewAction(ClickHousePipeDialect)
class ClickHouseRunToStageAction : PipeRunToStageAction(ClickHousePipeDialect)
class ClickHouseStageCompletion : PipeStageCompletionContributor(ClickHousePipeDialect) {
    override fun inputCatalog(file: PsiFile, input: String) = null
}
class ClickHouseStageAnnotator : PipeStageKeywordAnnotator(ClickHousePipeDialect)
