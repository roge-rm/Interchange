package com.rm.interchange.net

import com.rm.interchange.sim.BadData
import com.rm.interchange.sim.ByteWriter
import com.rm.interchange.sim.Command
import com.rm.interchange.sim.PackRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WireTest {
    @Test
    fun messagesComeBackAsTheyWent() {
        val all = listOf(
            Message.Hello(Wire.PROTOCOL, "abc", "Ann", "pw", Message.NEW_COMPANY),
            Message.Hello(Wire.PROTOCOL, "abc", "Ann", "", 2, "ours"),
            Message.Look(Wire.PROTOCOL),
            Message.Lock("ours"),
            Message.PackGet(Wire.PROTOCOL, PackRef("prairie", 3, -77L)),
            Message.Request(Command.Loan(2, true)),
            Message.Say("bonjour, ça va"),
            Message.Resync,
            Message.Rejected(Reject.PASSWORD),
            Message.Scheduled(1234567890123L, Command.NewCompany(3, "Ann")),
            Message.Frame(99, 16, -42),
            Message.Chat("Ann", 3, "hi"),
            Message.Players(listOf(Player("Host", 0), Player("Ann", 3), Player("Bo", -1))),
            Message.Players(listOf(Player("Ann", 3)), listOf(CompanyInfo(0, "Host & Co", 1, false), CompanyInfo(3, "Ann", 2, true, com.rm.interchange.sim.Insignia.presets[0]))),
        )
        for (m in all) assertEquals(m, Wire.decode(Wire.encode(m)))
        val w = Message.Welcome(3, "Game", 77, byteArrayOf(1, 2, 3), listOf(Message.Scheduled(78, Command.Loan(3, false))))
        val back = assertIs<Message.Welcome>(Wire.decode(Wire.encode(w)))
        assertEquals(3, back.company)
        assertEquals(77L, back.frame)
        assertContentEquals(w.save, back.save)
        assertEquals(w.pending, back.pending)
        val part = assertIs<Message.PackPart>(Wire.decode(Wire.encode(Message.PackPart(PackRef("prairie", 3, -77L), 5, 9, byteArrayOf(4, 5)))))
        assertEquals(PackRef("prairie", 3, -77L), part.ref)
        assertEquals(5 to 9, part.offset to part.total)
        assertContentEquals(byteArrayOf(4, 5), part.bytes)
    }

    @Test
    fun anOldHelloIsReadAsAnotherVersion() {
        // A hello as protocol 1 wrote it, without the company password.
        val w = ByteWriter(64)
        w.byte(1); w.int(1); w.string("abc"); w.string("Ann"); w.string(""); w.int(Message.NEW_COMPANY)
        assertEquals(1, assertIs<Message.Hello>(Wire.decode(w.toByteArray())).protocol)
    }

    @Test
    fun rubbishIsTurnedAway() {
        assertFailsWith<BadData> { Wire.decode(byteArrayOf(99)) }
        assertFailsWith<BadData> { Wire.decode(byteArrayOf(8)) }
    }

    @Test
    fun beaconsComeBackAsTheyWent() {
        val b = Beacon("Dan's game", 45688, 3, Wire.PROTOCOL, true, "id", 1930, 256, "10.0.0.5")
        assertEquals(b, LanDiscovery.decode(LanDiscovery.encode(b), "10.0.0.5"))
        assertEquals(null, LanDiscovery.decode(byteArrayOf(1, 2, 3), ""))
    }
}
