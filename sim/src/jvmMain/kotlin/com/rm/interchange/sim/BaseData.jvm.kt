package com.rm.interchange.sim

actual object BaseData {
    actual fun read(name: String): ByteArray =
        BaseData::class.java.getResourceAsStream("/base/$name")?.use { it.readBytes() } ?: error("base/$name is missing from the game")
}
