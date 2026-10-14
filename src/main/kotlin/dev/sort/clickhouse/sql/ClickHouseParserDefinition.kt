package dev.sort.clickhouse.sql

import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParserDefinition
import com.intellij.sql.dialects.base.SqlElementFactoryBase

class ClickHouseParserDefinition : CHouseFamilyParserDefinition(ClickHouseDialect, "clickhouse") {
    override fun createElementFactory(): SqlElementFactoryBase = ClickHouseElementFactory()
    override fun createBaseLexer(): Lexer = ClickHouseLexer()
    override fun createParser(project: Project?): PsiParser = ClickHousePsiParser()
}
