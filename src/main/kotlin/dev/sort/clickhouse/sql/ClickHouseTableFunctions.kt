package dev.sort.clickhouse.sql

import com.intellij.psi.ResolveState
import com.intellij.psi.impl.source.tree.CompositeElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.base.SqlElementFactoryBase
import com.intellij.sql.dialects.clickhouse.CHouseParserDefinition
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlInsertDmlInstruction
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.impl.SqlInsertDmlInstructionImpl
import com.intellij.sql.psi.SqlTableType
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.sql.psi.impl.SqlTableTypeBase
import dev.brikk.house.sql.metadata.FunctionKind
import dev.sort.clickhouse.ClickHouseDbms
import java.util.concurrent.ConcurrentHashMap

/** Resolve engine-advertised table functions without inventing signatures or result columns. */
class ClickHouseTableFunctionResolveExtension : SqlResolveExtension {
    private val symbols = ConcurrentHashMap<String, BuiltinFunction>()

    override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        val name = reference.element as? SqlReferenceExpression ?: return true
        val call = name.parent as? SqlFunctionCallExpression ?: return true
        if (call.nameElement !== name || name.qualifierExpression != null) return true
        val file = name.containingFile
        if (file.language !== ClickHouseDialect) return true
        if (!isTablePosition(call)) return true
        val function = ClickHouseCompletionProfile.functions(file).firstOrNull {
            it.kind == FunctionKind.TABLE_VALUED && it.name.equals(name.name, ignoreCase = true)
        } ?: return true
        val symbol = symbols.computeIfAbsent(function.name) {
            BuiltinFunction(it, null, emptyMap(), emptyList(), ClickHouseDbms.CLICKHOUSE_BRIKK)
        }
        return processor.execute(symbol, ResolveState.initial())
    }
}

private fun isInsertTarget(call: SqlFunctionCallExpression): Boolean {
    val insert = PsiTreeUtil.getParentOfType(call, SqlInsertDmlInstruction::class.java) ?: return false
    return insert.targetExpression?.let { PsiTreeUtil.isAncestor(it, call, false) } == true
}

private fun isTablePosition(call: SqlFunctionCallExpression): Boolean =
    call.parent?.node?.elementType == SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION || isInsertTarget(call)

class ClickHouseElementFactory(
    private val stock: SqlElementFactoryBase = CHouseParserDefinition().elementFactory,
) : SqlElementFactoryBase by stock {
    override fun createElementNode(type: IElementType): CompositeElement? =
        if (type == SqlCompositeElementTypes.SQL_INSERT_DML_INSTRUCTION) ClickHouseInsertInstruction(type)
        else stock.createElementNode(type)
}

private class ClickHouseInsertInstruction(type: IElementType) : SqlInsertDmlInstructionImpl(type) {
    override fun getTargetType(): SqlTableType {
        val target = targetExpression
        if (target != null && PsiTreeUtil.findChildOfType(target, SqlFunctionCallExpression::class.java, false) != null) {
            // The platform uses its empty table type for an unresolved/open shape. A scalar UNKNOWN
            // instead becomes one synthetic column named after the call, causing false checks/hints.
            return SqlTableTypeBase.EMPTY_TABLE
        }
        return super.getTargetType()
    }
}
