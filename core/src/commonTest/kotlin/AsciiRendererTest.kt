package fr.godox.replikube.core.render

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.Verifier
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AsciiRendererTest {

    private val size3 = GridSize(3, 3, 3)

    /**
     * Parses rendered text back into `y` slice headers plus one entry per `z` row.
     *
     * The renderer promises slices are separated only by runs of two or more spaces and that
     * no label contains such a run, which is what makes this unambiguous.
     */
    private fun parse(text: String): Parsed {
        val label = Regex("""z=\s*(-?\d+)\s""")
        val rows = text.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        val columns = { line: String -> Regex(" {2,}").split(line).filter { it.isNotEmpty() } }
        val headers = columns(rows.first())
        val body = rows.drop(1).map { columns(it) }
        return Parsed(
            headers = headers,
            zLabels = body.map { label.find(it[0])!!.groupValues[1] },
            slices = List(headers.size) { y -> body.map { row -> row[y].replaceFirst(label, "") } },
        )
    }

    private class Parsed(
        val headers: List<String>,
        val zLabels: List<String>,
        /** [slices][y][row] is the glyph string for that `y` slice. */
        val slices: List<List<String>>,
    )

    @Test
    fun `every colour id maps to a distinct glyph`() {
        val glyphs = (Palette.EMPTY..Palette.MAX).map { AsciiRenderer.glyph(it) }
        assertEquals<Int>(Palette.MAX + 1, glyphs.distinct().size, "duplicate glyph in $glyphs")
    }

    @Test
    fun `an out-of-range id renders as unknown`() {
        assertEquals('?', AsciiRenderer.glyph(99))
    }

    @Test
    fun `slices run left to right in ascending y, and z descends`() {
        val parsed = parse(AsciiRenderer.render(VoxelGrid.empty(size3)))
        assertEquals(listOf("y=-1", "y= 0", "y= 1"), parsed.headers, "labels pad the sign, not the digits")
        assertEquals(listOf("1", "0", "-1"), parsed.zLabels, "the front face prints first")
    }

    @Test
    fun `one filled voxel lands in the right cell of the right slice`() {
        val grid = VoxelGrid.build(size3) { x, y, z ->
            if (x == 0 && y == 1 && z == -1) Palette.RED else Palette.EMPTY
        }
        val parsed = parse(AsciiRenderer.render(grid))
        val rowOfZMinus1 = parsed.zLabels.indexOf("-1")
        val y1 = parsed.slices[2]
        assertEquals(".R.", y1[rowOfZMinus1], "x=0 is the middle column of a 3-wide slice")
        assertEquals("...", y1[rowOfZMinus1].replace("R", "."), "no other voxel in that slice")
        assertTrue(parsed.slices[0].all { it == "..." }, "the y=-1 slice is untouched")
    }

    @Test
    fun `an empty grid is all dots`() {
        val parsed = parse(AsciiRenderer.render(VoxelGrid.empty(size3)))
        assertTrue(parsed.slices.flatten().all { row -> row.all { it == '.' } })
    }

    @Test
    fun `a full grid is a solid block in every slice`() {
        val parsed = parse(AsciiRenderer.render(VoxelGrid.filled(size3, Palette.GREEN)))
        assertTrue(parsed.slices.flatten().all { row -> row == "GGG" }, "expected 3 slices of GGG")
    }

    @Test
    fun `diff marks wrong colour and extra voxels`() {
        val target = VoxelGrid.build(size3) { x, y, z -> if (x == 0 && y == 1 && z == 0) Palette.RED else Palette.EMPTY }
        val result = VoxelGrid.build(size3) { x, y, z ->
            when {
                x == 0 && y == 1 && z == 0 -> Palette.BLUE // same voxel, wrong colour
                x == -1 && y == -1 && z == -1 -> Palette.GREEN // should not be there
                else -> Palette.EMPTY
            }
        }
        val diff = assertIs<Verifier.Result.Graded>(Verifier.diff(target, result)).diff
        assertEquals(1, diff.wrongColor.size)
        assertEquals(1, diff.extra.size)
        assertEquals(0, diff.missing.size)

        val text = AsciiRenderer.renderDiff(target, diff)
        assertTrue(text.contains('?'), "colour mismatch should be marked:\n$text")
        assertTrue(text.contains('X'), "extra voxel should be marked:\n$text")
        assertTrue(!text.contains('M'), "nothing is missing:\n$text")
    }

    @Test
    fun `diff marks missing voxels`() {
        // Only the y = 0 layer, so one slice of nine.
        val target = VoxelGrid.build(size3) { _, y, _ -> if (y == 0) Palette.RED else Palette.EMPTY }
        val result = VoxelGrid.empty(size3)
        val diff = assertIs<Verifier.Result.Graded>(Verifier.diff(target, result)).diff
        assertEquals(9, diff.missing.size)

        val parsed = parse(AsciiRenderer.renderDiff(target, diff))
        assertTrue(parsed.slices[1].all { row -> row == "MMM" }, "the middle slice is all missing")
        assertTrue(parsed.slices[0].all { row -> row == "..." }, "no missing voxels on the bottom")
        assertTrue(parsed.slices[2].all { row -> row == "..." }, "no missing voxels on top")
    }

    @Test
    fun `legend names every colour used`() {
        val grid = VoxelGrid.build(size3) { x, _, _ -> if (x == 0) Palette.GREEN else Palette.EMPTY }
        val legend = paletteLegend(grid)
        assertTrue(legend.contains("green (10)"), legend)
        assertTrue(legend.contains("empty (0)"), legend)
    }
}