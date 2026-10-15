package com.rm.interchange.server

import com.rm.interchange.sim.NewGame
import com.rm.interchange.sim.Region
import com.rm.interchange.sim.World
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class GameThreadStopTest {
    /** Stopping the game thread at any moment, mid-step in the workers or between frames, leaves no uncaught exception. */
    @Test
    fun stoppingAtAnyMomentEndsTheThreadQuietly() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> failures += e }
        try {
            val random = Random(5)
            repeat(40) {
                val runner = GameThread({ World(NewGame(96, 96, 7, 1930, Region.MIXED, towns = 6, industries = 6, rivals = 1)) })
                runner.speed = 8
                runner.start()
                Thread.sleep(random.nextLong(5, 400))
                runner.stop()
                runner.join(5000)
            }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
        assertEquals(emptyList(), failures.toList())
    }
}
