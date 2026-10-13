package dev.sort.clickhouse.brikk

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.PathUtil
import dev.sort.clickhouse.ClickHousePlatformTestCase
import dev.sort.clickhouse.sql.ClickHouseDialect
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import java.io.File

abstract class ClickHouseBrikkTestCase : ClickHousePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        val stdlib = File(PathUtil.getJarPathForClass(Unit::class.java))
        PsiTestUtil.addLibrary(module, "kotlin-stdlib", stdlib.parent, stdlib.name)
        val annotations = File(PathUtil.getJarPathForClass(org.intellij.lang.annotations.Language::class.java))
        PsiTestUtil.addLibrary(module, "language-annotations", annotations.parent, annotations.name)
        val jdk = JavaSdk.getInstance().createJdk("ClickHouse Brikk fixture JDK", System.getProperty("java.home"), false)
        WriteCommandAction.runWriteCommandAction(project) { ProjectJdkTable.getInstance().addJdk(jdk, testRootDisposable) }
        ModuleRootModificationUtil.setModuleSdk(module, jdk)
        myFixture.addFileToProject("runtime/BrikkTypes.kt", """
            package dev.brikk.house.sql.runtime
            import org.intellij.lang.annotations.Language
            annotation class BrikkSql
            annotation class BrikkSqlDialect(val dialect: String)
            interface Partial
            interface Shape : Partial
            annotation class BrikkTrait
            interface Rel<T>
            object Sql {
                @BrikkSqlDialect("clickhouse")
                fun clickhouse(@Language("BrikkSQL") sql: String): Rel<Nothing> = error("inspection fixture")
                @BrikkSqlDialect("duckdb")
                fun duckdb(@Language("DuckDBSQL") sql: String): Rel<Nothing> = error("inspection fixture")
            }
        """.trimIndent())
    }

    protected fun configure(body: String): KtStringTemplateExpression {
        myFixture.configureByText("BrikkSample.kt", """
            import dev.brikk.house.sql.runtime.BrikkSql
            import dev.brikk.house.sql.runtime.BrikkTrait
            import dev.brikk.house.sql.runtime.Rel
            import dev.brikk.house.sql.runtime.Shape
            import dev.brikk.house.sql.runtime.Partial
            import dev.brikk.house.sql.runtime.Sql
            $body
        """.trimIndent())
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        val file = InjectedLanguageManager.getInstance(project).getTopLevelFile(myFixture.file)
        return PsiTreeUtil.findChildrenOfType(file, KtStringTemplateExpression::class.java)
            .last { it.text.contains("SELECT") || it.text.contains("FROM") }
    }

    protected fun injected(host: KtStringTemplateExpression): PsiFile {
        val files = InjectedLanguageManager.getInstance(project).getInjectedPsiFiles(host).orEmpty()
        assertFalse("No ClickHouse injection for ${host.text}", files.isEmpty())
        val file = files.first().first.containingFile
        assertSame(ClickHouseDialect, file.language)
        return file
    }
}
