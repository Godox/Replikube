package fr.godox.replikube.core.render

import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.Diff
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette
import kotlin.math.abs

/**
 * Renders a [VoxelGrid] as text, one row per `z` and one column per `x`, one `y` slice at
 * a time.
 *
 * This exists so levels can be authored and tuned without the UI. Levels *are* the content
 * of the game, and iterating on a shape in a terminal is far faster than through a viewport.
 * It is also the most useful failure output there is: when a level looks wrong, three
 * slices of it as text usually make the mistake obvious.
 *
 * Slices sit side by side, bottom layer first, because that is the order a player builds a
 * shape up in:
 *
 * ```
 * y=-1     y=0      y=1
 * z=-1     z=-1     z=-1
 * .#.      ...      ...
 * z=0      z=0      z=0
 * ...      .#.      ...
 * ```
 *
 * Within a slice, `z` descends, so the first row printed is the front face of the shape and
 * matches the isometric view's near corner.
 *
 * Slices are separated by runs of two or more spaces, and no label contains a run that long,
 * so a caller (or a test) can split the output back into columns.
 */
object AsciiRenderer {

    /**
     * One character per palette id.
     *
     * Lowercase for the grey ramp so it reads as one gradient, and `.` for [Palette.EMPTY]
     * because absence should look like background rather than a colour.
     */
    private const val GREYS = ".wlgd#" // EMPTY WHITE LIGHT_GRAY GRAY DARK_GRAY BLACK
    private const val WARM = "ROYLGC" // RED ORANGE YELLOW LIME GREEN CYAN
    private const val COOL = "BIPM" // BLUE INDIGO PURPLE MAGENTA

    /** Marks a filled voxel of the wrong colour. Shares a glyph with an out-of-range id. */
    private const val WRONG = '?'
    private const val MISSING = 'M'
    private const val EXTRA = 'X'

    /** Glyph for a palette colour id; `?` for anything out of range. */
    fun glyph(color: Int): Char = when (color) {
        in Palette.EMPTY..Palette.BLACK -> GREYS[color]
        in Palette.RED..Palette.CYAN -> WARM[color - Palette.RED]
        in Palette.BLUE..Palette.MAGENTA -> COOL[color - Palette.BLUE]
        Palette.BROWN -> 'b'
        else -> WRONG
    }

    /** Renders [grid] as labelled `y` slices side by side. */
    fun render(grid: VoxelGrid): String = renderSlices(grid.size) { x, y, z -> glyph(grid[x, y, z]) }

    /**
     * Renders what the player got wrong: target glyphs, with offending voxels marked.
     *
     * Correct voxels keep their real colour so the result still reads as a shape. `M` is a
     * voxel the target wants and the player left empty, `X` one they should not have added,
     * and `?` a colour mismatch.
     */
    fun renderDiff(target: VoxelGrid, diff: Diff): String {
        val missing = diff.missing.toSet()
        val extra = diff.extra.toSet()
        val wrong = diff.wrongColor.mapTo(mutableSetOf()) { it.coord }
        return renderSlices(target.size) { x, y, z ->
            val c = Coord(x, y, z)
            when {
                c in missing -> MISSING
                c in extra -> EXTRA
                c in wrong -> WRONG
                else -> glyph(target[x, y, z])
            }
        }
    }

    /**
     * Builds the side-by-side slice view.
     *
     * Each slice is rendered independently into its own lines, then the line lists are
     * zipped: every slice has the same number of lines, so padding each to the widest
     * slice keeps the `z` labels lined up.
     */
    private fun renderSlices(size: fr.godox.replikube.core.GridSize, cell: (Int, Int, Int) -> Char): String {
        val b = size.bounds
        val slices = (b.minY..b.maxY).map { y ->
            buildList {
                add(axisLabel("y", y))
                for (z in b.maxZ downTo b.minZ) {
                    add(axisLabel("z", z) + " " + (b.minX..b.maxX).map { cell(it, y, z) }.joinToString(""))
                }
            }
        }
        val widths = slices.maxOf { slice -> slice.maxOf { it.length } }
        return buildString {
            for (row in slices.first().indices) {
                for (slice in slices) {
                    append(slice[row].padEnd(widths)).append(GAP)
                }
                appendLine()
            }
        }.trimEnd()
    }

    private const val GAP = "  "

    /**
     * A fixed-width axis label, e.g. `y=-1` and `y= 1`.
     *
     * The sign is written explicitly rather than letting the digits float: padding the
     * number itself would count the minus sign and give `-1` and `1` different widths, which
     * is exactly the misalignment that makes a rendered shape hard to read.
     */
    private fun axisLabel(axis: String, value: Int): String =
        "$axis=${if (value < 0) "-" else " "}${abs(value)}"
}

/**
 * One entry per colour id used anywhere in [grids], mapping glyph to id and name.
 *
 * Without this, text-rendered shapes are close to unreadable: `g` and `G` are hard to tell
 * apart, and the grey ramp is exactly where levels hide their mistakes.
 */
fun paletteLegend(vararg grids: VoxelGrid): String {
    // `sortedSetOf` is `java.util.TreeSet` and is not in common code. The common equivalent
    // that behaves identically here is `distinct().sorted()`: palette ids are `Int`, so the
    // natural ordering *is* numeric ordering, which is what the old TreeSet produced.
    //
    // Distinct matters as much as sorted -- a legend that repeated a colour once per voxel
    // would be unreadable, and a 9x9x9 level has 729 voxels.
    val ids: List<Int> = grids.asSequence()
        .flatMap { grid -> grid.allCoords().map { grid[it] } }
        .distinct()
        .sorted()
        .toList()
    if (ids.isEmpty()) return "(empty)"
    return ids.joinToString("  ") { id -> "${AsciiRenderer.glyph(id)} = ${Palette.nameOf(id).lowercase()} ($id)" }
}