package fr.godox.replikube.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridSizeTest {

    @Test
    fun `odd dimensions are centred on the origin`() {
        val b = GridSize(3, 3, 3).bounds
        assertEquals(-1, b.minX)
        assertEquals(1, b.maxX)
    }

    @Test
    fun `even dimensions keep the larger half on the positive side`() {
        val b = GridSize(4, 2, 5).bounds
        assertEquals(-2, b.minX)
        assertEquals(1, b.maxX)
        assertEquals(-1, b.minY)
        assertEquals(0, b.maxY)
        assertEquals(-2, b.minZ)
        assertEquals(2, b.maxZ)
    }

    /**
     * The span along each axis must be exactly the dimension, for every parity.
     *
     * This is the property that `minOf(x / 2)` silently violated: it returned `x / 2`
     * rather than a lower bound, collapsing a 3-wide axis to the single coordinate `1`.
     */
    @Test
    fun `span along each axis equals the dimension for every parity`() {
        for (n in 1..16) {
            val size = GridSize(n, n + 1, n + 2)
            val b = size.bounds
            assertEquals<Int>(n, (b.maxX - b.minX) + 1, "x span for $size")
            assertEquals<Int>(n + 1, (b.maxY - b.minY) + 1, "y span for $size")
            assertEquals<Int>(n + 2, (b.maxZ - b.minZ) + 1, "z span for $size")
            assertEquals<Int>(size.voxelCount, size.coords().count(), "coords for $size")
        }
    }

    @Test
    fun `rejects non-positive dimensions`() {
        val bad = listOf(
            { GridSize(0, 1, 1) },
            { GridSize(1, -1, 1) },
            { GridSize(1, 1, 0) },
        )
        for (build in bad) {
            assertTrue(
                runCatching(build).exceptionOrNull() is IllegalArgumentException,
                "expected a non-positive dimension to be rejected",
            )
        }
    }
}