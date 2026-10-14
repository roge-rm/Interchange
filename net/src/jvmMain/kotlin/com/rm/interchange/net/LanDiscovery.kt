package com.rm.interchange.net

import com.rm.interchange.sim.ByteReader
import com.rm.interchange.sim.ByteWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A game heard on the local network, at [address] (filled in by whoever heard it) and [port]. */
data class Beacon(
    val name: String,
    val port: Int,
    val players: Int,
    val protocol: Int,
    /** Whether it needs a password. */
    val locked: Boolean,
    /** Made once per hosted game, so one heard on two networks is listed once. */
    val host: String,
    val year: Int,
    val mapSize: Int,
    val address: String = "",
)

/**
 * Finding games on the local network: a host sends a [Beacon] by UDP
 * broadcast every second on [PORT], and players listening hear it. Android
 * drops broadcasts unless the app holds a multicast lock while listening.
 */
object LanDiscovery {
    const val PORT = 45_687
    /** The port games are hosted on unless set otherwise. */
    const val GAME_PORT = 45_688
    private const val MAGIC = "ICHG1"
    private const val EVERY_MILLIS = 1_000L

    fun encode(b: Beacon): ByteArray = ByteWriter(64).apply {
        string(MAGIC); string(b.name); int(b.port); int(b.players); int(b.protocol); bool(b.locked); string(b.host); int(b.year); int(b.mapSize)
    }.toByteArray()

    /** A beacon from [data], or null if it's something else. */
    fun decode(data: ByteArray, address: String): Beacon? = try {
        val r = ByteReader(data)
        if (r.string() != MAGIC) null
        else Beacon(r.string(), r.int(), r.int(), r.int(), r.bool(), r.string(), r.int(), r.int(), address)
    } catch (_: Exception) {
        null
    }

    /** Sends [beacon]'s latest every second until [stop] says to, on a thread of its own. */
    fun announce(stop: () -> Boolean, beacon: () -> Beacon): Thread = Thread({
        DatagramSocket().use { s ->
            s.broadcast = true
            while (!stop()) {
                val b = encode(beacon())
                for (to in broadcastAddresses()) {
                    try { s.send(DatagramPacket(b, b.size, InetSocketAddress(to, PORT))) } catch (_: Exception) {}
                }
                try { Thread.sleep(EVERY_MILLIS) } catch (_: InterruptedException) { break }
            }
        }
    }, "interchange-announce").apply { isDaemon = true; start() }

    /** Listens for beacons until [stop] says to, handing each to [heard] on a thread of its own. */
    fun listen(stop: () -> Boolean, heard: (Beacon) -> Unit): Thread = Thread({
        try {
            DatagramSocket(null).use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress(PORT))
                s.soTimeout = 500
                val buf = ByteArray(1024)
                while (!stop()) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                    decode(p.data.copyOf(p.length), p.address.hostAddress ?: "")?.let(heard)
                }
            }
        } catch (_: Exception) {
        }
    }, "interchange-discover").apply { isDaemon = true; start() }

    /** Every network's broadcast address, and the all-networks one. */
    private fun broadcastAddresses(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (n in NetworkInterface.getNetworkInterfaces()) {
                if (!n.isUp || n.isLoopback) continue
                for (a in n.interfaceAddresses) a.broadcast?.let { out += it }
            }
        } catch (_: Exception) {
        }
        out += InetAddress.getByName("255.255.255.255")
        // So games on this machine hear each other, as in tests.
        out += InetAddress.getLoopbackAddress()
        return out.distinct()
    }
}
