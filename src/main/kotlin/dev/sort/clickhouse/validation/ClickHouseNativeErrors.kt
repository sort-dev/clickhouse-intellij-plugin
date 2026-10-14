package dev.sort.clickhouse.validation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiFile
import com.intellij.lang.Language
import dev.brikk.house.sql.diagnostics.localParseDiagnostics
import dev.brikk.house.sql.dialects.Dialects
import dev.brikk.house.sql.generator.UnsupportedError
import dev.brikk.house.sql.intellij.catalog.DialectDataSourceResolver
import dev.brikk.house.sql.intellij.diagnostics.DataSourceVerification
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorProfile
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorPsiFilter
import dev.brikk.house.sql.intellij.diagnostics.VerificationConnectionListener
import dev.brikk.house.sql.intellij.pipe.isEnabled
import dev.brikk.house.sql.parser.ParseError
import dev.brikk.house.sql.parser.SqlStatementRange
import dev.brikk.house.sql.parser.TokenError
import dev.brikk.house.sql.shape.ParamStyle
import dev.brikk.house.sql.shape.ShapeError
import dev.brikk.house.sql.shape.SqlScriptParameters
import dev.brikk.house.sql.verify.ClickhouseQueryConnection
import dev.brikk.house.sql.verify.ParseOnlyClickhouseVerifier
import dev.sort.clickhouse.ClickHouseDbms
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.brikk.ClickHouseHostSql

object ClickHouseNativeErrorProfile : NativeErrorProfile {
    override val language get() = ClickHouseDialect
    override val dialect = "clickhouse"
    override val label = "ClickHouse"
    override val localParser = true
    override val reportsPsiErrors = false
    override val hostSql get() = ClickHouseHostSql
    override val injectionHosts get() = setOfNotNull(Language.findLanguageByID("kotlin"))

    private val verification = DataSourceVerification(
        DialectDataSourceResolver(ClickHouseDbms.CLICKHOUSE_BRIKK),
        connectionProperties = {
            mapOf("compress" to "false", "decompress" to "false", "ignore_unknown_config_key" to "true")
        },
    ) { connect ->
        ParseOnlyClickhouseVerifier({
            connect()?.let { helper ->
                object : ClickhouseQueryConnection {
                    override fun queryString(sql: String, timeoutSeconds: Int): String? = helper.queryString(sql, timeoutSeconds)
                    override fun cancel() = helper.cancel()
                    override fun close() = helper.close()
                }
            }
        }, checkCanceled = ProgressManager::checkCanceled)
    }

    override fun validator(file: PsiFile) = verification.validator(file)
    override fun modificationCount(file: PsiFile): Long = verification.modificationCount(file)
    override fun verificationText(file: PsiFile, sql: String): String = maskClickHouseIdeBinds(sql) ?: ""

    override fun statementDiagnostics(file: PsiFile, script: String, statement: SqlStatementRange) =
        if (statement.hasPipe && ClickHousePipeDialect.isEnabled(file.project)) {
            // PIPE is editor syntax, not server SQL. Its execution still uses shared lowering.
            localParseDiagnostics(script, statement.start until statement.end, dialect, ProgressManager::checkCanceled)
        } else null
}

class ClickHouseNativeErrorAnnotator : NativeErrorAnnotator(ClickHouseNativeErrorProfile)
class ClickHouseNativePsiFilter : NativeErrorPsiFilter(ClickHouseNativeErrorProfile)
class ClickHouseVerificationConnectionListener : VerificationConnectionListener()

internal fun maskClickHouseIdeBinds(sql: String): String? {
    val parameters = SqlScriptParameters.parse(sql, "clickhouse", positional = true, checkCanceled = ProgressManager::checkCanceled)
    if (parameters.failureOffset != null || parameters.ambiguous.isNotEmpty()) return null
    val binds = parameters.occurrences.filter { it.style == ParamStyle.NAMED_COLON || it.style == ParamStyle.POSITIONAL }
    if (binds.isEmpty()) return sql
    val masked = StringBuilder(sql).apply {
        for (bind in binds) {
            setCharAt(bind.range.start, '0')
            for (offset in bind.range.start + 1 until bind.range.end) {
                if (sql[offset] != '\r' && sql[offset] != '\n') setCharAt(offset, ' ')
            }
        }
    }.toString()
    return try {
        Dialects.forName("clickhouse").parse(masked)
        masked
    } catch (_: ParseError) {
        null
    } catch (_: TokenError) {
        null
    } catch (_: ShapeError) {
        null
    } catch (_: UnsupportedError) {
        null
    }
}
