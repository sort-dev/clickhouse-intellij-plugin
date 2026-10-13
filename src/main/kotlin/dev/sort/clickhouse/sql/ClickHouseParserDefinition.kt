package dev.sort.clickhouse.sql

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParserDefinition

class ClickHouseParserDefinition : CHouseFamilyParserDefinition(ClickHouseDialect, "clickhouse") {
    override fun createBaseLexer(): Lexer = ClickHouseLexer()
    override fun createParser(project: Project?): PsiParser = ClickHousePsiParser()
}
