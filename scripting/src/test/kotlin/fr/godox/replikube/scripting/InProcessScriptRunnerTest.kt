package fr.godox.replikube.scripting

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end tests for [InProcessScriptRunner].
 *
 * These compile and run real Kotlin, so they are slow (seconds each) and are the only
 * place the compiler integration is verified. Everything cheap is tested directly against
 * [SolutionCompiler] instead.
 *
 * Every snippet here is a **body**, not a declaration — see [SolutionCompiler].
 */
class InProcessScriptRunnerTest {

    private val runner = InProcessScriptRunner()
    private val size3 = GridSize(3, 3, 3)

    private fun run(source: String, size: GridSize = size3, timeout: Long = 60_000): RunResult =
        runBlocking { runner.run(source, size, timeout) }

    private fun grid(source: String, size: GridSize = size3): VoxelGrid {
        val result = run(source, size)
        return assertIs<RunResult.Success>(
            result,
            "expected success but got ${(result as? RunResult.Failure)?.diagnostic}",
        ).grid
    }

    private fun failure(source: String, size: GridSize = size3, timeout: Long = 60_000): Diagnostic =
        assertIs<RunResult.Failure>(run(source, size, timeout)).diagnostic

    @Test
    fun `runs the canonical three-layer example`() {
        val grid = grid(
            """
            |return when {
            |    y == 1 -> RED
            |    y == 0 -> YELLOW
            |    else -> GREEN
            |}
            """.trimMargin(),
        )

        assertEquals(Palette.RED, grid[0, 1, 0])
        assertEquals(Palette.YELLOW, grid[0, 0, 0])
        assertEquals(Palette.GREEN, grid[0, -1, 0])
        assertEquals(27, grid.solidCount)
    }

    @Test
    fun `null means no cube`() {
        val grid = grid(
            """
            |return if (x == 0 && y == 0 && z == 0) MAGENTA else null
            """.trimMargin(),
        )

        assertEquals(Palette.MAGENTA, grid[0, 0, 0])
        assertEquals(1, grid.solidCount, "null should leave every other voxel empty")
        assertEquals(Palette.EMPTY, grid[1, 0, 0], "null becomes EMPTY in the grid")
    }

    @Test
    fun `a when without an else is rejected, and the compiler says why`() {
        // Kotlin 2.4 requires an expression `when` to be exhaustive, with or without a
        // subject — so `else -> null` is how a solution says "nothing here". Players will
        // write the non-exhaustive form, and the message has to survive verbatim to help.
        val diagnostic = failure(
            """
            |return when (x) {
            |    0 -> CYAN
            |}
            """.trimMargin(),
        )

        assertEquals(Diagnostic.Kind.COMPILE_ERROR, diagnostic.kind)
        assertContains(diagnostic.message, "exhaustive")
        assertContains(diagnostic.message, "else")
    }

    @Test
    fun `EMPTY still works, because it is the same value`() {
        val grid = grid(
            """
            |return if (y == 0) RED else EMPTY
            """.trimMargin(),
        )

        assertEquals(9, grid.solidCount, "one 3x3 layer")
    }

    @Test
    fun `the body may declare vals and helper functions`() {
        // Helpers used to be top-level; they are local now, because the player's code *is*
        // the body. Recursion and local `val`s are the reason the wrapper uses a block
        // rather than an expression body.
        val grid = grid(
            """
            |val radius = 2
            |
            |fun shell(d: Int) = d in (radius - 1) until radius
            |
            |fun survives(depth: Int, n: Int): Boolean =
            |    depth == 0 || n % 2 == 0 || survives(depth - 1, n / 2)
            |
            |val chosen = if (survives(3, x)) shell(chebyshev(x, y, z)) else false
            |
            |return if (chosen) BLUE else null
            """.trimMargin(),
        )

        assertEquals(Palette.BLUE, grid[0, 1, 0])
        assertEquals(Palette.BLUE, grid[1, 1, 1], "corners are at Chebyshev distance 1")
        assertEquals(Palette.EMPTY, grid[0, 0, 0], "centre is at distance 0")
        assertTrue(grid.solidCount > 0)
    }

    @Test
    fun `injects Level constants matching the grid`() {
        val grid = grid(
            """
            |return if (Level.inBounds(x, y, z) && Level.minY == -1) CYAN else null
            """.trimMargin(),
        )

        assertEquals(27, grid.solidCount, "a 3x3x3 level spans -1..1 on every axis")
    }

