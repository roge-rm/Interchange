package com.rm.interchange.sim

/**
 * Splits work across the cores. [run] calls its body once for each piece,
 * spread over a pool of threads with the caller taking a share, and returns
 * when all are done.
 *
 * So every copy of the world still comes out the same, each piece writes only
 * its own part of the world or its own slot of a result array, and anything
 * that depends on order is put together afterwards, in piece order, on the
 * calling thread. A body that calls [run] again runs those pieces itself.
 */
expect object Workers {
    /** Threads working, the caller included. */
    val threads: Int

    fun run(pieces: Int, body: (Int) -> Unit)
}

/** Splits 0 until [size] into about [Workers.threads] times four bands and calls [body] with each band's start and end. */
fun parallelBands(size: Int, body: (from: Int, until: Int) -> Unit) {
    val bands = minOf(size, Workers.threads * 4).coerceAtLeast(1)
    Workers.run(bands) { b -> body(size * b / bands, size * (b + 1) / bands) }
}
