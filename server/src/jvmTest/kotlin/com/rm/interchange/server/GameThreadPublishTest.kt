package com.rm.interchange.server

import com.rm.interchange.sim.Command
import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class GameThreadPublishTest {
    private fun waitFor(what: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!what()) {
            assertTrue(System.nanoTime() < until, "timed out")
            Thread.sleep(5)
        }
    }

    /** Paused, the view stays the same object till a command runs or what it's asked to hold changes. */
    @Test
    fun pausedPublishesOnlyOnChange() {
        val runner = GameThread({ World(NewGame(64, 64, 3, 1930, Region.MIXED, towns = 3, industries = 3, rivals = 0)) })
        runner.paused = true
        runner.start()
        try {
            waitFor { runner.view != null }
            // A frame or two for anything already asked for.
            Thread.sleep(100)
            val still = runner.view
            Thread.sleep(300)
            assertSame(still, runner.view)

            val money = runner.money
            runner.request(Command.Loan(0, true))
            waitFor { runner.money != money }
            val afterLoan = runner.view
            assertNotSame(still, afterLoan)
            Thread.sleep(300)
            assertSame(afterLoan, runner.view)

            runner.showNetwork = true
            waitFor { runner.networkView != null }
            val withNetwork = runner.view
            assertNotNull(withNetwork)
            Thread.sleep(700)
            assertSame(withNetwork, runner.view)

            // Running again, it publishes every frame.
            runner.paused = false
            val tick = runner.tick
            waitFor { runner.tick > tick + 3 }
        } finally {
            runner.stop()
            runner.join(5000)
        }
    }
}
