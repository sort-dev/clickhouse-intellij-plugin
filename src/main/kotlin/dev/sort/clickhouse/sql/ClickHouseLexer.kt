package dev.sort.clickhouse.sql

import com.intellij.lexer.Lexer
import com.intellij.sql.dialects.clickhouse.CHouseLexer
import com.intellij.sql.psi.SqlTokens
import dev.brikk.house.sql.intellij.lexer.MaskingSqlLexer

class ClickHouseLexer : MaskingSqlLexer(CHouseLexer()) {
    private var functionInsert = false

    override fun resetStatement() {
        functionInsert = false
    }

    override fun onNote(token: String) {
        if (statementHead == "INSERT" && token == "FUNCTION" && lastMeaningful in FUNCTION_PREFIXES) functionInsert = true
    }

    override fun rule(base: Lexer, upper: String): Boolean {
        if (!functionInsert || parenDepth != 0 || upper != "PARTITION" || !wordsFollow(base, "BY")) return false
        // The stock grammar lacks this INSERT clause. Preserve its structured INSERT/SELECT PSI.
        var depth = 0
        base.advance()
        while (base.tokenType != null) {
            val text = base.tokenText
            when {
                text in OPEN -> depth++
                text in CLOSE -> depth--
                text == ";" || depth == 0 && text.uppercase() in FOLLOWING_CLAUSES -> break
            }
            base.advance()
        }
        span(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
        return true
    }

    private companion object {
        private val FUNCTION_PREFIXES = setOf("INTO", "TABLE")
        private val OPEN = setOf("(", "[", "{")
        private val CLOSE = setOf(")", "]", "}")
        private val FOLLOWING_CLAUSES = setOf("SELECT", "WITH", "VALUES", "FORMAT", "SETTINGS")
    }
}
