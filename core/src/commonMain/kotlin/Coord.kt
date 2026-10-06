package fr.godox.replikube.core

import kotlinx.serialization.Serializable

/**
 * A voxel coordinate.
 *
 * The grid is centred on the origin, so a level of size `(sx, sy, sz)` spans
 * `[-sx/2, (sx-1)/2]` on each axis. Negative `y` is the bottom layer.
 */
@Serializable
data class Coord(val x: Int, val y: Int, val z: Int) {
    override fun toString(): String = "($x, $y, $z)"
}

/** Dimensions of a grid. */
@Serializable
data class GridSize(val x: Int, val y: Int, val z: Int) {
    val voxelCount: Int get() = x * y * z

    init {
        require(x > 0 && y > 0 && z > 0) { "GridSize must be positive, got $this" }
    }

    /**
     * Lowest and highest coordinate on each axis, inclusive.
     *
     * Odd dimensions straddle the origin symmetrically; even dimensions are offset by half,
     * giving the larger half to the positive side. `x / 2` (not `x / 2.0`) is deliberate:
     * it keeps the span exactly [x] wide for both parities.
     */
    val bounds: CoordinateBounds
        get() = CoordinateBounds(
            minX = -(x / 2),
            maxX = (x - 1) / 2,
            minY = -(y / 2),
            maxY = (y - 1) / 2,
            minZ = -(z / 2),
            maxZ = (z - 1) / 2,
        )
}

/** Inclusive lower/upper coordinate limits per axis. */
data class CoordinateBounds(
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val minZ: Int,
    val maxZ: Int,
) {
    operator fun contains(c: Coord): Boolean =
        c.x in minX..maxX && c.y in minY..maxY && c.z in minZ..maxZ
}

/** Whether [c] lies inside a grid of this size. */
fun GridSize.contains(c: Coord): Boolean = c in bounds

/**
 * Every coordinate in the grid, in row-major order.
 *
 * Row-major is defined by [indexOf]: **x varies fastest**, then y, then z. Anything that
 * walks the grid linearly must use this (or iterate [coords]) so it agrees with
 * [VoxelGrid.get] and the serialised layout.
 */
fun GridSize.coords(): Sequence<Coord> = sequence {
    val b = bounds
    for (z in b.minZ..b.maxZ) {
        for (y in b.minY..b.maxY) {
            for (x in b.minX..b.maxX) {
                yield(Coord(x, y, z))
            }
        }
    }
}

/** The six axis-aligned neighbours of [c], ignoring those outside the grid. */
fun GridSize.neighboursOf(c: Coord): List<Coord> =
    listOf(
        Coord(c.x + 1, c.y, c.z),
        Coord(c.x - 1, c.y, c.z),
        Coord(c.x, c.y + 1, c.z),
        Coord(c.x, c.y - 1, c.z),
        Coord(c.x, c.y, c.z + 1),
        Coord(c.x, c.y, c.z - 1),
    ).filter { it in bounds }
