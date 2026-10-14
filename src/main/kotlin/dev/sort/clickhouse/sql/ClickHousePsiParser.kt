package dev.sort.clickhouse.sql

import com.intellij.lang.PsiBuilder
import dev.brikk.house.sql.intellij.parser.CHouseFamilyParser
import dev.brikk.house.sql.intellij.pipe.PipeStatementGate
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.brikk.ClickHouseHostSql
import com.intellij.psi.impl.source.resolve.FileContextUtil
import dev.brikk.house.sql.intellij.parser.parseColonParameter
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlLazyElementTypeImpl

class ClickHousePsiParser : CHouseFamilyParser("clickhouse") {
    private val pipes = PipeStatementGate(ClickHousePipeDialect)

    override fun parseStatement(builder: PsiBuilder, level: Int): Boolean =
        pipes.parse(builder) || super.parseStatement(builder, level)

    override fun parseParameterOrVariableReference(builder: PsiBuilder, allowVariable: Boolean): Boolean {
        val file = builder.getUserData(FileContextUtil.CONTAINING_FILE_KEY)
        if (ClickHouseHostSql.parsesColonBinds(file) && builder.parseColonParameter()) return true
        return super.parseParameterOrVariableReference(builder, allowVariable)
    }

    override fun parseArgumentListInner(builder: PsiBuilder): Boolean {
        // Stock parses SELECT here as an identifier and recovers the rest as dummy tokens. Use its
        // own query parser so references and real unknown functions inside the predicate stay visible.
        val context = SqlLazyElementTypeImpl.getContextElement(builder)
        val call = (context as? SqlFunctionCallExpression) ?: (context?.parent as? SqlFunctionCallExpression)
        val name = call?.nameElement
        if (name?.qualifierExpression == null && name?.text?.uppercase() in setOf("EXISTS", "ANY", "ALL")) {
            val attempt = builder.mark()
            if (parseQueryExpression(builder, 0) && builder.eof()) {
                attempt.drop()
                return true
            }
            attempt.rollbackTo()
        }
        return super.parseArgumentListInner(builder)
    }
}
