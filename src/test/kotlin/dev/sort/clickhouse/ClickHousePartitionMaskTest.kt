package dev.sort.clickhouse

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlInsertDmlInstruction
import com.intellij.sql.psi.SqlReferenceExpression
import com.intellij.sql.psi.SqlStatement
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.sql.ClickHouseParserDefinition

class ClickHousePartitionMaskTest : ClickHousePlatformTestCase() {
    private fun assertStructured(sql: String) {
        val file = myFixture.configureByText("partition.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        val statements = PsiTreeUtil.findChildrenOfType(myFixture.file, SqlStatement::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, SqlStatement::class.java) == null }
        assertEquals(listOf(sql.removeSuffix(";")), statements.map { it.text })
        assertNotSame(SqlCompositeElementTypes.SQL_STATEMENT, statements.single().node.elementType)
        assertNotNull(PsiTreeUtil.findChildOfType(statements.single(), SqlInsertDmlInstruction::class.java))
        assertEmpty(PsiTreeUtil.findChildrenOfType(myFixture.file, PsiErrorElement::class.java))
        assertRestartsMatchFullLex({ ClickHouseParserDefinition().createLexer(project) }, sql)
    }

    fun testFormatFunctionIsPartOfThePartitionExpression() {
        assertStructured("INSERT INTO FUNCTION null('id UInt64') PARTITION BY format('year={}', toYear(ts)) SELECT id FROM events;")
    }

    fun testPartitionByLookaheadSkipsCommentsAndWhitespace() {
        for (trivia in listOf(" /* c */ ", " -- c\n ")) {
            assertStructured("INSERT INTO TABLE FUNCTION null('id UInt64') PARTITION${trivia}BY toYYYYMM(ts) SELECT id FROM events;")
        }
    }

    fun testDestinationColumnListKeepsItsReferences() {
        val sql = "INSERT INTO FUNCTION null('id UInt64, ts DateTime') PARTITION BY toYYYYMM(ts) /* columns */ (id, ts) /* query */ SELECT id, ts FROM events;"
        assertStructured(sql)
        val start = sql.indexOf("(id, ts)")
        for ((name, offset) in listOf("id" to start + 1, "ts" to start + 5)) {
            val leaf = requireNotNull(myFixture.file.findElementAt(offset))
            val reference = requireNotNull(PsiTreeUtil.getParentOfType(leaf, SqlReferenceExpression::class.java))
            assertEquals(name, reference.text)
            assertEquals(offset, reference.textOffset)
        }
    }
}
