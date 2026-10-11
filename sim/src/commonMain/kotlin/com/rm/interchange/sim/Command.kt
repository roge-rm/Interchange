package com.rm.interchange.sim

/**
 * Everything a player does to the world goes through a command, in single
 * player too. In a multiplayer game the server puts every player's commands in
 * order and each copy of the world runs them on the same tick.
 */
sealed interface Command {
    /** The company giving the command. */
    val company: Int

    /** Raises or lowers the corner ([x], [y]) a step, and the land round it as far as it has to. */
    data class Terraform(override val company: Int, val x: Int, val y: Int, val up: Boolean) : Command

    /** Lays or lifts a line of track, as [Track.line] makes it. */
    data class Rail(
        override val company: Int,
        val sx: Int, val sy: Int, val ex: Int, val ey: Int,
        val first: Int,
        val remove: Boolean,
    ) : Command
}
