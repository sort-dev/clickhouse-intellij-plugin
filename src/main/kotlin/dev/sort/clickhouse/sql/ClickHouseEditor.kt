package dev.sort.clickhouse.sql

import dev.brikk.house.sql.intellij.editor.KeywordOverlayHighlighterFactory
import dev.brikk.house.sql.intellij.editor.MaskedSpanRecolorAnnotator
import dev.brikk.house.sql.intellij.editor.SqlDialectQuoteHandler
import dev.brikk.house.sql.intellij.editor.SubstrateErrorFilter
import dev.brikk.house.sql.intellij.editor.SubstrateErrors
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.psi.PsiErrorElement
import dev.brikk.house.sql.diagnostics.localParseDiagnostics
import dev.brikk.house.sql.parser.SqlScriptSnapshot

class ClickHouseSyntaxHighlighterFactory : KeywordOverlayHighlighterFactory(ClickHouseDialect, emptySet())
class ClickHouseMaskedSpanAnnotator : MaskedSpanRecolorAnnotator(ClickHouseDialect, "clickhouse")
class ClickHouseQuoteHandler : SqlDialectQuoteHandler(EnterInString.Newline)
class ClickHouseSubstrateErrorFilter : HighlightErrorFilter() {
    private val substrate = object : SubstrateErrorFilter(ClickHouseDialect, SubstrateErrors.LENIENT_ONLY, "clickhouse") {}

    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean {
        if (!substrate.shouldHighlightErrorElement(element)) return false
        val file = element.containingFile ?: return true
        if (file.language !== ClickHouseDialect) return true
        val statement = SqlScriptSnapshot.parse(file.text, "clickhouse").at(element.textOffset) ?: return true
        // Local core errors are already emitted once by the native-error annotator. This does not
        // short-circuit its server verdict, unlike statementDiagnostics overrides.
        return localParseDiagnostics(file.text, statement.start until statement.end, "clickhouse").isEmpty()
    }
}
