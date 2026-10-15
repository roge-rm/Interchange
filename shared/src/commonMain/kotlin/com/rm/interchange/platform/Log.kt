package com.rm.interchange.platform

/** Writes a line to the system log under the tag "Interchange", for events like going out of step. */
expect fun logLine(text: String)
