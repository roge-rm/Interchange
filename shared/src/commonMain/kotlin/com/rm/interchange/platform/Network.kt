package com.rm.interchange.platform

/** What the platform needs to do for the game to be found on the local network. */
object Network {
    /** Called with true while listening for games nearby and false after: Android only hears broadcasts with a multicast lock. */
    var listening: (Boolean) -> Unit = {}
}
