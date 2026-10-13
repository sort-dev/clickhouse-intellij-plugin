package dev.sort.clickhouse.sql

import com.intellij.lang.PsiBuilder
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParser
import dev.brikk.house.sql.intellij.pipe.PipeStatementGate
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.brikk.ClickHouseHostSql
import com.intellij.psi.impl.source.resolve.FileContextUtil
import dev.brikk.house.sql.intellij.parser.parseColonParameter

class ClickHousePsiParser : CHouseFamilyParser("clickhouse") {
    private val pipes = PipeStatementGate(ClickHousePipeDialect)

    override fun parseStatement(builder: PsiBuilder, level: Int): Boolean =
        pipes.parse(builder) || super.parseStatement(builder, level)

    override fun parseParameterOrVariableReference(builder: PsiBuilder, allowVariable: Boolean): Boolean {
        val file = builder.getUserData(FileContextUtil.CONTAINING_FILE_KEY)
        if (ClickHouseHostSql.parsesColonBinds(file) && builder.parseColonParameter()) return true
        return super.parseParameterOrVariableReference(builder, allowVariable)
    }
}
