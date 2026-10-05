@file:JvmName("Geometry")

package fr.godox.replikube.dsl

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Geometry helpers.
 *
 * Thin, obvious wrappers over the standard library — the point is that a level's
 * *idea* is legible in the solution, not that the maths is powerful. Distances are
 * integer and squared where possible, so no floats creep into a puzzle.
 */

/** Chebyshev (chessboard) distance: the largest per-axis gap. The classic "cube shell" metric. */
fun chebyshev(x: Int, y: Int, z: Int): Int = max(abs(x), max(abs(y), abs(z)))

/** Manhattan (taxicab) distance: gaps summed per axis. The classic "diamond" metric. */
fun manhattan(x: Int, y: Int, z: Int): Int = abs(x) + abs(y) + abs(z)

/** Euclidean distance to the origin, squared. Compare against `r * r` to stay in integers. */
fun dist(x: Int, y: Int, z: Int): Int = x * x + y * y + z * z

/** Whether `(x, y, z)` is within [r] of the origin, using Euclidean distance. */
fun inSphere(x: Int, y: Int, z: Int, r: Int): Boolean = dist(x, y, z) <= r * r

/** Whether `(x, y, z)` is within [r] of the origin, using Chebyshev distance. */
fun inCube(x: Int, y: Int, z: Int, r: Int): Boolean = chebyshev(x, y, z) <= r

/** Whether `(x, y, z)` is within [r] of the origin, using Manhattan distance. */
fun inDiamond(x: Int, y: Int, z: Int, r: Int): Boolean = manhattan(x, y, z) <= r

/**
 * Parity of [n], normalized so that a value and its negation share a parity.
 *
 * `abs(n) % 2` — so `-1` and `1` are both odd. Level coordinate systems are centred on
 * the origin, and `n % 2` returns `-1` for negative odd numbers in Kotlin, which quietly
 * breaks naive checkerboards. Use this instead of `% 2`.
 */
fun parity(n: Int): Int = abs(n) % 2

/** Whether `n` is even. `parity(n) == 0`. */
fun isEven(n: Int): Boolean = parity(n) == 0

/** Whether `n` is odd. `parity(n) == 1`. */
fun isOdd(n: Int): Boolean = parity(n) == 1

/** Clamp [v] into `lo..hi`. */
fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
