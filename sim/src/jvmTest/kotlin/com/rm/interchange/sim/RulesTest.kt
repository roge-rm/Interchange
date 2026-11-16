package com.rm.interchange.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RulesTest {
    /** Each name reads as its place in [Rule.NAMES] plus 100, so month is 100; brought(c) is c times 1000. */
    private val inputs = object : RuleInputs {
        override fun read(n: Int) = 100L + n
        override fun brought(c: Int) = c * 1000L
    }

    private fun eval(src: String) = Rule.parse(src).eval(inputs)

    private fun bad(src: String) = assertFailsWith<BadRule> { Rule.parse(src) }

    @Test
    fun operatorsGoByPrecedence() {
        assertEquals(7, eval("1 + 2 * 3"))
        assertEquals(9, eval("(1 + 2) * 3"))
        assertEquals(1, eval("7 - 3 - 3"))
        assertEquals(2, eval("12 / 3 / 2"))
        assertEquals(-5, eval("-2 - 3"))
        assertEquals(1, eval("1 + 1 == 2"))
        assertEquals(1, eval("2 < 3 && 3 <= 3 && 4 > 3 && 4 >= 4 && 1 != 2"))
        assertEquals(1, eval("0 || 5"))
        assertEquals(0, eval("!7"))
        assertEquals(1, eval("!0"))
        assertEquals(20, eval("1 < 2 ? 1 > 2 ? 10 : 20 : 30"))
        assertEquals(1, eval("0 && 1 || 1"))
        assertEquals(-3, eval("-7 / 2"))
        assertEquals(-1, eval("-7 % 2"))
        assertEquals(3_000_000_000L, eval("3000000 * 1000"))
    }

    @Test
    fun divisionByZeroGivesZero() {
        assertEquals(0, eval("5 / 0"))
        assertEquals(0, eval("5 % (2 - 2)"))
        assertEquals(1, eval("1 + 5 / 0"))
    }

    @Test
    fun functions() {
        assertEquals(2, eval("min(5, 2, 9)"))
        assertEquals(9, eval("max(5, 2, 9)"))
        assertEquals(10, eval("clamp(50, 0, 10)"))
        assertEquals(0, eval("clamp(-5, 0, 10)"))
        assertEquals(4, eval("clamp(4, 0, 10)"))
        assertEquals(6, eval("abs(-6)"))
        assertEquals(30, eval("pick(2, 10, 20, 30, 40)"))
        assertEquals(10, eval("pick(-1, 10, 20, 30)"))
        assertEquals(30, eval("pick(99, 10, 20, 30)"))
        assertEquals(Cargo.GRAIN * 1000L, eval("brought(grain)"))
    }

    @Test
    fun namesReadTheirInputs() {
        for ((n, name) in Rule.NAMES.withIndex()) assertEquals(100L + n, eval(name))
        assertEquals(100L + Rule.LEVEL + 1, eval("level+1"))
    }

    @Test
    fun badRulesSayWhereTheyreWrong() {
        assertEquals(5, bad("1 + foo").column)
        assertEquals("unknown name \"foo\"", bad("1 + foo").what)
        assertEquals(11, bad("brought(  sand)").column)
        assertEquals(7, bad("(1 + 2").column)
        assertEquals(3, bad("1 2").column)
        assertEquals(1, bad("").column)
        assertEquals(1, bad("abs(1, 2)").column)
        assertEquals(1, bad("clamp(1, 2)").column)
        assertEquals(3, bad("1 & 2").column)
        assertEquals(1, bad("pick(1)").column)
    }

    @Test
    fun limitsAreRefused() {
        assertEquals(Rule.MAX_LENGTH + 1, bad("1" + " ".repeat(Rule.MAX_LENGTH)).column)
        // 32 brackets deep is fine, 33 isn't.
        Rule.parse("(".repeat(32) + "1" + ")".repeat(32))
        bad("(".repeat(33) + "1" + ")".repeat(33))
        bad("-".repeat(40) + "1")
        // Long sums side by side are fine up to the node count.
        Rule.parse((1..128).joinToString("+"))
        bad((1..200).joinToString("+"))
        bad("99999999999999999999")
    }
}
