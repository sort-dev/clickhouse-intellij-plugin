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

class ClickHouseExecuteConfiguration : CooperativeExecuteConfiguration() {
    override val interceptor = PipeExecuteInterceptor(ClickHousePipeDialect)
}

class ClickHouseExecutePromoter : CooperativeExecutePromoter()
class ClickHousePreviewIntention : PipePreviewIntention(ClickHousePipeDialect)
class ClickHouseRunToStageIntention : PipeRunToStageIntention(ClickHousePipeDialect)
class ClickHousePreviewAction : PipePreviewAction(ClickHousePipeDialect)
class ClickHouseRunToStageAction : PipeRunToStageAction(ClickHousePipeDialect)
class ClickHouseStageCompletion : PipeStageCompletionContributor(ClickHousePipeDialect)
class ClickHouseStageAnnotator : PipeStageKeywordAnnotator(ClickHousePipeDialect)
