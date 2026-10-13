package dev.sort.clickhouse.brikk

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import dev.brikk.house.intellij.BrikkHosts
import dev.brikk.house.intellij.BrikkHostState
import dev.brikk.house.sql.intellij.pipe.PipeText
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorProfile
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.verify.SqlVerifier
import dev.brikk.house.sql.verify.VerifyResult
import dev.sort.clickhouse.validation.ClickHouseNativeErrorProfile
import com.intellij.psi.PsiFile
import java.util.concurrent.atomic.AtomicInteger

class ClickHouseBrikkInjectionTest : ClickHouseBrikkTestCase() {
    fun testRuntimeBrikkSqlAnnotationIsOverriddenByClickHouseRecognition() {
        val host = configure("@BrikkSql fun query(id: Long) = Sql.clickhouse(\"SELECT ${'$'}id AS id\")")
        val file = injected(host)
        assertTrue(file.text, file.text.contains("SELECT :id AS id"))
        assertTrue(ClickHouseHostSql.parsesColonBinds(file))
        assertTrue(ClickHouseHostSql.isInspectionOnly(file))
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
    }

    fun testTrimMarginAndEscapedStringsKeepDecodedSql() {
        val triple = "\"\"\""
        val host = configure("""
            @BrikkSql fun query(id: Long) = Sql.clickhouse(
                $triple
                #SELECT ${'$'}id AS id
                #FROM events
                $triple.trimMargin("#")
            )
        """.trimIndent())
        assertTrue(PipeText.of(injected(host)).text.trim().endsWith("SELECT :id AS id\nFROM events"))
        val escaped = configure("@BrikkSql fun query() = Sql.clickhouse(\"SELECT 'a\\nline' AS value\")")
        assertTrue(PipeText.of(injected(escaped)).text.contains("a\nline"))
    }

    fun testRememberedPendingHostUsesPlaceholdersInsteadOfOldInterpolationRoles() {
        val host = configure("@BrikkSql fun query(id: Long) = Sql.clickhouse(\"SELECT ${'$'}id AS id\")")
        assertTrue(BrikkHosts.state(host) is BrikkHostState.Recognized)
        injected(host)
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val state = BrikkHosts.state(host)
            assertTrue(state.toString(), state is BrikkHostState.Pending)
            val projection = (state as BrikkHostState.Pending).projection
            assertNotNull(projection)
            assertTrue(requireNotNull(projection).entries.filter { it.replacement != null }.all { it.placeholder })
        }
    }

    fun testNeverRecognizedPendingHostIsClaimedWithoutInjection() {
        val host = configure("@BrikkSql fun query(id: Long) = Sql.clickhouse(\"SELECT ${'$'}id\")")
        BrikkHosts.forget(host)
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            val state = BrikkHosts.state(host)
            assertTrue(state.toString(), state is BrikkHostState.Pending)
            assertNull((state as BrikkHostState.Pending).projection)
        }
    }

    fun testPlaceholderStatementsNeverReachServerVerification() {
        val host = configure("@BrikkSql fun query(id: Long) = Sql.clickhouse(\"SELECT ${'$'}{id + 1} AS id\")")
        val file = injected(host)
        assertNotEmpty(requireNotNull(ClickHouseHostSql.marker(file)).placeholders)
        val asked = AtomicInteger()
        val verifier = object : SqlVerifier {
            override val engine = "clickhouse"
            override fun verify(sql: String): VerifyResult {
                asked.incrementAndGet()
                return VerifyResult.Accepted
            }
            override fun verifyExpression(sql: String) = verify(sql)
        }
        val validator = NativeSqlValidator.of(verifier)
        val profile = object : NativeErrorProfile by ClickHouseNativeErrorProfile {
            override fun validator(file: PsiFile) = validator
        }
        val annotator = object : NativeErrorAnnotator(profile) {}
        annotator.doAnnotate(annotator.collectInformation(host.containingFile))
        assertEquals(0, asked.get())
    }
}
