package dev.sort.clickhouse.sql

import dev.brikk.house.sql.intellij.completion.ExpressionPositions
import dev.brikk.house.sql.intellij.completion.QualifierRule
import dev.brikk.house.sql.intellij.completion.SqlCompletionConfidence
import dev.brikk.house.sql.intellij.completion.SqlCompletionContributor
import dev.brikk.house.sql.intellij.completion.SqlCompletionProfile
import dev.brikk.house.sql.intellij.completion.SqlFunctionGates
import dev.brikk.house.sql.metadata.CLICKHOUSE_METADATA

object ClickHouseCompletionProfile : SqlCompletionProfile {
    override val language get() = ClickHouseDialect
    override val metadata = CLICKHOUSE_METADATA
    override val functionGates = SqlFunctionGates(
        qualifier = QualifierRule.Unqualified,
        digitPrefix = true,
        autopopup = ExpressionPositions.STANDARD,
    )
}

class ClickHouseCompletionContributor : SqlCompletionContributor(ClickHouseCompletionProfile)
class ClickHouseCompletionConfidence : SqlCompletionConfidence(ClickHouseCompletionProfile)
