package dev.sort.clickhouse.sql

import dev.brikk.house.sql.intellij.completion.ExpressionPositions
import dev.brikk.house.sql.intellij.completion.QualifierRule
import dev.brikk.house.sql.intellij.completion.SqlCompletionConfidence
import dev.brikk.house.sql.intellij.completion.SqlCompletionContributor
import dev.brikk.house.sql.intellij.completion.SqlCompletionProfile
import dev.brikk.house.sql.intellij.completion.SqlFunctionGates
import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.metadata.MetadataSnapshot
import dev.sort.clickhouse.catalog.ClickHouseLiveCatalogService

object ClickHouseCompletionProfile : SqlCompletionProfile {
    override val language get() = ClickHouseDialect
    override val metadata = CLICKHOUSE_METADATA
    private fun live(file: PsiFile) = ApplicationManager.getApplication().service<ClickHouseLiveCatalogService>().completion(file)
    override fun snapshot(file: PsiFile) = live(file).snapshot
    override fun functions(file: PsiFile) = live(file).functions
    override fun serverOnlyFunctions(file: PsiFile) = live(file).serverOnlyFunctions
    override fun keywordCatalog(file: PsiFile, snapshot: MetadataSnapshot) = live(file).keywords
    override fun builtinFunctionFilter(file: PsiFile) = live(file).builtinFunctionFilter
    override val functionGates = SqlFunctionGates(
        qualifier = QualifierRule.Unqualified,
        digitPrefix = true,
        autopopup = ExpressionPositions.STANDARD,
    )
}

class ClickHouseCompletionContributor : SqlCompletionContributor(ClickHouseCompletionProfile)
class ClickHouseCompletionConfidence : SqlCompletionConfidence(ClickHouseCompletionProfile)
