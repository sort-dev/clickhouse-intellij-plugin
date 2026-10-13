package dev.sort.clickhouse

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.lang.annotation.Annotator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorAnnotator
import dev.brikk.house.sql.intellij.diagnostics.NativeErrorProfile
import dev.brikk.house.sql.intellij.pipe.NativeSqlValidator
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.verify.SqlVerifier
import dev.brikk.house.sql.verify.VerifyError
import dev.brikk.house.sql.verify.VerifyResult
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.sort.clickhouse.pipe.ClickHousePipeDialect
import dev.sort.clickhouse.pipe.ClickHousePipeSettings
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.validation.ClickHouseNativeErrorProfile
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import com.intellij.testFramework.PlatformTestUtil

class ClickHouseNativeDiagnosticsTest : ClickHousePlatformTestCase() {
    private class RecordingVerifier(private val answer: (String) -> VerifyResult) : SqlVerifier {
        val asked = CopyOnWriteArrayList<String>()
        override val engine = "clickhouse"
        override fun verify(sql: String): VerifyResult {
            asked += sql
            return answer(sql)
        }
        override fun verifyExpression(sql: String): VerifyResult = verify(sql)
    }

    private fun annotations(sql: String, verifier: SqlVerifier? = null): List<TextRange> {
        val configured = myFixture.configureByText("native-diagnostics.sql", sql)
        setSqlDialectMapping(project, configured.virtualFile, ClickHouseDialect)
        val validator = verifier?.let { NativeSqlValidator.of(it) }
        val profile = object : NativeErrorProfile by ClickHouseNativeErrorProfile {
            override fun validator(file: PsiFile) = validator ?: ClickHouseNativeErrorProfile.validator(file)
        }
        val annotator = object : NativeErrorAnnotator(profile) {}
        val snapshot = annotator.collectInformation(myFixture.file)
        val outcome = ApplicationManager.getApplication().executeOnPooledThread(Callable {
            annotator.doAnnotate(snapshot)
        }).get(30, TimeUnit.SECONDS)
        val holder = AnnotationHolderImpl(AnnotationSession(myFixture.file), false)
        holder.runAnnotatorWithContext(myFixture.file, Annotator { _, active -> annotator.apply(myFixture.file, outcome, active) })
        holder.assertAllAnnotationsCreated()
        return holder.map { TextRange(it.startOffset, it.endOffset) }
    }

    fun testServerRejectionIsShownAtItsUtf16Range() {
        val sql = "SELECT '😀', server_gap FROM events;"
        val start = sql.indexOf("server_gap")
        val verifier = RecordingVerifier { checked -> VerifyResult.Rejected(VerifyError.atOffset(checked, "Recorded server rejection", start, start + "server_gap".length)) }
        assertContainsElements(annotations(sql, verifier), TextRange(start, start + "server_gap".length))
        assertEquals(listOf(sql.removeSuffix(";")), verifier.asked.toList())
    }

    fun testServerAcceptanceClearsALocalFalsePositiveInTheVerifiedStatement() {
        val sql = "INSERT INTO events FORMAT Values (1, 'value');"
        assertNotEmpty(annotations(sql))
        assertEmpty(annotations(sql, RecordingVerifier { VerifyResult.Accepted }))
    }

    fun testNoDataSourceAndUnavailableKeepLocalErrors() {
        val sql = "SELECT * FROM events WHERE = 1;"
        val local = annotations(sql)
        assertNotEmpty(local)
        assertNull(ClickHouseNativeErrorProfile.validator(myFixture.file))
        assertSameElements(local, annotations(sql, RecordingVerifier { VerifyResult.Unavailable("Disconnected") }))
        assertSameElements(local, annotations(sql, RecordingVerifier { VerifyResult.Unverified("Not checked") }))
    }

    fun testVerificationReceivesMaskedIdeBindsButUnchangedTypedParameters() {
        val sql = "SELECT :limit, ?, {server:UInt64};"
        val verifier = RecordingVerifier { VerifyResult.Accepted }
        annotations(sql, verifier)
        assertEquals(listOf("SELECT 0     , 0, {server:UInt64}"), verifier.asked.toList())
    }

    fun testUncertainMasksNeverAskTheVerifierOrClearLocalErrors() {
        for (sql in listOf(
            "SELECT x ?",
            "SELECT x ?:y",
            "SELECT FROM WHERE ? +;",
            "SELECT FROM WHERE :name +;",
        )) {
            val local = annotations(sql)
            val verifier = RecordingVerifier { VerifyResult.Accepted }
            assertSameElements(local, annotations(sql, verifier))
            assertEmpty(sql, verifier.asked)
        }
    }

    fun testVmVetoDoesNotTreatRawPipeSyntaxAsEnabledEditorSyntax() {
        val configured = myFixture.configureByText("veto-diagnostics.sql", "SELECT 1 AS id |> WHERE id > 0")
        setSqlDialectMapping(project, configured.virtualFile, ClickHouseDialect)
        val settings = project.service<ClickHousePipeSettings>()
        val previous = settings.enabled
        val flag = System.getProperty(ClickHousePipeDialect.vetoProperty)
        try {
            System.clearProperty(ClickHousePipeDialect.vetoProperty)
            settings.enabled = true
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val sql = myFixture.file.text
            val statement = SqlScriptSnapshot.parse(sql, "clickhouse").statements.single()
            assertNotNull(ClickHouseNativeErrorProfile.statementDiagnostics(myFixture.file, sql, statement))
            System.setProperty(ClickHousePipeDialect.vetoProperty, "false")
            assertNull(ClickHouseNativeErrorProfile.statementDiagnostics(myFixture.file, sql, statement))
        } finally {
            settings.enabled = previous
            if (flag == null) System.clearProperty(ClickHousePipeDialect.vetoProperty)
            else System.setProperty(ClickHousePipeDialect.vetoProperty, flag)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
        }
    }
}
