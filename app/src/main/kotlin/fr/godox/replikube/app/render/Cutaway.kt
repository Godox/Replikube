package fr.godox.replikube.app.render

import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.GridSize

/**
 * How much of the grid is cut away, counted in whole layers from the **high** end of each
 * axis.
 *
 * ### Why a cutaway exists
 *
 * A hollow shape is unreadable from outside. `hollow-frame` is a six-cube-thick shell with
 * a four-cube cavity: every voxel a player can see is on the surface, and the surface looks
 * identical whether the middle is filled or empty. The puzzle's whole content is in the
 * part the camera cannot reach, which makes it unplayable rather than merely hard.
 *
 * Cutting is also how a player debugs. After a wrong answer, "which of these two hundred
 * cubes did I get wrong" is answerable by looking at the outside for the surface cubes and
 * by cutting for the rest.
 *
 * ### Why it counts layers rather than showing a slice
 *
 * A slice (keep only `x == 0`) answers a narrower question than the one being asked. The
 * player wants "show me everything *behind* this plane", which is a half-space, and a
 * half-space is described by where its plane is. Counting from the high end means the
 * slider's leftmost position is always "nothing hidden" and its rightmost is always
 * "nothing left", regardless of the grid's size or where the origin sits — so the control
 * means the same thing on a 3³ level and a 9³ one.
 *
 * ### Why it is cut from the high end and not the low end
 *
 * The grid is centred on the origin, so `+` and `-` are symmetric and the choice is
 * arbitrary in world terms. It is not arbitrary in *screen* terms: the renderer's culling
 * and depth sort treat the camera's positive side as the near side, so cutting from the
 * high end removes the near half for the default camera and therefore reveals the
 * interior rather than burying it.
 */
data class Cutaway(
    /** Layers hidden from the high end of x. `0` hides nothing. */
    val x: Int = 0,
    /** Layers hidden from the high end of y. `0` hides nothing. */
    val y: Int = 0,
    /** Layers hidden from the high end of z. `0` hides nothing. */
    val z: Int = 0,
) {
    /** Whether this cutaway hides anything at all. */
    val isOff: Boolean get() = x == 0 && y == 0 && z == 0

    /** How many voxels of [size] this cutaway keeps. */
    fun keptCount(size: GridSize): Int = clampedTo(size).let {
        (size.x - it.x) * (size.y - it.y) * (size.z - it.z)
    }

    /** How many voxels of [size] this cutaway hides. */
    fun hiddenCount(size: GridSize): Int = size.voxelCount - keptCount(size)

    /** The fraction of the grid still drawn, in `[0, 1]`. */
    fun keptFraction(size: GridSize): Float = keptCount(size).toFloat() / size.voxelCount

    /** This cutaway with each axis clamped into `0..size` for that axis. */
    fun clampedTo(size: GridSize): Cutaway = Cutaway(
        x = x.coerceIn(0, size.x),
        y = y.coerceIn(0, size.y),
        z = z.coerceIn(0, size.z),
    )

    /** Whether [c] falls in the removed half-space of any axis. */
    fun hides(c: Coord, size: GridSize): Boolean {
        val b = size.bounds
        return hidesAxis(c.x, x, b.maxX) ||
            hidesAxis(c.y, y, b.maxY) ||
            hidesAxis(c.z, z, b.maxZ)
    }

    private fun hidesAxis(value: Int, layers: Int, max: Int): Boolean =
        layers > 0 && value > max - layers

    companion object {
        /**
         * The cutaway that hides nothing.
         *
         * Named so that a call site reading `cutaway = Cutaway.Off` says what it means.
         */
        val Off = Cutaway()

        /**
         * The deepest cut on [size] that still leaves one voxel — a slider's top position.
         *
         * On the companion rather than an instance because it is a fact about the grid, not
         * about any particular cutaway: it is the same answer whatever the current cut is,
         * and a slider asking "where is my end stop" is asking about the grid.
         */
        fun maxFor(size: GridSize): Cutaway = Cutaway(size.x - 1, size.y - 1, size.z - 1)
    }
}