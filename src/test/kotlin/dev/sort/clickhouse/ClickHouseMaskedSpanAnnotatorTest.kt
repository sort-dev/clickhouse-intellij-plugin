package dev.sort.clickhouse

import com.intellij.codeInsight.daemon.impl.AnnotationHolderImpl
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.annotation.AnnotationSession
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.util.PsiTreeUtil
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.sql.ClickHouseMaskedSpanAnnotator

class ClickHouseMaskedSpanAnnotatorTest : ClickHousePlatformTestCase() {
    fun testRegisteredAnnotatorCoversTokensWithinTheMaskedPartitionClause() {
        val sql = "INSERT INTO FUNCTION null('id UInt64') PARTITION BY toYear(ts) SELECT id FROM events;"
        val file = myFixture.configureByText("masked.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        val annotator = LanguageAnnotators.INSTANCE.allForLanguage(myFixture.file.language)
            .filterIsInstance<ClickHouseMaskedSpanAnnotator>().single()
        val mask = PsiTreeUtil.findChildrenOfType(myFixture.file, PsiComment::class.java)
            .single { it.text.startsWith("PARTITION") }
        val holder = AnnotationHolderImpl(AnnotationSession(myFixture.file), false)
        holder.runAnnotatorWithContext(mask, annotator)
        holder.assertAllAnnotationsCreated()
        val ranges = holder.map { TextRange(it.startOffset, it.endOffset) }
        val start = sql.indexOf("PARTITION BY")
        assertContainsElements(ranges, TextRange(start, start + "PARTITION BY".length))
        assertTrue(ranges.toString(), ranges.all { mask.textRange.contains(it) })
    }
}
