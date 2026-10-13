package dev.sort.clickhouse

import com.intellij.database.script.ScriptModel
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.sql.psi.SqlCompositeElementTypes
import com.intellij.sql.psi.SqlInsertDmlInstruction
import com.intellij.sql.psi.SqlPsiFacade
import com.intellij.sql.psi.SqlStatement
import dev.brikk.house.sql.intellij.testing.assertRestartsMatchFullLex
import dev.brikk.house.sql.intellij.testing.setSqlDialectMapping
import dev.brikk.house.sql.parser.SqlScriptSnapshot
import dev.sort.clickhouse.sql.ClickHouseDialect
import dev.sort.clickhouse.sql.ClickHouseParserDefinition

class ClickHouseBoundaryTest : ClickHousePlatformTestCase() {
    private fun configure(sql: String): PsiFile {
        val file = myFixture.configureByText("clickhouse.sql", sql)
        setSqlDialectMapping(project, file.virtualFile, ClickHouseDialect)
        return requireNotNull(PsiManager.getInstance(project).findFile(file.virtualFile))
    }

    private fun statements(file: PsiFile): List<SqlStatement> =
        PsiTreeUtil.findChildrenOfType(file, SqlStatement::class.java)
            .filter { PsiTreeUtil.getParentOfType(it, SqlStatement::class.java) == null }

    fun testCorpusPsiAndExecuteAtCaretAgreeWithScriptRanges() {
        for (case in boundaryCorpus) {
            val file = configure(case.sql)
            assertSame(case.name, ClickHouseDialect, file.language)
            val core = SqlScriptSnapshot.parse(case.sql, "clickhouse")
            val terminators = core.statements.mapNotNull { it.terminator }.toSet()
            var from = 0
            val expected = case.statements.map { source ->
                val start = case.sql.indexOf(source, from)
                from = start + source.length
                val end = if (from - 1 in terminators) from - 1 else from
                TextRange(start, start + case.sql.substring(start, end).trimEnd().length)
            }
            val psi = statements(file)
            assertEquals(case.name, expected, psi.map { it.textRange })
            val model = SqlPsiFacade.getInstance(project).createScriptModel(file)
            for (statement in psi) {
                var lineStart = statement.textOffset
                for (line in statement.text.lines()) {
                    val visible = line.indexOfFirst { !it.isWhitespace() }
                    if (visible >= 0 && line.trim() != ";") {
                        val offset = lineStart + visible
                        val queries = model.subModel(ScriptModel.PositionRange(offset)).statements().map { it.query() }.toList()
                        val selected = queries.filter { it != file.text }.ifEmpty { queries }.map(String::trim).distinct()
                        assertEquals("${case.name} default at $offset", statement.text.trim(), selected.first())
                        assertNotNull(case.name, core.at(offset))
                    }
                    lineStart += line.length + 1
                }
            }
        }
    }

    fun testOwnerInsertKeepsErrorFreeStructuredPsiOnOneOrManyLines() {
        val owner = boundaryCorpus.first { it.name == "owner multiline partitioned s3 insert" }.sql
        for (sql in listOf(owner, owner.lines().joinToString(" ") { it.trim() }, owner.replace("INTO FUNCTION", "INTO TABLE FUNCTION"))) {
            val file = configure("$sql\nSELECT 2;")
            val insert = statements(file).first()
            assertEquals(sql.removeSuffix(";"), insert.text)
            assertNotSame(SqlCompositeElementTypes.SQL_STATEMENT, insert.node.elementType)
            assertNotNull(PsiTreeUtil.findChildOfType(insert, SqlInsertDmlInstruction::class.java))
            assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        }
    }

    fun testPartitionMaskLeavesWindowClausesAlone() {
        val file = configure("INSERT INTO events SELECT sum(value) OVER (PARTITION BY id) FROM events;")
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiComment::class.java))
        assertEmpty(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java))
        assertNotNull(PsiTreeUtil.findChildOfType(file, SqlInsertDmlInstruction::class.java))
    }

    fun testCsvQuotedBlankAndTerminatorLinesRemainData() {
        val data = "INSERT INTO events FORMAT CSV\n1,\"first\n\n;\nlast\""
        val sql = "$data\n;\nSELECT 3;"
        val file = configure(sql)
        assertEquals(listOf(data, "SELECT 3"), statements(file).map { it.text.trimEnd() })
        val snapshot = SqlScriptSnapshot.parse(sql, "clickhouse")
        val range = requireNotNull(snapshot.at(sql.indexOf("\n;\n") + 1))
        assertNull(range.boundaryError)
        assertEquals(0, range.start)
        assertEquals(sql.lastIndexOf("\n;\n") + 2, range.end)
        val selected = SqlPsiFacade.getInstance(project).createScriptModel(file)
            .subModel(ScriptModel.PositionRange(sql.indexOf("\n;\n") + 1)).statements()
            .map { it.query() }.filter { it != file.text }.map(String::trim).toList()
        assertEquals(data, selected.first())
    }

    fun testLexerRestartsKeepTheSameTokens() {
        for (case in boundaryCorpus) {
            assertRestartsMatchFullLex({ ClickHouseParserDefinition().createLexer(project) }, case.sql)
        }
    }

    fun testCleanUnterminatedStatementsKeepStockBoundaries() {
        val file = configure("DROP TABLE t\nSELECT 1;")
        assertEquals(listOf("DROP TABLE t", "SELECT 1"), statements(file).map { it.text })
    }
}
