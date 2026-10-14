package dev.sort.clickhouse.sql

import dev.brikk.house.sql.intellij.editor.KeywordOverlayHighlighterFactory
import dev.brikk.house.sql.intellij.editor.MaskedSpanRecolorAnnotator
import dev.brikk.house.sql.intellij.editor.SqlDialectQuoteHandler
import dev.brikk.house.sql.intellij.editor.SubstrateErrorFilter
import dev.brikk.house.sql.intellij.editor.SubstrateErrors

class ClickHouseSyntaxHighlighterFactory : KeywordOverlayHighlighterFactory(ClickHouseDialect, emptySet())
class ClickHouseMaskedSpanAnnotator : MaskedSpanRecolorAnnotator(ClickHouseDialect, "clickhouse")
class ClickHouseQuoteHandler : SqlDialectQuoteHandler(EnterInString.Newline)
class ClickHouseSubstrateErrorFilter : SubstrateErrorFilter(ClickHouseDialect, SubstrateErrors.LENIENT_ONLY, "clickhouse")
