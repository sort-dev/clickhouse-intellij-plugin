package dev.sort.clickhouse.brikk.kotlin

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiLanguageInjectionHost
import dev.brikk.house.intellij.BrikkDialect
import dev.brikk.house.intellij.BrikkHosts
import dev.brikk.house.intellij.BrikkHostState
import dev.brikk.house.intellij.BrikkPendingCause
import dev.brikk.house.sql.intellij.host.HostSqlClaim
import dev.brikk.house.sql.intellij.host.HostSqlEntries
import dev.brikk.house.sql.intellij.host.HostSqlEntry
import dev.brikk.house.sql.intellij.host.HostSqlInjector
import dev.brikk.house.sql.intellij.host.HostSqlTrim
import dev.sort.clickhouse.brikk.ClickHouseHostSql
import dev.sort.clickhouse.sql.ClickHouseDialect
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

internal data class ClickHouseBrikkHostData(val cause: BrikkPendingCause?)

class ClickHouseBrikkInjector : HostSqlInjector(ClickHouseHostSql, ClickHouseDialect) {
    override val prefix = "-- dialect: clickhouse\n"

    override fun claim(host: PsiLanguageInjectionHost): HostSqlClaim {
        if (host !is KtStringTemplateExpression) return HostSqlClaim.NotClaimed
        val state = BrikkHosts.state(host)
        if (state.dialect != BrikkDialect.CLICKHOUSE) return HostSqlClaim.NotClaimed
        val context = when (state) {
            is BrikkHostState.Recognized -> state.context
            is BrikkHostState.Pending -> state.projection ?: return HostSqlClaim.Unavailable(state.reason)
            BrikkHostState.NotBrikk -> return HostSqlClaim.NotClaimed
        }
        val entries = context.entries.map { entry ->
            HostSqlEntry(TextRange(entry.range.start, entry.range.end), entry.replacement, entry.placeholder)
        }
        val trims = context.trimSteps.map { trim ->
            when (trim.function) {
                "trimIndent" -> HostSqlTrim.Indent
                "trimMargin" -> HostSqlTrim.Margin(requireNotNull(trim.marginPrefix))
                else -> error("Unknown helper trim: ${trim.function}")
            }
        }
        return HostSqlClaim.Injected(
            ClickHouseDialect, HostSqlEntries(entries, trims), pending = state is BrikkHostState.Pending,
            inspectionOnly = true, colonBinds = true,
            data = ClickHouseBrikkHostData((state as? BrikkHostState.Pending)?.cause),
        )
    }
}
