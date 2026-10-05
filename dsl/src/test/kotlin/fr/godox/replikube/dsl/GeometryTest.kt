package fr.godox.replikube.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The handful of geometry helpers, which exist to make a level's *idea* legible.
 *
 * There is nothing clever here on purpose — the point is that a solution reads as "a
 * sphere" rather than as arithmetic. So these tests are about the two things that are
 * easy to get wrong and invisible when wrong: which metric is which, and the sign
 * behaviour of Kotlin's `%`.
 */
class GeometryTest {

    @Test
    fun `distances agree with their definitions`() {
        assertEquals(3, chebyshev(1, -2, 3), "largest per-axis gap")
        assertEquals(6, manhattan(1, -2, 3), "gaps summed")
        assertEquals(14, dist(1, -2, 3), "sum of squares, not a square root")
    }

    @Test
    fun `the origin is inside every shape and a far corner is outside every shape`() {
        for (radius in 1..4) {
            assertTrue(inSphere(0, 0, 0, radius))
            assertTrue(inCube(0, 0, 0, radius))
            assertTrue(inDiamond(0, 0, 0, radius))
            assertTrue(inCube(radius, radius, radius, radius), "a cube includes its corners")
        }
        assertFalse(inSphere(10, 10, 10, 1))
        assertFalse(inCube(10, 10, 10, 1))
        assertFalse(inDiamond(10, 10, 10, 1))
    }

    @Test
    fun `the three metrics disagree, which is the point of having all three`() {
        // (1,1,0): inside a radius-1 cube, outside a radius-1 sphere, inside a radius-2
        // diamond. A level can therefore tell the shapes apart.
        assertTrue(inCube(1, 1, 0, 1))
        assertFalse(inSphere(1, 1, 0, 1))
        assertTrue(inDiamond(1, 1, 0, 2))
    }

    @Test
    fun `a larger radius only ever includes more voxels`() {
        // Monotonicity, which is what makes "make it bigger" a safe edit when tuning a
        // level. A level that shrinks as you widen it would be very hard to spot by eye.
        for (r in 1..4) {
            assertTrue(inSphere(0, 0, 0, r + 1))
            assertTrue(inCube(0, 0, 0, r + 1))
            assertTrue(inDiamond(0, 0, 0, r + 1))
        }
        assertTrue(inSphere(3, 0, 0, 5) && !inSphere(3, 0, 0, 2), "just inside, then just outside")
    }

    @Test
    fun `parity is the same for a number and its negation`() {
        // Kotlin's `%` returns -1 for a negative odd number, which silently breaks a
        // checkerboard written as `x % 2 == 0`. This is the whole reason parity() exists.
        assertEquals(1, parity(-1))
        assertEquals(1, parity(1))
        assertEquals(0, parity(-2))
        for (n in -20..20) {
            assertEquals(parity(n), parity(-n), "parity should ignore the sign of $n")
        }
    }

    @Test
    fun `clamp pulls both ends in`() {
        assertEquals(5, clamp(9, 0, 5))
        assertEquals(0, clamp(-9, 0, 5))
        assertEquals(3, clamp(3, 0, 5))
        assertEquals(3, clamp(3, 3, 3), "a degenerate range should return that value")
    }
}