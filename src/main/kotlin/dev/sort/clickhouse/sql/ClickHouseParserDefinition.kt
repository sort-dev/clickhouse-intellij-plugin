package dev.sort.clickhouse.sql

import com.intellij.lexer.Lexer
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParserDefinition

class ClickHouseParserDefinition : CHouseFamilyParserDefinition(ClickHouseDialect, "clickhouse") {
    override fun createBaseLexer(): Lexer = ClickHouseLexer()
}
