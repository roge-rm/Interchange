package com.rm.interchange.sim

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

actual object Workers {
    // A core or two left for the screen and the game thread's own work.
    actual val threads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 16)

    private val inside = ThreadLocal.withInitial { false }

    private val pool = Executors.newFixedThreadPool((threads - 1).coerceAtLeast(1)) { r ->
        Thread(r, "interchange-worker").apply { isDaemon = true }
    }

    actual fun run(pieces: Int, body: (Int) -> Unit) {
        if (pieces <= 0) return
        if (pieces == 1 || threads == 1 || inside.get()) {
            for (i in 0 until pieces) body(i)
            return
        }
        // Pieces are taken in turn by whoever is free, the caller included.
        val next = AtomicInteger(0)
        val failed = AtomicReference<Throwable>(null)
        val helpers = minOf(threads - 1, pieces - 1)
        val done = CountDownLatch(helpers)
        val work = {
            inside.set(true)
            try {
                while (true) {
                    val i = next.getAndIncrement()
                    if (i >= pieces || failed.get() != null) break
                    try { body(i) } catch (t: Throwable) { failed.compareAndSet(null, t) }
                }
            } finally {
                inside.set(false)
            }
        }
        repeat(helpers) { pool.execute { try { work() } finally { done.countDown() } } }
        work()
        done.await()
        failed.get()?.let { throw it }
    }
}
