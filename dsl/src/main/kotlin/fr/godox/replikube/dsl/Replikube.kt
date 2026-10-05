package fr.godox.replikube.dsl

/**
 * The contract every solution implements.
 *
 * A solution writes **the body of `block` and nothing else**. Not the signature, not the
 * return type, not the `fun` keyword — the game supplies those:
 *
 * ```kotlin
 * when (y) {
 *     1 -> RED
 *     0 -> YELLOW
 *     else -> null
 * }
 * ```
 *
 * ### Why a body rather than a declaration
 *
 * The signature is the one part of a solution that is never *yours*. It is identical for
 * all twenty levels, it carries no information about the puzzle, and a player who has to
 * type it can only get it wrong in ways that have nothing to do with the shape they are
 * trying to build. Removing it leaves exactly the part that is theirs.
 *
 * ### `null` means no cube
 *
 * [block] returns `Int?`, and `null` means "leave this voxel empty". That is the same
 * idea as `EMPTY` (0), stated in a way that needs no knowledge of the palette: `null` is
 * the one value in Kotlin that cannot be confused with a colour, so `if (r <= 2) RED else
 * null` says "a cube, or nothing" without the player having to remember which number 0 is.
 * `EMPTY` still works and still means 0, so a solution written against the older contract
 * keeps compiling.
 *
 * A `when` used as an expression still needs an `else` on this compiler — Kotlin 2.4
 * rejects a non-exhaustive one — so the empty case is written `else -> null`.
 *
 * Anything that *is* a cube is a palette constant. See [Palette].
 *
 * ### What a body can contain
 *
 * Anything a function body can: `val`s, local `fun`s, loops, early `return`s. Helper
 * declarations move *inside* `block`, so a helper that used to be a top-level `fun` is
 * now a local one. That costs nothing except that it is re-created per voxel, which for
 * the handful of operations a puzzle needs is not measurable.
 */
interface Replikube {
    /**
     * Colour at voxel `(x, y, z)`, or `null` to leave it empty. See [Palette].
     *
     * Coordinates are centred on the origin: for a level of size `(sx, sy, sz)`,
     * `x` ranges over `[-sx/2, (sx-1)/2]`, and so do `z` and `y`. Negative `y` is the
     * bottom layer.
     *
     * @throws IllegalStateException never — invalid ids are reported by the game.
     */
    fun block(x: Int, y: Int, z: Int): Int?
}

/**
 * Dimensions of the grid a solution is filling, injected as a top-level object by the
 * generated harness. Available inside [block] and any helper it calls:
 *
 * ```kotlin
 * return if (Level.inBounds(x, y, z)) RED else null
 * ```
 *
 * Declared here for documentation only — the real `Level` object is emitted per level
 * with literal constants, so no runtime state is shared between runs.
 */
object Level {
    const val sizeX = 3
    const val sizeY = 3
    const val sizeZ = 3

    /** Whether `(x, y, z)` is inside this grid. */
    fun inBounds(x: Int, y: Int, z: Int): Boolean =
        x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    const val minX = -sizeX / 2
    const val maxX = (sizeX - 1) / 2
    const val minY = -sizeY / 2
    const val maxY = (sizeY - 1) / 2
    const val minZ = -sizeZ / 2
    const val maxZ = (sizeZ - 1) / 2
}