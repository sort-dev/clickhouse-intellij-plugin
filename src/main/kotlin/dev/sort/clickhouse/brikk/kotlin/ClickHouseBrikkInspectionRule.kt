package dev.sort.clickhouse.brikk.kotlin

import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import dev.brikk.house.intellij.BrikkInspectionView
import dev.sort.clickhouse.brikk.ClickHouseInspectionRule

class ClickHouseBrikkInspectionRule : ClickHouseInspectionRule {
    override fun claims(host: PsiLanguageInjectionHost): Boolean = BrikkInspectionView.isInspectionOnly(host)
    override fun isInspectionOnly(file: PsiFile, offset: Int): Boolean = BrikkInspectionView.isInspectionOnly(file, offset)
}
