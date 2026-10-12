package dev.sort.clickhouse

import com.intellij.lang.PsiBuilder
import com.intellij.lang.PsiBuilderFactory
import com.intellij.sql.dialects.clickhouse.CHouseLexer
import com.intellij.sql.dialects.clickhouse.CHouseParser
import com.intellij.sql.dialects.clickhouse.CHouseParserDefinition
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.brikk.house.sql.intellij.parser.parseLenientStatement
import dev.brikk.house.sql.intellij.parser.wordAt
import kotlin.test.assertFailsWith

class SubstrateGapTest : BasePlatformTestCase() {
    fun testKnownStockFactoryGapForSharedLenientStatements() {
        val sql = boundaryCorpus.first { it.name == "multiline insert with comments" }.sql
        val builder = PsiBuilderFactory.getInstance().createBuilder(CHouseParserDefinition(), CHouseLexer(), sql)
        val parser = object : CHouseParser() {
            override fun parseSqlStatement(builder: PsiBuilder, level: Int): Boolean =
                if (builder.wordAt(0) == "INSERT") builder.parseLenientStatement()
                else super.parseSqlStatement(builder, level)
        }
        val root = parser.parse(CHouseParserDefinition.CH_SQL_FILE, builder)
        val statement = requireNotNull(root.firstChildNode)
        assertEquals(SqlCompositeElementTypes.SQL_STATEMENT, statement.elementType)
        assertEquals(sql.removeSuffix(";"), statement.text)
        // Stock's package-private factory cannot create PSI for the shared generic statement.
        assertFailsWith<AssertionError> { CHouseParserDefinition().createElement(statement) }
    }
}
