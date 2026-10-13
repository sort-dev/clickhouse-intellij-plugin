package dev.sort.clickhouse.brikk

import dev.brikk.house.sql.intellij.host.HostSqlChannel
import dev.brikk.house.sql.intellij.host.HostSqlPlaceholderErrorFilter
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost

interface ClickHouseInspectionRule {
    fun claims(host: PsiLanguageInjectionHost): Boolean
    fun isInspectionOnly(file: PsiFile, offset: Int): Boolean
}

internal val clickHouseInspectionRules = ExtensionPointName.create<ClickHouseInspectionRule>("dev.sort.clickhouse.inspectionRule")

object ClickHouseHostSql : HostSqlChannel("dev.sort.clickhouse.brikk") {
    override fun claimsHost(host: PsiLanguageInjectionHost): Boolean = clickHouseInspectionRules.extensionList.any { it.claims(host) }
}
class ClickHousePlaceholderErrorFilter : HostSqlPlaceholderErrorFilter(ClickHouseHostSql)
