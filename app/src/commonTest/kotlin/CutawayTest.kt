package fr.godox.replikube.app

import fr.godox.replikube.app.render.Cutaway
import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.coords
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cutaway's arithmetic.
 *
 * A cutaway is a set of half-spaces over a grid that is centred on the origin, so the two
 * things worth pinning are that it removes the *near* half at the default camera — the
 * removal has to reveal the interior, not bury it — and that it never claims to remove
 * voxels that were not there. Everything else is arithmetic on those two properties.
 */
class CutawayTest {

    /** A grid with odd dimensions, so the bounds straddle the origin symmetrically. */
    private val odd = GridSize(5, 5, 5)

    /** Even dimensions, where the origin sits inside a layer rather than on one. */
    private val even = GridSize(4, 6, 4)

    @Test
    fun `an off cutaway hides nothing`() {
        assertTrue(Cutaway.Off.isOff)
        assertEquals(0, Cutaway.Off.hiddenCount(odd))
        for (c in odd.coords()) {
            assertFalse(Cutaway.Off.hides(c, odd), "$c should be visible")
        }
    }

    @Test
    fun `cutting one layer from x hides exactly the highest x`() {
        // GridSize(5).bounds is minX = -2, maxX = 2, so the highest layer is x == 2.
        val cut = Cutaway(x = 1)
        for (c in odd.coords()) {
            assertEquals(c.x == 2, cut.hides(c, odd), "x=$c")
        }
        assertEquals(odd.y * odd.z, cut.hiddenCount(odd))
    }

    @Test
    fun `cutting from y hides the top, not the bottom`() {
        // minY = -2 is the bottom layer. The cut must not take it.
        val cut = Cutaway(y = 1)
        assertFalse(cut.hides(Coord(0, -2, 0), odd), "the bottom layer must survive")
        assertTrue(cut.hides(Coord(0, 2, 0), odd))
    }

    @Test
    fun `a full cut hides every voxel`() {
        for (size in listOf(odd, even, GridSize(3, 3, 3), GridSize(1, 1, 1))) {
            val cut = Cutaway(x = size.x, y = size.y, z = size.z)
            assertEquals(
                size.voxelCount,
                cut.hiddenCount(size),
                "$size: a cut of every layer must hide everything",
            )
        }
    }

    @Test
    fun `one layer short of a full cut leaves exactly one voxel`() {
        // This is why the slider's maximum is size - 1: it is the deepest cut that still
        // shows something. Dragging past it to an empty screen helps nobody.
        for (size in listOf(odd, even)) {
            val cut = Cutaway(size.x - 1, size.y - 1, size.z - 1)
            assertEquals(1, cut.keptCount(size), "$size should keep exactly one voxel")
        }
    }

    @Test
    fun `two cuts combine, and hide more than either alone`() {
        val cutX = Cutaway(x = 1)
        val cutY = Cutaway(y = 1)
        val both = Cutaway(x = 1, y = 1)
        assertTrue(both.hiddenCount(odd) > cutX.hiddenCount(odd))
        assertTrue(both.hiddenCount(odd) > cutY.hiddenCount(odd))

        // The hidden set is a *union* of two half-spaces, so the overlap is counted once:
        // cutting one x layer and one y layer hides the slice plus the layer, not their
        // sum. Asserting the complement rather than a hand-added number is what pins that.
        assertEquals(
            odd.voxelCount - (odd.x - 1) * (odd.y - 1) * odd.z,
            both.hiddenCount(odd),
        )
        // Which is *less* than either cut added together — the layer where the two cuts
        // overlap is one voxel, not two.
        assertTrue(both.hiddenCount(odd) < cutX.hiddenCount(odd) + cutY.hiddenCount(odd))
    }

    @Test
    fun `kept count and hidden count agree with the grid total`() {
        for (cut in listOf(Cutaway.Off, Cutaway(x = 2), Cutaway(y = 3), Cutaway(x = 1, z = 4))) {
            for (size in listOf(odd, even)) {
                assertEquals(
                    size.voxelCount,
                    cut.keptCount(size) + cut.hiddenCount(size),
                    "cut=$cut size=$size must partition the grid",
                )
            }
        }
    }

    @Test
    fun `kept fraction is the kept count over the whole grid`() {
        assertEquals(1f, Cutaway.Off.keptFraction(odd))
        assertEquals(1f / odd.voxelCount, Cutaway(odd.x - 1, odd.y - 1, odd.z - 1).keptFraction(odd))
    }

    @Test
    fun `an out-of-range cut is clamped to the grid rather than miscounted`() {
        // The slider is bounded by the layout, but a restored state or a level change can
        // leave a number that means nothing on the new grid. It must clamp, not wrap or
        // subtract a negative count: x = 99 is a whole-grid cut, and y = -5 is no cut at
        // all. Both readings are far more useful than a negative count in the readout.
        assertEquals(Cutaway(x = 5, y = 0), Cutaway(x = 99, y = -5).clampedTo(odd))
        assertEquals(odd.voxelCount, Cutaway(x = 99).clampedTo(odd).hiddenCount(odd))
        assertEquals(0, Cutaway(y = -5).clampedTo(odd).hiddenCount(odd))
    }

    @Test
    fun `hides is monotone in the layer count`() {
        // Sliding right must never bring a voxel back. If it did, the control would be
        // unusable: the player could not predict what is visible.
        var previous = 0
        for (layers in 0..odd.x) {
            val hidden = Cutaway(x = layers).hiddenCount(odd)
            assertTrue(hidden >= previous, "cutting $layers layers hid less than ${layers - 1}")
            previous = hidden
        }
    }

    @Test
    fun `maxFor is the deepest cut that still leaves one voxel`() {
        for (size in listOf(odd, even, GridSize(3, 1, 2))) {
            assertEquals(1, Cutaway.maxFor(size).keptCount(size), "$size should keep one voxel")
        }
    }
}