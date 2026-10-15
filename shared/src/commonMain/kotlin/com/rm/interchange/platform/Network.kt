package com.rm.interchange.platform

/** What the platform needs to do for the game to be found on the local network. */
object Network {
    /** Called with true while listening for games nearby and false after: Android only hears broadcasts with a multicast lock. */
    var listening: (Boolean) -> Unit = {}

    /** Called with the game's name and how many are playing while it's open to players, and null after: Android keeps the app running for it. */
    var hosting: (String?, Int) -> Unit = { _, _ -> }

    /** Closes the game to players; set by the game, called when the user stops hosting from outside the app. */
    var stopHosting: (() -> Unit)? = null
}
