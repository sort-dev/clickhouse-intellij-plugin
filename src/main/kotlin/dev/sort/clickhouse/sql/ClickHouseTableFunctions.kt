package dev.sort.clickhouse.sql

import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.database.model.DasObject
import com.intellij.database.model.ObjectKind
import com.intellij.database.symbols.DasSymbol
import com.intellij.sql.psi.SqlResolveResult
import com.intellij.sql.psi.SqlQueryExpression
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
import com.intellij.sql.psi.impl.SqlResolveExtension
import com.intellij.sql.psi.impl.SqlFunctionCallExpressionImpl
import com.intellij.sql.psi.impl.SqlDasTypeAwareDeepRecursiveElement
import com.intellij.database.types.DasType
import com.intellij.database.types.DasTypeSystemBase
import dev.brikk.house.sql.intellij.relation.RelationTableTypes
import dev.sort.clickhouse.brikk.ClickHouseRelationProfile
import dev.brikk.house.sql.metadata.FunctionKind
import dev.sort.clickhouse.ClickHouseDbms
import java.util.concurrent.ConcurrentHashMap

/** Resolve engine-advertised table functions without inventing signatures or result columns. */
class ClickHouseTableFunctionResolveExtension : SqlResolveExtension {
    private val symbols = ConcurrentHashMap<String, BuiltinFunction>()

    override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        // PsiReference.getElement, not the experimental PsiSymbolReference.getElement: the same element.
        val name = (reference as PsiReference).element as? SqlReferenceExpression ?: return true
        val call = name.parent as? SqlFunctionCallExpression
        if (name.qualifierExpression != null) return true
        val file = name.containingFile
        if (file.language !== ClickHouseDialect) return true
        val tableReference = name.referenceElementType.targetKind == ObjectKind.TABLE &&
            file.text.drop(name.textRange.endOffset).dropWhile(Char::isWhitespace).startsWith('(')
        if (!tableReference && (call == null || call.nameElement !== name || !isTablePosition(call))) return true
        val function = ClickHouseCompletionProfile.functions(file).firstOrNull {
            it.kind == FunctionKind.TABLE_VALUED && it.name.equals(name.name, ignoreCase = true)
        } ?: return true
        val symbol = symbols.computeIfAbsent("${if (tableReference) "table" else "call"}:${function.name}") {
            object : BuiltinFunction(function.name, null, emptyMap(), emptyList(), ClickHouseDbms.CLICKHOUSE_BRIKK) {
                override fun getKind(): ObjectKind = if (tableReference) ObjectKind.TABLE else ObjectKind.ROUTINE
            }
        }
        return processor.execute(symbol, ResolveState.initial())
    }
}

private fun isInsertTarget(call: SqlFunctionCallExpression): Boolean {
    val insert = PsiTreeUtil.getParentOfType(call, SqlInsertDmlInstruction::class.java) ?: return false
    return insert.targetExpression?.let { PsiTreeUtil.isAncestor(it, call, false) } == true
}

private fun isTablePosition(call: SqlFunctionCallExpression): Boolean {
    if (call.parent?.node?.elementType == SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION || isInsertTarget(call)) return true
    val outer = PsiTreeUtil.getParentOfType(call, SqlFunctionCallExpression::class.java) ?: return false
    val name = outer.nameElement?.name ?: return false
    return ClickHouseCompletionProfile.functions(call.containingFile).any { it.kind == FunctionKind.TABLE_VALUED && it.name == name } && isTablePosition(outer)
}

/** Table-name completion is not a valid fallback for a table function's expression arguments. */
class ClickHouseTableFunctionArgumentCompletion : CompletionContributor() {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (parameters.originalFile.language !== ClickHouseDialect) return
        val call = generateSequence(PsiTreeUtil.getParentOfType(parameters.position, SqlFunctionCallExpression::class.java)) {
            PsiTreeUtil.getParentOfType(it, SqlFunctionCallExpression::class.java)
        }.firstOrNull { candidate ->
            candidate.parameterList?.textRange?.contains(parameters.offset) == true && isTablePosition(candidate)
        } ?: return
        val query = PsiTreeUtil.getParentOfType(parameters.position, SqlQueryExpression::class.java)
        if (query != null && PsiTreeUtil.isAncestor(call.parameterList, query, false)) return
        result.runRemainingContributors(parameters) { completion ->
            val item = completion.lookupElement
            val target = (item.`object` as? SqlResolveResult)?.targetSymbol ?: item.`object` ?: item.psiElement
            val kind = ((target as? DasSymbol)?.dasObject ?: target as? DasObject)?.kind
            if (kind !in setOf(ObjectKind.TABLE, ObjectKind.VIEW, ObjectKind.MAT_VIEW, ObjectKind.SCHEMA, ObjectKind.DATABASE)) {
                result.passResult(completion)
            }
        }
        result.stopHere()
    }
}

class ClickHouseElementFactory(
    private val stock: SqlElementFactoryBase = CHouseParserDefinition().elementFactory,
) : SqlElementFactoryBase by stock {
    override fun createElementNode(type: IElementType): CompositeElement? =
        if (type == SqlCompositeElementTypes.SQL_FUNCTION_CALL) ClickHouseFunctionCall(type)
        else stock.createElementNode(type)
}

private class ClickHouseFunctionCall(type: IElementType) : SqlFunctionCallExpressionImpl(type) {
    override suspend fun DeepRecursiveScope<SqlDasTypeAwareDeepRecursiveElement, DasType>.computeDasType(): DasType =
        RelationTableTypes.ofTableFunction(this@ClickHouseFunctionCall, ClickHouseRelationProfile)
            ?: nameElement?.let { createFunctionReturnType(it, this@ClickHouseFunctionCall) }
            ?: DasTypeSystemBase.UNKNOWN
}
