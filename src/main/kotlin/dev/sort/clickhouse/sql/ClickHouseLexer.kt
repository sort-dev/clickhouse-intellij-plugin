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
        if (!functionInsert || parenDepth != 0 || upper != "PARTITION") return false
        val partition = base.currentPosition
        base.advance()
        skipTrivia(base)
        if (!base.tokenText.equals("BY", true)) {
            base.restore(partition)
            return false
        }
        // The stock grammar lacks this INSERT clause. Preserve its structured INSERT/SELECT PSI.
        var depth = 0
        var previous: String? = null
        var expectsOperand = true
        base.advance()
        while (base.tokenType != null) {
            if (isTrivia(base)) {
                base.advance()
                continue
            }
            val text = base.tokenText
            if (text == ";") break
            if (depth == 0 && !expectsOperand) {
                if (text.uppercase() in FOLLOWING_CLAUSES) break
                if (text == "(" && previous?.endsAtom() == true && columnListFollows(base)) break
            }
            when {
                text in OPEN -> depth++
                text in CLOSE -> depth--
            }
            previous = text
            expectsOperand = text.uppercase() in OPERAND_PREFIXES
            base.advance()
        }
        span(base, SqlTokens.SQL_BLOCK_COMMENT, MASKED)
        return true
    }

    private fun columnListFollows(base: Lexer): Boolean {
        val start = base.currentPosition
        try {
            consumeBalanced(base)
            skipTrivia(base)
            return base.tokenText.uppercase() in FOLLOWING_CLAUSES
        } finally {
            base.restore(start)
        }
    }

    private fun skipTrivia(base: Lexer) {
        while (base.tokenType != null && isTrivia(base)) base.advance()
    }

    private fun isTrivia(base: Lexer): Boolean = base.tokenText.isBlank() || base.tokenType in SqlTokens.COMMENT_TOKENS

    private fun String.endsAtom(): Boolean = this in CLOSE || firstOrNull()?.let { it.isDigit() || it in "'\"`" } == true

    private companion object {
        private val FUNCTION_PREFIXES = setOf("INTO", "TABLE")
        private val OPEN = setOf("(", "[", "{")
        private val CLOSE = setOf(")", "]", "}")
        private val FOLLOWING_CLAUSES = setOf("SELECT", "WITH", "VALUES", "FORMAT", "SETTINGS")
        private val OPERAND_PREFIXES = setOf("+", "-", "*", "/", "%", "||", "=", "<", ">", "<=", ">=", "!=", "<>", "?", ":", "AND", "OR", "NOT", "CASE", "WHEN", "THEN", "ELSE")
    }
}
