package com.rm.interchange.sim

/*
 * The rules language: whole-number expressions a pack writes in strings, like
 * "pick(month, 0, 0, 40, 100) * level / 16". Parsed once as a pack loads, into
 * a small tree, and worked out on the sim thread. Long maths only, nothing it
 * can loop on or write to, so it's safe and comes out the same everywhere.
 */

/** What's wrong with an expression, and the column it's at, from 1. */
class BadRule(val column: Int, val what: String) : Exception("column $column: $what")

/** What an expression can read: the names in [Rule.NAMES], and what's been brought of a cargo. */
interface RuleInputs {
    /** The value of name [n], a place in [Rule.NAMES]. */
    fun read(n: Int): Long
    /** What's been brought of cargo [c] last month. */
    fun brought(c: Int): Long
}

/** A parsed expression; [source] is the text it came from. */
class Rule private constructor(val source: String, private val root: Node) {
    fun eval(inputs: RuleInputs): Long = root.eval(inputs)

    override fun toString() = source

    private sealed class Node {
        abstract fun eval(i: RuleInputs): Long
    }

    private class Num(val v: Long) : Node() {
        override fun eval(i: RuleInputs) = v
    }

    private class Name(val n: Int) : Node() {
        override fun eval(i: RuleInputs) = i.read(n)
    }

    private class Brought(val c: Int) : Node() {
        override fun eval(i: RuleInputs) = i.brought(c)
    }

    private class Unary(val op: Char, val a: Node) : Node() {
        override fun eval(i: RuleInputs): Long {
            val v = a.eval(i)
            return if (op == '-') -v else if (v == 0L) 1 else 0
        }
    }

    private class Binary(val op: String, val a: Node, val b: Node) : Node() {
        override fun eval(i: RuleInputs): Long {
            val x = a.eval(i)
            // Both sides only where both count.
            when (op) {
                "&&" -> return if (x != 0L && b.eval(i) != 0L) 1 else 0
                "||" -> return if (x != 0L || b.eval(i) != 0L) 1 else 0
            }
            val y = b.eval(i)
            return when (op) {
                "+" -> x + y
                "-" -> x - y
                "*" -> x * y
                "/" -> if (y == 0L) 0 else x / y
                "%" -> if (y == 0L) 0 else x % y
                "<" -> if (x < y) 1 else 0
                "<=" -> if (x <= y) 1 else 0
                ">" -> if (x > y) 1 else 0
                ">=" -> if (x >= y) 1 else 0
                "==" -> if (x == y) 1 else 0
                else -> if (x != y) 1 else 0
            }
        }
    }

    private class Choose(val test: Node, val a: Node, val b: Node) : Node() {
        override fun eval(i: RuleInputs) = if (test.eval(i) != 0L) a.eval(i) else b.eval(i)
    }

    private class Call(val f: String, val args: List<Node>) : Node() {
        override fun eval(i: RuleInputs): Long = when (f) {
            "min" -> args.minOf { it.eval(i) }
            "max" -> args.maxOf { it.eval(i) }
            "abs" -> args[0].eval(i).let { if (it < 0) -it else it }
            "clamp" -> {
                val lo = args[1].eval(i)
                val hi = args[2].eval(i)
                minOf(maxOf(args[0].eval(i), lo), hi)
            }
            // pick: the i-th of the rest from 0, the first or last past either end.
            else -> args[1 + args[0].eval(i).coerceIn(0L, (args.size - 2).toLong()).toInt()].eval(i)
        }
    }

    companion object {
        /** The names an expression reads, in the order [RuleInputs.read] takes them. */
        val NAMES = listOf("month", "year", "level", "base", "carried", "wet", "snow", "mud", "ice", "stations")
        const val MONTH = 0
        const val YEAR = 1
        const val LEVEL = 2
        const val BASE = 3
        const val CARRIED = 4
        const val WET = 5
        const val SNOW = 6
        const val MUD = 7
        const val ICE = 8
        const val STATIONS = 9

        /** The functions, with the fewest and most things they take. */
        private val FUNCTIONS = mapOf("min" to (2 to 64), "max" to (2 to 64), "clamp" to (3 to 3), "abs" to (1 to 1), "pick" to (2 to 64), "brought" to (1 to 1))

        const val MAX_LENGTH = 1000
        const val MAX_DEPTH = 32
        const val MAX_NODES = 256

        /** [source] parsed, or a [BadRule] saying where it's wrong. */
        fun parse(source: String): Rule {
            if (source.length > MAX_LENGTH) throw BadRule(MAX_LENGTH + 1, "longer than $MAX_LENGTH characters")
            val p = Parser(source)
            val root = p.choose(0)
            p.space()
            if (p.at < source.length) throw BadRule(p.at + 1, "unexpected \"${source[p.at]}\"")
            return Rule(source, root)
        }
    }

