package dev.sort.clickhouse.sql

import com.intellij.lang.PsiBuilder
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParser
import dev.brikk.house.sql.intellij.pipe.PipeStatementGate
import dev.sort.clickhouse.pipe.ClickHousePipeDialect

class ClickHousePsiParser : CHouseFamilyParser("clickhouse") {
    private val pipes = PipeStatementGate(ClickHousePipeDialect)

    override fun parseStatement(builder: PsiBuilder, level: Int): Boolean =
        pipes.parse(builder) || super.parseStatement(builder, level)
}
