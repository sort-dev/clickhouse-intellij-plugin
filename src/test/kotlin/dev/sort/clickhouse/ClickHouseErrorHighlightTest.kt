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

    fun testMaskedBindFallbackHasNoErrorHighlightsAfterCoreColumnListFix() {
        val owner = boundaryCorpus.first { it.name == "owner multiline partitioned s3 insert" }.sql
        for (sql in listOf(
            "INSERT INTO FUNCTION null('id UInt64') PARTITION BY toString(:partition) SELECT 1 AS id;",
            owner.replace("'year='", ":prefix"),
            "INSERT INTO FUNCTION null('id UInt64, ts DateTime') PARTITION BY concat(:prefix, toString(ts)) (id, ts) SELECT id, ts FROM events;",
        )) {
            configure(sql)
            assertEmpty(sql, myFixture.doHighlighting(HighlightSeverity.ERROR))
        }
    }

    fun testGenuineErrorsAfterAMaskedBindFallbackRemainVisible() {
        val insert = "INSERT INTO FUNCTION null('id UInt64') PARTITION BY toString(:partition) SELECT 1 AS id;"
        configure("$insert\nSELECT FROM WHERE 1 +;")
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).any { it.startOffset > insert.length })
    }
}
