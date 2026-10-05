package fr.godox.replikube.core

import fr.godox.replikube.dsl.Palette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * A level's target shape as a flat list of palette ids, and the conversions that turn one
 * into a [VoxelGrid].
 *
 * ### Why the matrix is data and not compiled Kotlin
 *
 * A target used to be defined as "whatever the reference solution produces when compiled".
 * That made level loading depend on the Kotlin compiler: opening a level meant compiling a
 * program, and a compiler that cannot run — a missing `jdk.compiler` module in a jlink runtime,
 * a `kotlin-compiler-embeddable` jar stripped by a packaging step, a bytecode target newer than
 * the bundled JVM — took every level down with it. The failure surfaced as a bare
 * `UnsupportedClassVersionError` naming neither the level nor the cause.
 *
 * Storing the matrix instead removes the compiler from level loading entirely. Loading a level
 * is now a parse, which cannot fail in the ways compilation could. The player still writes
 * Kotlin; that path is untouched and still needs the compiler, but the *campaign* no longer
 * does.
 *
 * ### The layout
 *
 * A single flat array in the grid's own row-major order — **x varies fastest, then y, then z**
 * — which is exactly [indexOf]. Reusing the existing layout means there is one definition of
 * voxel order in the project rather than two, so the matrix cannot be transposed by accident:
 * [VoxelGrid.of] takes the array unchanged and [asIntArray] returns it unchanged.
 *
 * Flat rather than nested `[z][y][x]`. Nested would be easier to eyeball, but a nested array
 * costs two extra bracket pairs per slab and row (98 bracket characters for a 7x7x7 level), and
 * it gains nothing that [indexOf] does not already guarantee. The compactness matters more
 * here than usual: these files are the campaign's entire content, they are read on every
 * launch, and a level author diffing one wants to see the numbers change and nothing else.
 */
object TargetMatrix {

    /**
     * The matrix for [grid]: every coordinate's colour id, row-major.
     *
     * Inverse of [toGrid], so `TargetMatrix.toGrid(size, g.toMatrix()) == g` for any valid grid.
     */
    fun from(grid: VoxelGrid): List<Int> = grid.asIntArray().toList()

    /**
     * The matrix for [grid], written as compact JSON with no spaces.
     *
     * For authoring: pipe this straight into a level file. See [encode].
     */
    fun encode(grid: VoxelGrid): String = buildString {
        append('[')
        grid.asIntArray().forEachIndexed { i, id ->
            if (i > 0) append(',')
            append(id)
        }
        append(']')
    }

    /**
     * Parses a matrix of palette ids into a grid of [size].
     *
     * @throws IllegalArgumentException if the array is the wrong length or holds an id outside
     *   `0..[Palette.MAX]`. Both are authoring mistakes, and both are worth failing loudly on:
     *   a silently short matrix would leave part of the grid undefined, and an out-of-range id
     *   would render as a colour that does not exist.
     */
    fun toGrid(size: GridSize, json: JsonArray): VoxelGrid {
        val cells = json.map { element ->
            // Via `intOrNull` rather than `int`, so a hand-edited file with `"ten"` or `"0x6"`
            // fails with this message instead of a serializer error naming the type.
            val id = (element as? JsonPrimitive)?.intOrNull
            require(id != null && Palette.isValid(id)) {
                "\"$element\" is not a palette id in 0..${Palette.MAX} " +
                    "(${Palette.nameOf(Palette.MAX)} is the last)"
            }
            id
        }
        return toGrid(size, cells)
    }

    /**
     * The matrix as plain ids, for callers that already hold a list (tests, generators).
     *
     * Separated from [toGrid] so the JSON layer is the only place that knows about `JsonArray`.
     */
    fun toGrid(size: GridSize, cells: List<Int>): VoxelGrid {
        require(cells.size == size.voxelCount) {
            "Expected ${size.voxelCount} cells for $size (${size.x}x${size.y}x${size.z}), got ${cells.size}"
        }
        return VoxelGrid.of(size, cells.toIntArray())
    }
}
