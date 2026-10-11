package com.rm.interchange.platform

import com.rm.interchange.sim.Pictures

/** Asks the player for a file, for adding a content pack. */
interface Picker {
    /** Shows the system's file chooser; [onPicked] gets the file's bytes on the main thread, or null if none was picked. */
    fun pickFile(onPicked: (ByteArray?) -> Unit)
}

/** This platform's file chooser and PNG reader. Set up by the app before the screen starts. */
lateinit var picker: Picker
lateinit var pictures: Pictures
