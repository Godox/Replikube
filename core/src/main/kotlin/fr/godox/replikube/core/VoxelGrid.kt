package fr.godox.replikube.core

import fr.godox.replikube.dsl.Palette

/**
 * An immutable, fully-populated grid of colour ids.
 *
 * Every coordinate in [size] always has a value; `EMPTY` is a value, not an absence.
 * That keeps the renderer and the diff free of null handling.
 */
class VoxelGrid private constructor(
    val size: GridSize,
    private val cells: IntArray,
) {
    /** Colour id at [c]. [EMPTY] when the coordinate holds nothing. */
    operator fun get(c: Coord): Int = when {
        c !in size.bounds -> Palette.EMPTY
        else -> cells[size.indexOf(c)]
    }

    operator fun get(x: Int, y: Int, z: Int): Int = get(Coord(x, y, z))

    /** Whether this grid has a non-[EMPTY] voxel at [c]. */
    fun isSolid(c: Coord): Boolean = get(c) != Palette.EMPTY

    /** Number of non-[EMPTY] voxels. */
    val solidCount: Int get() = cells.count { it != Palette.EMPTY }

    /** Coordinates with a non-[EMPTY] colour. */
    fun solidCoords(): List<Coord> = size.coords().filter { cells[size.indexOf(it)] != Palette.EMPTY }.toList()

    /** Coordinates in row-major order, for rendering and tests. */
    fun allCoords(): List<Coord> = size.coords().toList()

    /** Distinct colour ids present, ascending. */
    fun colorsUsed(): List<Int> = cells.distinct().sorted()

    /**
     * Whether every cell holds a valid palette id.
     *
     * Player code is untrusted input; an out-of-range id is a failure, not a colour.
     */
    fun hasValidColors(): Boolean = cells.all { Palette.isValid(it) }

    /** The first out-of-range cell, or `null` when all cells are valid. */
    fun firstInvalidCell(): Coord? =
        size.coords().firstOrNull { !Palette.isValid(cells[size.indexOf(it)]) }

    /** Read-only view over the backing array, row-major. */
    fun asIntArray(): IntArray = cells.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || (other is VoxelGrid && size == other.size && cells.contentEquals(other.cells))

    override fun hashCode(): Int = 31 * size.hashCode() + cells.contentHashCode()

    override fun toString(): String = "VoxelGrid($size, ${solidCount} solid)"

    companion object {
        /** An empty grid of [size]. */
        fun empty(size: GridSize): VoxelGrid = VoxelGrid(size, IntArray(size.voxelCount))

        /** A grid of [size] where every coordinate returns the same [color]. */
        fun filled(size: GridSize, color: Int): VoxelGrid = VoxelGrid(size, IntArray(size.voxelCount) { color })

        /** A grid built from [values], row-major; used for deserialization and tests. */
        fun of(size: GridSize, values: IntArray): VoxelGrid {
            require(values.size == size.voxelCount) {
                "Expected ${size.voxelCount} cells for $size, got ${values.size}"
            }
            return VoxelGrid(size, values.copyOf())
        }

        /**
         * A grid built from [block], called once per coordinate in row-major order.
         *
         * Not `inline`: it would have to expose the private constructor, and the lambda
         * allocation is irrelevant next to the work of filling a grid.
         */
        fun build(size: GridSize, block: (x: Int, y: Int, z: Int) -> Int): VoxelGrid {
            val cells = IntArray(size.voxelCount)
            // Via coords() rather than hand-rolled loops: the fill order must match
            // indexOf, and there is exactly one definition of that order.
            var i = 0
            for (c in size.coords()) cells[i++] = block(c.x, c.y, c.z)
            return VoxelGrid(size, cells)
        }
    }
}

/**
 * Flat row-major index of [c] within this grid. **x varies fastest**, then y, then z.
 *
 * The single definition of the grid's memory layout: [VoxelGrid.asIntArray],
 * [VoxelGrid.get] and [coords] all agree with it.
 */
fun GridSize.indexOf(c: Coord): Int {
    val b = bounds
    return (c.x - b.minX) + x * ((c.y - b.minY) + y * (c.z - b.minZ))
}
