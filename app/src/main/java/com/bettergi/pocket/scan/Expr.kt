package com.bettergi.pocket.scan

/**
 * stopWhen 表达式求值器（D3：表达式字符串谓词）。
 * 语法：or → and → cmp → atom；比较符 < > <= >= == !=；字面量 int / 标识符（查 vars）。
 * 示例："rarity < 4 && level == 0"。
 */
object Expr {
    class EvalException(msg: String) : Exception(msg)

    fun eval(expr: String, vars: Map<String, Any?>): Boolean {
        val tokens = tokenize(expr)
        val parser = Parser(tokens, vars)
        val v = parser.parseOr()
        parser.expectEnd()
        return truthy(v)
    }

    private sealed interface Tok
    private data class Num(val v: Int) : Tok
    private data class Ident(val name: String) : Tok
    private data class Op(val sym: String) : Tok

    private fun tokenize(s: String): List<Tok> {
        val tokens = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() -> {
                    var j = i
                    while (j < s.length && s[j].isDigit()) j++
                    tokens.add(Num(s.substring(i, j).toInt()))
                    i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    tokens.add(Ident(s.substring(i, j)))
                    i = j
                }
                else -> {
                    val two = if (i + 1 < s.length) s.substring(i, i + 2) else ""
                    val sym = when {
                        two == "<=" || two == ">=" || two == "==" || two == "!=" || two == "&&" || two == "||" -> two
                        c == '<' || c == '>' -> c.toString()
                        else -> throw EvalException("unexpected char '$c' in expr: $s")
                    }
                    tokens.add(Op(sym))
                    i += sym.length
                }
            }
        }
        return tokens
    }

    private class Parser(private val tokens: List<Tok>, private val vars: Map<String, Any?>) {
        private var pos = 0

        fun parseOr(): Long {
            var left = parseAnd()
            while (peekOp("||")) {
                next()
                val r = parseAnd()
                left = if (truthy(left) || truthy(r)) 1 else 0
            }
            return left
        }

        fun parseAnd(): Long {
            var left = parseCmp()
            while (peekOp("&&")) {
                next()
                val r = parseCmp()
                left = if (truthy(left) && truthy(r)) 1 else 0
            }
            return left
        }

        private fun parseCmp(): Long {
            val left = parseAtom()
            val sym = (peek() as? Op)?.sym
            if (sym in setOf("<", ">", "<=", ">=", "==", "!=")) {
                next()
                val r = parseAtom()
                return when (sym) {
                    "<" -> if (left < r) 1 else 0
                    ">" -> if (left > r) 1 else 0
                    "<=" -> if (left <= r) 1 else 0
                    ">=" -> if (left >= r) 1 else 0
                    "==" -> if (left == r) 1 else 0
                    "!=" -> if (left != r) 1 else 0
                    else -> throw EvalException("bad op $sym")
                }
            }
            return left
        }

        private fun parseAtom(): Long = when (val t = peek()) {
            is Num -> { next(); t.v.toLong() }
            is Ident -> { next(); lookup(t.name) }
            else -> throw EvalException("expected atom, got $t")
        }

        private fun lookup(name: String): Long {
            val v = vars[name] ?: return 0
            return when (v) {
                is Int -> v.toLong()
                is Long -> v
                is Boolean -> if (v) 1 else 0
                is Number -> v.toLong()
                else -> throw EvalException("var '$name' is not numeric: $v")
            }
        }

        private fun peekOp(sym: String): Boolean = (peek() as? Op)?.sym == sym
        private fun peek(): Tok = tokens.getOrElse(pos) { Op("<eof>") }
        private fun next(): Tok = tokens.getOrElse(pos++) { Op("<eof>") }
        fun expectEnd() {
            if (pos < tokens.size) throw EvalException("trailing tokens at $pos in expr")
        }
    }

    private fun truthy(v: Long): Boolean = v != 0L
}
