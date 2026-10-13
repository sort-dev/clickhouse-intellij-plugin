package dev.sort.clickhouse.sql

import dev.brikk.house.sql.intellij.editor.KeywordOverlayHighlighterFactory
import dev.brikk.house.sql.intellij.editor.MaskedSpanRecolorAnnotator
import dev.brikk.house.sql.intellij.editor.SqlDialectQuoteHandler

class ClickHouseSyntaxHighlighterFactory : KeywordOverlayHighlighterFactory(ClickHouseDialect, emptySet())
class ClickHouseMaskedSpanAnnotator : MaskedSpanRecolorAnnotator(ClickHouseDialect, "clickhouse")
class ClickHouseQuoteHandler : SqlDialectQuoteHandler(EnterInString.Newline)
