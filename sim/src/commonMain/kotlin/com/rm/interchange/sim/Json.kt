package com.rm.interchange.sim

/**
 * A small JSON reader for content packs. Objects come back as maps in the
 * order written, arrays as lists, numbers as Long when whole and Double
 * otherwise. A mistake throws [BadJson] with the line and column, so a pack
 * maker can find it.
 */
class BadJson(message: String) : Exception(message)

object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.space()
        val v = p.value()
        p.space()
        if (p.i < text.length) p.fail("more after the end")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun fail(what: String): Nothing {
            var line = 1
            var col = 1
            for (k in 0 until minOf(i, s.length)) if (s[k] == '\n') { line++; col = 1 } else col++
            throw BadJson("line $line, column $col: $what")
        }

        fun space() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun value(): Any? {
            if (i >= s.length) fail("it ends too soon")
            return when (val ch = s[i]) {
                '{' -> obj()
                '[' -> list()
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> if (ch == '-' || ch.isDigit()) number() else fail("unexpected '$ch'")
            }
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) fail("unexpected '${s[i]}'")
            i += w.length
            return v
        }

        fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            space()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                space()
                if (i >= s.length || s[i] != '"') fail("expected a name in quotes")
                val k = string()
                if (k in out) fail("\"$k\" is given twice")
                space()
                if (i >= s.length || s[i] != ':') fail("expected ':' after \"$k\"")
                i++
                space()
                out[k] = value()
                space()
                if (i >= s.length) fail("it ends inside { }")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        fun list(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            space()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                space()
                out += value()
                space()
                if (i >= s.length) fail("it ends inside [ ]")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        fun string(): String {
            val b = StringBuilder()
            i++
            while (true) {
                if (i >= s.length) fail("a string is never closed")
                val ch = s[i++]
                when {
                    ch == '"' -> return b.toString()
                    ch == '\\' -> {
                        if (i >= s.length) fail("a string is never closed")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> b.append(e)
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'n' -> b.append('\n')
                            'r' -> b.append('\r')
                            't' -> b.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("a \\u escape is cut short")
                                b.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: fail("a bad \\u escape"))
                                i += 4
                            }
                            else -> fail("unknown escape \\$e")
                        }
                    }
                    ch < ' ' -> fail("a line break inside a string")
                    else -> b.append(ch)
                }
            }
        }

        fun number(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: fail("a bad number \"$t\"")
        }
    }
}
