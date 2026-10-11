package com.rm.interchange.sim

/**
 * Everything a player does to the world goes through a command, in single
 * player too. In a multiplayer game the server puts every player's commands in
 * order and each copy of the world runs them on the same tick.
 */
sealed interface Command {
    /** The company giving the command. */
    val company: Int

    data class RaiseLand(override val company: Int, val x: Int, val y: Int) : Command
    data class LowerLand(override val company: Int, val x: Int, val y: Int) : Command
}
