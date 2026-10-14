package com.rm.interchange.net

import com.rm.interchange.sim.BadData
import com.rm.interchange.sim.Command
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
            Message.Request(Command.Loan(2, true)),
            Message.Say("bonjour, ça va"),
            Message.Resync,
            Message.Rejected(Reject.PASSWORD),
            Message.Scheduled(1234567890123L, Command.NewCompany(3, "Ann")),
            Message.Frame(99, 16, -42),
            Message.Chat("Ann", 3, "hi"),
            Message.Players(listOf(Player("Host", 0), Player("Ann", 3), Player("Bo", -1))),
        )
        for (m in all) assertEquals(m, Wire.decode(Wire.encode(m)))
        val w = Message.Welcome(3, "Game", 77, byteArrayOf(1, 2, 3), listOf(Message.Scheduled(78, Command.Loan(3, false))))
        val back = assertIs<Message.Welcome>(Wire.decode(Wire.encode(w)))
        assertEquals(3, back.company)
        assertEquals(77L, back.frame)
        assertContentEquals(w.save, back.save)
        assertEquals(w.pending, back.pending)
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
