package com.rm.interchange.platform

/** What the app tells the screen about being put away and brought back. */
object Lifecycle {
    /** Called when the app is put away: the game saves itself. */
    var onHide: (() -> Unit)? = null
}
