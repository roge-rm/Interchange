package com.rm.interchange.sim

import java.lang.reflect.Modifier

/**
 * For tests: a hash of each of the world's fields, saved or not, followed into the sim's own objects. Two worlds
 * that should be the same can be compared part by part to name what differs. Sets and maps hash the same whatever
 * order they were filled in.
 */
fun World.parts(): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    for (f in fieldsOf(javaClass)) {
        if (f.type == Roster::class.java) continue
        f.isAccessible = true
        out[f.name] = deepHash(f.get(this), HashSet())
    }
    return out
}

/** The names of the parts of [a] and [b] that differ. */
fun partsDiffer(a: World, b: World): List<String> {
    val pa = a.parts()
    val pb = b.parts()
    return pa.keys.filter { pa[it] != pb[it] }
}

private fun fieldsOf(c: Class<*>): List<java.lang.reflect.Field> {
    val all = ArrayList<java.lang.reflect.Field>()
    var k: Class<*>? = c
    while (k != null && k != Any::class.java) {
        all += k.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        k = k.superclass
    }
    return all
}

private fun mix(h: Long, v: Long) = (h xor v) * 0x100000001b3L

private fun deepHash(v: Any?, seen: HashSet<Int>): Long = when (v) {
    null -> 7L
    is Boolean -> if (v) 1L else 2L
    is Number -> v.toLong() * 31 + 3
    is Char -> v.code.toLong()
    is String -> v.hashCode().toLong()
    is Enum<*> -> v.ordinal.toLong()
    is ByteArray -> v.contentHashCode().toLong()
    is ShortArray -> v.contentHashCode().toLong()
    is IntArray -> v.contentHashCode().toLong()
    is LongArray -> v.contentHashCode().toLong()
    is BooleanArray -> v.contentHashCode().toLong()
    is Array<*> -> v.fold(11L) { h, e -> mix(h, deepHash(e, seen)) }
    is Set<*> -> v.sumOf { deepHash(it, seen) } * 13 + v.size
    is Map<*, *> -> v.entries.sumOf { mix(deepHash(it.key, seen), deepHash(it.value, seen)) } * 17 + v.size
    is Iterable<*> -> v.fold(19L) { h, e -> mix(h, deepHash(e, seen)) }
    is Roster -> 23L
    else -> {
        val id = System.identityHashCode(v)
        if (!v.javaClass.name.startsWith("com.rm.interchange") || !seen.add(id)) v.javaClass.name.hashCode().toLong()
        else fieldsOf(v.javaClass).fold(v.javaClass.name.hashCode().toLong()) { h, f ->
            f.isAccessible = true
            mix(h, deepHash(f.get(v), seen))
        }
    }
}

/** For tests: like [parts], but naming fields inside the sim's objects and lists too, as `trains[2].speed`. */
fun World.partPaths(): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    fun walk(path: String, v: Any?, depth: Int) {
        when {
            depth < 3 && v is List<*> -> v.forEachIndexed { i, e -> walk("$path[$i]", e, depth + 1) }
            depth < 3 && v != null && v !is Roster && v !is Enum<*> && v.javaClass.name.startsWith("com.rm.interchange") ->
                for (f in fieldsOf(v.javaClass)) { f.isAccessible = true; walk("$path.${f.name}", f.get(v), depth + 1) }
            else -> out[path] = deepHash(v, HashSet())
        }
    }
    for (f in fieldsOf(javaClass)) {
        if (f.type == Roster::class.java) continue
        f.isAccessible = true
        walk(f.name, f.get(this), 0)
    }
    return out
}
