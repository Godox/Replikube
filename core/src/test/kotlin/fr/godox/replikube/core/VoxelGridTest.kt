package fr.godox.replikube.core

import fr.godox.replikube.dsl.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoxelGridTest {

    private val size3 = GridSize(3, 3, 3)

    /**
     * The fill order in [VoxelGrid.build] and the addressing in [indexOf] must agree.
     *
     * They were written independently and disagreed about which axis varies fastest, which
     * silently transposed every rendered grid.
     */
    @Test
    fun `build writes each cell where indexOf reads it`() {
        for (size in listOf(GridSize(3, 3, 3), GridSize(4, 2, 5), GridSize(1, 5, 2))) {
            val grid = VoxelGrid.build(size) { x, y, z -> x * 100 + y * 10 + z }
            for (c in size.coords().toList()) {
                assertEquals(c.x * 100 + c.y * 10 + c.z, grid[c], "$c in $size")
            }
        }
    }

    @Test
    fun `indexOf is a bijection over the grid`() {
        val size = GridSize(4, 2, 5)
        val seen = size.coords().map { size.indexOf(it) }.toList()
        assertEquals((0 until size.voxelCount).toList(), seen.sorted())
        assertEquals<Int>(size.voxelCount, seen.distinct().size, "indexOf must not collide")
    }

    @Test
    fun `coords enumerates every voxel exactly once`() {
        for (size in listOf(GridSize(3, 3, 3), GridSize(4, 2, 5), GridSize(1, 1, 1))) {
            val coords = size.coords().toList()
            assertEquals<Int>(size.voxelCount, coords.size, "$size")
            assertEquals<Int>(coords.distinct().size, coords.size, "$size has duplicates")
        }
    }

    @Test
    fun `out-of-range coordinates read as EMPTY rather than throwing`() {
        val grid = VoxelGrid.filled(size3, Palette.RED)
        assertEquals(Palette.EMPTY, grid[9, 9, 9])
        assertEquals(Palette.EMPTY, grid[99, 0, 0])
    }

    @Test
    fun `solidCount and solidCoords agree`() {
        val grid = VoxelGrid.build(size3) { x, y, z -> if (x + y + z > 0) Palette.GREEN else Palette.EMPTY }
        assertEquals(grid.solidCoords().size, grid.solidCount)
        assertTrue(grid.solidCoords().all { grid.isSolid(it) })
    }

    @Test
    fun `hasValidColors flags an out-of-range id`() {
        assertTrue(VoxelGrid.filled(size3, Palette.BROWN).hasValidColors())
        // x is the fastest-varying axis, so index 4 is the third cell of the y=-1, z=-1 row.
        val bad = VoxelGrid.of(size3, IntArray(size3.voxelCount).also { it[4] = 99 })
        assertEquals(false, bad.hasValidColors())
        assertEquals(Coord(0, 0, -1), bad.firstInvalidCell())
    }

    @Test
    fun `of rejects an array of the wrong length`() {
        assertTrue(
            runCatching { VoxelGrid.of(size3, IntArray(26)) }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}