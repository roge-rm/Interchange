package com.rm.interchange.net

import com.rm.interchange.sim.Command

/**
 * Lockstep, as OpenTTD does it: players send commands to the server, the
 * server stamps each with the tick it runs on and sends it to everyone, and
 * every copy of the world runs it then. Only commands cross the network, never
 * the world, except once when a player joins.
 */
sealed interface Message {
    /** Player to server: run this, please. */
    data class Request(val command: Command) : Message

    /** Server to everyone: run [command] at the start of [tick]. */
    data class Scheduled(val tick: Long, val command: Command) : Message

    /** Server to everyone: you may run up to and including [tick]. */
    data class RunTo(val tick: Long) : Message

    /** Player to server: my world's checksum at [tick], to catch a copy gone out of step. */
    data class Checksum(val tick: Long, val value: Long) : Message
}
