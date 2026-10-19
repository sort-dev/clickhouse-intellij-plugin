package dev.sort.clickhouse.sql

import com.intellij.psi.PsiReference
import com.intellij.psi.ResolveState
import com.intellij.sql.dialects.BuiltinFunction
import com.intellij.sql.dialects.clickhouse.CHouseDialect
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlBinaryExpression
import com.intellij.sql.psi.SqlFunctionCallExpression
import com.intellij.sql.psi.SqlReference
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlScopeProcessor
import com.intellij.sql.psi.impl.SqlResolveExtension
import dev.brikk.house.sql.metadata.FunctionKind
import dev.brikk.house.sql.ast.Query
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.TokenError
import dev.sort.clickhouse.ClickHouseDbms
import java.util.concurrent.ConcurrentHashMap

/** Metadata supplies name knowledge, never a guessed signature or return type. */
class ClickHouseScalarFunctionResolveExtension : SqlResolveExtension {
    private val symbols = ConcurrentHashMap<String, BuiltinFunction>()

    override fun process(reference: SqlReference, processor: SqlScopeProcessor): Boolean {
        // PsiReference.getElement, not the experimental PsiSymbolReference.getElement: the same element.
        val name = (reference as PsiReference).element as? SqlReferenceExpression ?: return true
        val call = name.parent as? SqlFunctionCallExpression ?: return true
        if (call.nameElement !== name || name.qualifierExpression != null) return true
        val file = name.containingFile
        if (file.language !== ClickHouseDialect) return true
        if (call.parent?.node?.elementType == SqlCompositeElementTypes.SQL_TABLE_PROCEDURE_CALL_EXPRESSION) return true
        val text = name.name ?: return true
        val keyword = keywordPredicate(call, text)
        if (keyword != null) {
            val symbol = symbols.computeIfAbsent("keyword:$keyword") {
                BuiltinFunction(keyword, null, emptyMap(), emptyList(), ClickHouseDbms.CLICKHOUSE_BRIKK)
            }
            return processor.execute(symbol, ResolveState.initial())
        }
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

    private fun keywordPredicate(call: SqlFunctionCallExpression, name: String): String? {
        // Stock ClickHouse PSI treats these query predicates as ordinary function calls, including
        // their query argument as a reference. Core must confirm a query, not EXISTS(1) or ALL(1).
        if (call.nameElement?.text != name) return null // Quoted function names are not keywords.
        val keyword = when {
            name.equals("EXISTS", ignoreCase = true) -> "EXISTS"
            name.equals("ALL", ignoreCase = true) -> {
                val comparison = call.parent as? SqlBinaryExpression ?: return null
                if (comparison.rOperand !== call || comparison.opSignElement?.text !in comparisons) return null
                "ALL"
            }
            else -> return null
        }
        val arguments = call.parameterList ?: return null
        val query = try {
            Dialects.forName("clickhouse").parse(arguments.text).singleOrNull() is Query
        } catch (_: ParseError) {
            false
        } catch (_: TokenError) {
            false
        }
        return keyword.takeIf { query }
    }

    private val comparisons = setOf("=", "!=", "<>", "<", "<=", ">", ">=")
}
