package dev.sort.clickhouse

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect

class ClickHouseErrorHighlightTest : ClickHousePlatformTestCase() {
    private fun configure(sql: String) {
        val file = myFixture.configureByText("errors.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
    }

    fun testGenuineSyntaxErrorsAreHighlighted() {
        configure("SELECT FROM WHERE 1 +; SELCT 1 FORM t;")
        assertNotEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        assertNotEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }

    fun testOwnerExportHasNoSyntaxErrorsOrErrorHighlights() {
        configure(boundaryCorpus.first { it.name == "owner multiline partitioned s3 insert" }.sql)
        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.ERROR))
    }
}
