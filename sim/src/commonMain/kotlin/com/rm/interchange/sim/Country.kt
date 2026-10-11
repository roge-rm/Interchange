package com.rm.interchange.sim

/*
 * What covers the land: the kind of ground on each tile and the trees on it,
 * from Canadian country. North is towards low y, colder; height makes it
 * colder too, so peaks are rock and snow. Moisture decides forest or prairie,
 * and patches of bare granite come through as on the Shield.
 *
 * Laid out from noise drawn in order from the seed, then worked out tile by
 * tile across the cores.
 */

object Ground {
    const val MEADOW = 0
    const val PRAIRIE = 1
    const val FOREST = 2
    const val ROCK = 3
    const val TUNDRA = 4
    const val SNOW = 5
    val NAMES = listOf("Meadow", "Prairie", "Forest", "Rock", "Tundra", "Snow")
}

object Trees {
    const val SPRUCE = 0
    const val PINE = 1
    const val FIR = 2
    const val MAPLE = 3
    const val BIRCH = 4
    const val ASPEN = 5
    val NAMES = listOf("Spruce", "Pine", "Fir", "Maple", "Birch", "Aspen")

    /** A tile's trees packed: the kind in the high bits, how many (0 to 3) in the low two. */
    fun pack(kind: Int, count: Int) = (kind shl 2) or count
    fun kind(packed: Int) = packed shr 2
    fun count(packed: Int) = packed and 3
}

/** Smooth noise over the tiles, 0 to 255, from grids drawn from the seed in order. */
private fun World.noise(spacings: IntArray): IntArray {
    val grids = spacings.map { sp -> IntArray((width / sp + 2) * (height / sp + 2)) { rng.below(256) } }
    val out = IntArray(width * height)
    parallelBands(height) { from, until ->
        for (y in from until until) for (x in 0 until width) {
            var sum = 0
            var weights = 0
            for ((k, sp) in spacings.withIndex()) {
                val gw = width / sp + 2
                val g = grids[k]
                val gx = x / sp
                val gy = y / sp
                val fx = (x % sp) * 256 / sp
                val fy = (y % sp) * 256 / sp
                val a = g[gy * gw + gx] * (256 - fx) + g[gy * gw + gx + 1] * fx
                val b = g[(gy + 1) * gw + gx] * (256 - fx) + g[(gy + 1) * gw + gx + 1] * fx
                val wgt = sp
                sum += (a / 256 * (256 - fy) + b / 256 * fy) / 256 * wgt
                weights += wgt
            }
            out[y * width + x] = sum / weights
        }
    }
    return out
}

/** A number from 0 to 255 for tile ([x], [y]) and [salt], the same every time. */
private fun World.dice(x: Int, y: Int, salt: Int): Int {
    var h = x * 374761393 + y * 668265263 + salt * 1442695041 + seedBits
    h = (h xor (h ushr 13)) * 1274126177
    return (h xor (h ushr 16)) and 255
}

internal fun generateCountry(world: World) {
    with(world) {
        val moisture = noise(intArrayOf(64, 32, 16))
        val shield = noise(intArrayOf(32, 16, 8))
        val warmth = noise(intArrayOf(128, 64))
        parallelBands(height) { from, until ->
            for (y in from until until) for (x in 0 until width) {
                val i = index(x, y)
                if (isWater(x, y)) continue
                val high = maxOf(maxOf(heightAt(x, y), heightAt(x + 1, y)), maxOf(heightAt(x + 1, y + 1), heightAt(x, y + 1)))
                // Warmer to the south, colder with height, with a wander so the bands aren't straight.
                val t = y * 200 / height + warmth[i] / 4 - high * 9
                val m = moisture[i]
                val kind = when {
                    high >= 17 -> Ground.SNOW
                    high >= 13 || shield[i] > 205 -> Ground.ROCK
                    t < 25 -> Ground.TUNDRA
                    m < 105 && t > 110 -> Ground.PRAIRIE
                    m > 140 && t < 140 -> Ground.FOREST
                    else -> Ground.MEADOW
                }
                ground[i] = kind.toByte()
                // Nothing grows on the beach.
                val beach = minOf(minOf(heightAt(x, y), heightAt(x + 1, y)), minOf(heightAt(x + 1, y + 1), heightAt(x, y + 1))) == 0
                if (beach) continue
                val r = dice(x, y, 1)
                val pick = dice(x, y, 2)
                val packed = when (kind) {
                    Ground.FOREST -> Trees.pack(
                        when {
                            pick < 100 -> Trees.SPRUCE
                            pick < 160 -> Trees.FIR
                            pick < 210 -> Trees.PINE
                            pick < 235 -> Trees.BIRCH
                            else -> Trees.ASPEN
                        },
                        if (r < 200) 3 else 2,
                    )
                    Ground.MEADOW -> if (r < 40) Trees.pack(if (t > 130 && pick < 150) Trees.MAPLE else if (pick < 200) Trees.BIRCH else Trees.ASPEN, 1 + r % 2) else 0
                    Ground.PRAIRIE -> if (r < 10) Trees.pack(Trees.ASPEN, 2) else 0
                    Ground.ROCK -> if (r < 45 && high < 13) Trees.pack(Trees.PINE, 1) else 0
                    Ground.TUNDRA -> if (r < 12) Trees.pack(Trees.SPRUCE, 1) else 0
                    else -> 0
                }
                trees[i] = packed.toByte()
            }
        }
    }
}

/** Clears the trees off tile [i], for building, and what that costs. */
internal fun World.clearTrees(i: Int): Long {
    val n = Trees.count(trees[i].toInt())
    if (n == 0) return 0
    trees[i] = 0
    touched(i % width, i / width)
    return Prices.TREE * n
}

/** What clearing the trees off tile [i] would cost. */
fun World.treeCost(i: Int): Long = Prices.TREE * Trees.count(trees[i].toInt())
