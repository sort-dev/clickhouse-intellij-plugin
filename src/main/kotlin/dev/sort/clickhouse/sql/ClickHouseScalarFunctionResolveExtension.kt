package dev.sort.clickhouse.sql

import com.intellij.psi.ResolveState
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.impl.SqlResolveExtension
import dev.brikk.house.sql.metadata.FunctionKind
import dev.sort.clickhouse.ClickHouseDbms
import java.util.concurrent.ConcurrentHashMap

/** Metadata supplies name knowledge, never a guessed signature or return type. */
class ClickHouseScalarFunctionResolveExtension : SqlResolveExtension {
    private val symbols = ConcurrentHashMap<String, BuiltinFunction>()

    override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        val name = reference.element as? SqlReferenceExpression ?: return true
        val call = name.parent as? SqlFunctionCallExpression ?: return true
        if (call.nameElement !== name || name.qualifierExpression != null) return true
        val file = name.containingFile
        if (file.language !== ClickHouseDialect) return true
        if (call.parent?.node?.elementType == SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION) return true
        val text = name.name ?: return true
        // Preserve the platform's richer builtin prototypes and special type rules when it knows them.
        if (CHouseDialect.INSTANCE.supportedFunctions.get(text) != null) return true
        val function = ClickHouseCompletionProfile.functions(file).firstOrNull {
            it.kind != FunctionKind.TABLE_VALUED && it.name.equals(text, ignoreCase = true)
        } ?: return true
        val symbol = symbols.computeIfAbsent(function.name) {
            BuiltinFunction(it, null, emptyMap(), emptyList(), ClickHouseDbms.CLICKHOUSE_BRIKK)
        }
        return processor.execute(symbol, ResolveState.initial())
    }
}