    private class Parser(val s: String) {
        var at = 0
        var nodes = 0

        fun space() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        fun node(n: Node): Node {
            if (++nodes > MAX_NODES) throw BadRule(at + 1, "more than $MAX_NODES parts")
            return n
        }

        /** Depth counts brackets, function calls, ?: and a - or ! in front; things chained side by side add none. */
        fun deep(depth: Int) {
            if (depth > MAX_DEPTH) throw BadRule(at + 1, "nested more than $MAX_DEPTH deep")
        }

        /** Takes [op] if it's next. */
        fun take(op: String): Boolean {
            space()
            if (!s.startsWith(op, at)) return false
            // Not the start of a longer operator: < from <=, & from &&, = from ==, ! from !=.
            if (op.length == 1 && op in "<>!" && at + 1 < s.length && s[at + 1] == '=') return false
            at += op.length
            return true
        }

        fun choose(depth: Int): Node {
            deep(depth)
            val test = or(depth)
            if (!take("?")) return test
            val a = choose(depth + 1)
            if (!take(":")) throw BadRule(at + 1, "expected \":\"")
            val b = choose(depth + 1)
            return node(Choose(test, a, b))
        }

        private fun binary(depth: Int, ops: List<String>, next: (Int) -> Node): Node {
            var a = next(depth)
            while (true) {
                val op = ops.firstOrNull { take(it) } ?: return a
                a = node(Binary(op, a, next(depth)))
            }
        }

        fun or(depth: Int): Node = binary(depth, listOf("||")) { and(it) }
        fun and(depth: Int): Node = binary(depth, listOf("&&")) { compare(it) }
        fun compare(depth: Int): Node = binary(depth, listOf("==", "!=", "<=", ">=", "<", ">")) { sum(it) }
        fun sum(depth: Int): Node = binary(depth, listOf("+", "-")) { product(it) }
        fun product(depth: Int): Node = binary(depth, listOf("*", "/", "%")) { unary(it) }

        fun unary(depth: Int): Node {
            deep(depth)
            if (take("-")) return node(Unary('-', unary(depth + 1)))
            if (take("!")) return node(Unary('!', unary(depth + 1)))
            return atom(depth)
        }

        fun atom(depth: Int): Node {
            space()
            if (at >= s.length) throw BadRule(at + 1, "expected a number, a name or (")
            val start = at
            val ch = s[at]
            if (ch == '(') {
                at++
                val inner = choose(depth + 1)
                if (!take(")")) throw BadRule(at + 1, "expected \")\"")
                return inner
            }
            if (ch in '0'..'9') {
                while (at < s.length && s[at] in '0'..'9') at++
                val v = s.substring(start, at).toLongOrNull() ?: throw BadRule(start + 1, "number too big")
                return node(Num(v))
            }
            if (ch.isLetter() || ch == '_') {
                while (at < s.length && (s[at].isLetterOrDigit() || s[at] == '_')) at++
                val word = s.substring(start, at)
                val n = NAMES.indexOf(word)
                if (n >= 0) return node(Name(n))
                val limits = FUNCTIONS[word] ?: throw BadRule(start + 1, "unknown name \"$word\"")
                if (!take("(")) throw BadRule(at + 1, "expected \"(\" after $word")
                if (word == "brought") {
                    space()
                    val cs = at
                    while (at < s.length && (s[at].isLetterOrDigit() || s[at] == '_')) at++
                    val cargo = s.substring(cs, at)
                    val c = Cargo.of(cargo)
                    if (c < 0) throw BadRule(cs + 1, "unknown cargo \"$cargo\"")
                    if (!take(")")) throw BadRule(at + 1, "expected \")\"")
                    return node(Brought(c))
                }
                val args = ArrayList<Node>()
                if (!take(")")) {
                    do args += choose(depth + 1) while (take(","))
                    if (!take(")")) throw BadRule(at + 1, "expected \",\" or \")\"")
                }
                if (args.size < limits.first || args.size > limits.second) {
                    throw BadRule(start + 1, if (limits.first == limits.second) "$word takes ${limits.first}" else "$word takes ${limits.first} to ${limits.second}")
                }
                return node(Call(word, args))
            }
            throw BadRule(start + 1, "unexpected \"$ch\"")
        }
    }
}
