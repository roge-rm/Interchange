package com.rm.interchange.map

// Written by tools/gen_tiles.py. Don't edit by hand.

/**
 * Where each sprite is in the atlases, files/atlas_<level>.png. Every sprite
 * is a cell [CELL_W] by [CELL_H] at level 0, halved each level after, laid out
 * [COLUMNS] to a row. A sprite's anchor is ([CELL_W] / 2, [CELL_H_ANCHOR]) at
 * level 0: the tile's top corner at the height it stands on.
 *
 * Shapes are which view corners stand a step up: top, right, bottom, left as
 * bits 1, 2, 4 and 8.
 */
object Atlas {
    const val LEVELS = 5
    const val CELL_W = 128
    const val CELL_H = 96
    const val CELL_H_ANCHOR = 16
    const val COLUMNS = 8

    const val GRASS0 = 0
    const val GRASS1 = 1
    const val GRASS2 = 2
    const val GRASS3 = 3
    const val SAND = 4
    const val WATER = 5

    /** The cell for ground of a kind and shape, at kind * 16 + shape, or -1. */
    val GROUND = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, -1, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, -1, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, -1, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, -1, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, -1, 75, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)

    /** The cell for a foundation's walls, at land shape * 16 + top shape, or -1. */
    val WALLS = intArrayOf(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 90, -1, -1, -1, -1, -1, 96, -1, -1, -1, -1, -1, 76, -1, -1, -1, 91, -1, -1, 92, -1, -1, -1, -1, -1, -1, -1, -1, 77, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 78, -1, -1, -1, -1, -1, -1, 93, -1, -1, -1, -1, -1, 94, -1, -1, 79, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 80, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 81, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 82, -1, -1, -1, -1, -1, -1, -1, -1, -1, 97, -1, -1, 95, -1, -1, 83, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 84, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 85, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 86, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 87, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 88, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 89, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)

    /** The cell for a track piece in the view, at piece index * 16 + the shape it lies on, or -1. */
    val TRACK = intArrayOf(98, -1, -1, -1, -1, -1, 99, -1, -1, 100, -1, -1, -1, -1, -1, -1, 101, -1, -1, 102, -1, -1, -1, -1, -1, -1, -1, -1, 103, -1, -1, -1, 104, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 105, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 106, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 107, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1)
}