    @Test
    fun `works on non-cubic grids with asymmetric bounds`() {
        val grid = grid(
            """
            |return when (y) {
            |    Level.minY, Level.maxY -> RED
            |    else -> null
            |}
            """.trimMargin(),
            GridSize(4, 3, 5),
        )

        assertEquals(Palette.RED, grid[0, -1, 0], "the bottom layer")
        assertEquals(Palette.RED, grid[0, 1, 0], "the top layer")
        assertEquals(Palette.EMPTY, grid[0, 0, 0], "the middle layer matches nothing")
        assertEquals(40, grid.solidCount, "two 4x5 layers")
        // minX is -2, not -3, on a 4-wide grid: the origin sits at the centre, so the extra
        // voxel goes on the positive side. Getting this backwards is the classic off-by-one.
        assertEquals(Palette.EMPTY, grid[-3, 0, 0], "x = -3 is off a 4-wide grid")
        assertEquals(Palette.RED, grid[1, 1, 0], "maxX is 1 on a 4-wide grid")
    }

    @Test
    fun `reports a compile error against the player's own line number`() {
        val diagnostic = failure(
            """
            |return when {
            |    y == 1 -> RED
            |    else -> NOT_A_COLOUR
            |}
            """.trimMargin(),
        )

        assertEquals(Diagnostic.Kind.COMPILE_ERROR, diagnostic.kind)
        assertEquals(3, diagnostic.line, "the bad token is on the player's line 3")
        assertContains(diagnostic.message, "NOT_A_COLOUR")
        assertEquals("    else -> NOT_A_COLOUR", diagnostic.snippet)
    }

    @Test
    fun `an error on the first player line is line 1, not line 29`() {
        // The wrapper is 28 lines of preamble. If the offset were wrong, every diagnostic
        // would point somewhere the player cannot see.
        val diagnostic = failure("return NOT_A_COLOUR\n")

        assertEquals(Diagnostic.Kind.COMPILE_ERROR, diagnostic.kind)
        assertEquals(1, diagnostic.line)
        assertEquals("return NOT_A_COLOUR", diagnostic.snippet)
        assertNull(diagnostic.detail, "nothing to explain: the line really is the player's")
    }

    @Test
    fun `a body with no return is a compile error naming the function`() {
        val diagnostic = failure("val unused = 1\n")

        assertEquals(Diagnostic.Kind.COMPILE_ERROR, diagnostic.kind)
        assertContains(diagnostic.message.lowercase(), "return")
    }

    @Test
    fun `an empty editor says so instead of complaining about a missing return`() {
        // Without this, blank source compiles to an empty block body and the player is told
        // a 'return' expression is required — true, and useless.
        val diagnostic = failure("   \n\n  \n")

        assertEquals(Diagnostic.Kind.CONTRACT_ERROR, diagnostic.kind)
        assertContains(diagnostic.message, "nothing to run")
        assertNull(diagnostic.line, "there is no line to blame")
    }

    @Test
    fun `rejects an out-of-range colour id and names the coordinate`() {
        val diagnostic = failure(
            """
            |return if (x == 0 && y == 0 && z == 0) 99 else null
            """.trimMargin(),
        )

        assertEquals(Diagnostic.Kind.CONTRACT_ERROR, diagnostic.kind)
        assertContains(diagnostic.message, "block(0, 0, 0) returned 99")
    }

    @Test
    fun `times out on an infinite loop without hanging the test`() {
        val diagnostic = failure(
            """
            |while (true) { }
            """.trimMargin(),
            timeout = 2_000,
        )

        assertEquals(Diagnostic.Kind.TIMEOUT, diagnostic.kind)
    }

    @Test
    fun `surfaces a runtime exception instead of crashing`() {
        val diagnostic = failure("""error("boom")""")

        // The message is not a compile error: it compiled, then threw at runtime.
        assertTrue(
            diagnostic.kind in setOf(Diagnostic.Kind.RUNTIME_ERROR, Diagnostic.Kind.INTERNAL_ERROR),
            "expected a runtime failure, got ${diagnostic.kind}",
        )
    }

    @Test
    fun `player code cannot see game internals`() {
        val diagnostic = failure(
            """
            |return fr.godox.replikube.core.VoxelGrid
            |    .empty(fr.godox.replikube.core.GridSize(1, 1, 1)).solidCount
            """.trimMargin(),
        )

        assertEquals(Diagnostic.Kind.COMPILE_ERROR, diagnostic.kind)
        assertContains(diagnostic.snippet.orEmpty() + diagnostic.message, "Unresolved")
    }

    @Test
    fun `the starter template compiles and produces an empty grid`() {
        val grid = grid(SolutionTemplate.starter(size3))

        assertEquals(0, grid.solidCount, "the starter template renders nothing")
    }

    @Test
    fun `a catalogue-style body still runs here`() {
        // `:levels` proves all twenty references produce their declared targets; this pins
        // the *shape* of those references at this layer, since they are bodies now too.
        // `levels`' resources are not on this module's test classpath by design.
        val grid = grid(
            """
            |return if (inSphere(x, y, z, 2)) CYAN else null
            """.trimMargin(),
            GridSize(5, 5, 5),
        )

        assertEquals(33, grid.solidCount, "a radius-2 sphere in a 5x5x5 grid")
        assertEquals(Palette.EMPTY, grid[2, 2, 2], "the corners are outside the sphere")
    }
}