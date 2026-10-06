package fr.godox.replikube.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The whole vocabulary a player is given.
 *
 * `:dsl` has no dependencies and no logic worth the name, so there is nothing here to
 * prove by execution — except for one thing. The palette indices are **a file format**:
 * they appear in level metadata, in solutions players share, and in saved progress. A
 * number moving means every one of those silently changes meaning, and nothing about the
 * failure looks like a bug. So the indices are pinned, and so is the invariant that the
 * hand-written name table cannot drift away from the constants.
 */
class PaletteTest {

    @Test
    fun `colour ids are stable forever`() {
        // The format itself, written out. If this test ever needs changing, the save
        // files and shared solutions that carry these numbers are broken too, so it needs
        // a migration and not an edit.
        assertEquals(0, EMPTY)
        assertEquals(1, WHITE)
        assertEquals(2, LIGHT_GRAY)
        assertEquals(3, GRAY)
        assertEquals(4, DARK_GRAY)
        assertEquals(5, BLACK)
        assertEquals(6, RED)
        assertEquals(7, ORANGE)
        assertEquals(8, YELLOW)
        assertEquals(9, LIME)
        assertEquals(10, GREEN)
        assertEquals(11, CYAN)
        assertEquals(12, BLUE)
        assertEquals(13, INDIGO)
        assertEquals(14, PURPLE)
        assertEquals(15, MAGENTA)
        assertEquals(16, BROWN)
        assertEquals(16, Palette.MAX, "MAX is the highest colour id")
    }

    @Test
    fun `the top-level constants are the same values as the object's`() {
        // Player code writes the bare name, so a divergence here would compile into a
        // solution that means something other than what the object says — and only one of
        // the two would be used to render it.
        assertEquals(Palette.EMPTY, EMPTY)
        assertEquals(Palette.WHITE, WHITE)
        assertEquals(Palette.LIGHT_GRAY, LIGHT_GRAY)
        assertEquals(Palette.GRAY, GRAY)
        assertEquals(Palette.DARK_GRAY, DARK_GRAY)
        assertEquals(Palette.BLACK, BLACK)
        assertEquals(Palette.RED, RED)
        assertEquals(Palette.ORANGE, ORANGE)
        assertEquals(Palette.YELLOW, YELLOW)
        assertEquals(Palette.LIME, LIME)
        assertEquals(Palette.GREEN, GREEN)
        assertEquals(Palette.CYAN, CYAN)
        assertEquals(Palette.BLUE, BLUE)
        assertEquals(Palette.INDIGO, INDIGO)
        assertEquals(Palette.PURPLE, PURPLE)
        assertEquals(Palette.MAGENTA, MAGENTA)
        assertEquals(Palette.BROWN, BROWN)
    }

    @Test
    fun `the name table is the constants, in index order`() {
        // `names` is a second hand-written list of the same 17 colours. This is what
        // catches it going stale.
        assertEquals(
            listOf(
                "EMPTY", "WHITE", "LIGHT_GRAY", "GRAY", "DARK_GRAY", "BLACK", "RED",
                "ORANGE", "YELLOW", "LIME", "GREEN", "CYAN", "BLUE", "INDIGO",
                "PURPLE", "MAGENTA", "BROWN",
            ),
            (0..Palette.MAX).map(Palette::nameOf),
        )
    }

    @Test
    fun `the name table covers every id, and no further`() {
        for (id in 0..Palette.MAX) {
            assertFalse(
                Palette.nameOf(id).startsWith("UNKNOWN"),
                "id $id has no name",
            )
        }
        assertTrue(Palette.nameOf(Palette.MAX + 1).startsWith("UNKNOWN"), "past the end should be unknown")
        assertTrue(Palette.nameOf(-1).startsWith("UNKNOWN"), "below the start should be unknown")
    }

    @Test
    fun `empty is zero and is the only way to leave a voxel out`() {
        assertEquals(0, Palette.EMPTY)
        assertTrue(Palette.isValid(Palette.EMPTY))
    }

    @Test
    fun `validity is exactly 0 to MAX`() {
        for (id in -5..Palette.MAX + 5) {
            assertEquals(id in 0..Palette.MAX, Palette.isValid(id), "id $id")
        }
    }
}
