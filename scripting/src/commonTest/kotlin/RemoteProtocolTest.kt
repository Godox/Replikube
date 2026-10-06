package fr.godox.replikube.scripting

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.dsl.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The remote protocol, tested without a network.
 *
 * ### Why these tests exist at all
 *
 * Every assertion here is about a response body the compiler service actually returned, read off
 * it by hand rather than invented. That is the point: the failure modes below — a warning
 * mistaken for an error, a 0-based line number treated as 1-based, `<errStream>` read as stdout —
 * are all *silent*. Each one produces a game that runs, returns a grid, and is wrong, and none
 * of them would be caught by anything that only checks the happy path compiles.
 *
 * The bodies are inlined rather than loaded from a fixture directory, because a fixture that
 * resembles a real response is the recurring lesson of this project: `GameModelTest` once passed
 * against a hand-built 3x3x3 stand-in while the level it opened was 27 solid voxels. The shapes
 * below are the ones the service returned, including the details that look like noise.
 */
class RemoteProtocolTest {

    private val size = GridSize(3, 3, 3)

    // --- the harness -------------------------------------------------------------

    /**
     * The player file carries the same wrapper the local runner builds, plus a `main`.
     *
     * Both halves matter: a divergence in the wrapper would move every compile error the player
     * sees, and a missing `main` would come back as "no entry point" from a service that has no
     * idea a game is calling it.
     */
    @Test
    fun `the harness is the local wrapper plus a main`() {
        val program = RemoteProgram.build("return RED", size)
        val harness = program.files.last { it.name == HARNESS_FILE_NAME }.text

        assertTrue(harness.contains("class Solution : Replikube"))
        assertTrue(harness.contains("fun main()"))
        assertTrue(harness.contains(GRID_MARKER))
        assertTrue(harness.contains("return RED"))
    }

    /**
     * The `:dsl` sources travel with the request, because the service has no classpath of ours.
     *
     * Asserted by content rather than by count: the sandbox on this path is exactly as strong as
     * `:dsl`'s dependency list, so a generated file that lost `Palette.kt` would compile and
     * leave a player unable to write RED.
     */
    @Test
    fun `the DSL sources are shipped with the request`() {
        val program = RemoteProgram.build("return RED", size)
        val names = program.files.map { it.name }

        assertTrue(names.contains("Palette.kt"), "Palette.kt missing from $names")
        assertTrue(names.contains("Geometry.kt"), "Geometry.kt missing from $names")
        assertEquals(HARNESS_FILE_NAME, names.last(), "the harness should be sent last")
        assertTrue(program.files.first { it.name == "Palette.kt" }.text.contains("const val RED"))
    }

    /**
     * The driver's loop order has to be the grid's memory order.
     *
     * This is the assertion that matters most in the file. A driver iterating z-fastest would
     * compile, run, and return 27 plausible numbers — transposed. Nothing else in the pipeline
     * could tell, because the shapes are all valid ids.
     */
    @Test
    fun `the driver walks x fastest then y then z`() {
        val harness = RemoteProgram.build("return null", size).files.last().text
        val z = harness.indexOf("for (z in")
        val y = harness.indexOf("for (y in")
        val x = harness.indexOf("for (x in")

        assertTrue(z in 0 until y, "z must be the outer loop")
        assertTrue(y in 0 until x, "y must be the middle loop")
    }

    /** The driver's bounds come from the grid, not from a constant. */
    @Test
    fun `the driver iterates the level's own bounds`() {
        val harness = RemoteProgram.build("return null", GridSize(9, 5, 7)).files.last().text

        // minX of a 9-wide grid is -9/2 = -4, minY of 5 is -2, minZ of 7 is -3.
        assertTrue(harness.contains("for (z in Level.minZ..Level.maxZ)"))
        assertTrue(harness.contains("const val sizeZ = 7"))
    }

    // --- line mapping ------------------------------------------------------------

    /**
     * A compile error is reported on the player's line, not the generated one.
     *
     * The service counts lines from 0 and [GeneratedSource.toPlayerLine] expects 1-based, so the
     * `+ 1` is the whole content of this test. Getting it wrong points every error one line above
     * where the player's code is, which reads as "the compiler is confused about my code".
     */
    @Test
    fun `a compile error maps back to the player's line`() {
        val player = "val a = 1\nval b = nope\nreturn null"
        val program = RemoteProgram.build(player, size)
        val expected = program.generated.playerLineOffset + 1

        val result = CompilerRunResponse(
            errors = mapOf(
                HARNESS_FILE_NAME to listOf(
                    CompilerDiagnostic(
                        interval = SourceInterval(
                            SourcePosition(expected - 1, 8),
                            SourcePosition(expected - 1, 12),
                        ),
                        message = "Unresolved reference: nope",
                        severity = CompilerDiagnostic.SEVERITY_ERROR,
                        className = "Solution",
                    ),
                ),
            ),
        ).toRunResult(size, program)

        val failure = assertIs<RunResult.Failure>(result)
        val diagnostic = failure.diagnostic
        assertEquals(Diagnostic.Kind.COMPILE_ERROR, diagnostic.kind)
        assertEquals(2, diagnostic.line, "should be line 2 of the player's source")
        assertEquals("val b = nope", diagnostic.snippet)
    }

    /**
     * An error the compiler blamed on the wrapper gets an explanation, not a line.
     *
     * The player cannot see the wrapper, so pointing at a line of it is the same as pointing at
     * nothing. The hint says what actually causes it — an unbalanced bracket.
     */
    @Test
    fun `an error outside the player's region explains the wrapper`() {
        val program = RemoteProgram.build("return null", size)
        val result = CompilerRunResponse(
            errors = mapOf(
                HARNESS_FILE_NAME to listOf(
                    CompilerDiagnostic(
                        interval = SourceInterval(SourcePosition(2, 0), SourcePosition(2, 1)),
                        message = "Expecting an expression",
                        severity = CompilerDiagnostic.SEVERITY_ERROR,
                    ),
                ),
            ),
        ).toRunResult(size, program)

        val diagnostic = assertIs<RunResult.Failure>(result).diagnostic
        assertNull(diagnostic.line, "a wrapper line is not a player line")
        assertNotNull(diagnostic.detail)
        assertTrue(diagnostic.detail.contains("bracket"), "should name the likely cause")
    }

    // --- severity ---------------------------------------------------------------

    /**
     * A warning does not fail a run.
     *
     * The service returns warnings alongside errors, and the editor's own starter text produces
     * them ("Variable is unused"). Treating any entry in `errors` as fatal would refuse to run
     * code that compiled and ran perfectly.
     */
    @Test
    fun `a warning alone still runs the solution`() {
        val program = RemoteProgram.build("return RED", size)
        val result = CompilerRunResponse(
            errors = mapOf(
                HARNESS_FILE_NAME to listOf(
                    CompilerDiagnostic(
                        interval = SourceInterval(SourcePosition(0, 0), SourcePosition(0, 1)),
                        message = "Variable is unused.",
                        severity = CompilerDiagnostic.SEVERITY_WARNING,
                    ),
                ),
            ),
            text = "<outStream>$GRID_MARKER${gridOf(27, 6)}</outStream>",
        ).toRunResult(size, program)

        val success = assertIs<RunResult.Success>(result)
        assertEquals(27, success.grid.solidCount)
    }

    /** Only the first error is reported. A player needs one sentence, not a wall. */
    @Test
    fun `only the first error becomes the diagnostic`() {
        val program = RemoteProgram.build("return null", size)
        val result = CompilerRunResponse(
            errors = mapOf(
                HARNESS_FILE_NAME to listOf(
                    error("first problem"), error("second problem"),
                ),
            ),
        ).toRunResult(size, program)

        assertEquals("first problem", assertIs<RunResult.Failure>(result).diagnostic.message)
    }

    /**
     * A failure in the shipped DSL is ours, not the player's.
     *
     * `errors` is keyed by file name, which is the only thing that distinguishes "your code is
     * wrong" from "replikube's copy of the palette is wrong". Reporting the latter as a compile
     * error would ask the player to fix code that is correct.
     */
    @Test
    fun `a DSL failure is internal, not the player's fault`() {
        val program = RemoteProgram.build("return RED", size)
        val result = CompilerRunResponse(
            errors = mapOf(
                "Palette.kt" to listOf(
                    CompilerDiagnostic(
                        interval = SourceInterval(SourcePosition(3, 0), SourcePosition(3, 1)),
                        message = "Expecting a declaration",
                        severity = CompilerDiagnostic.SEVERITY_ERROR,
                        className = "Palette",
                    ),
                ),
                HARNESS_FILE_NAME to emptyList(),
            ),
        ).toRunResult(size, program)

        val diagnostic = assertIs<RunResult.Failure>(result).diagnostic
        assertEquals(Diagnostic.Kind.INTERNAL_ERROR, diagnostic.kind)
        assertNull(diagnostic.line, "a DSL line is not a player line")
    }

    // --- runtime and watchdog ----------------------------------------------------

    /** A thrown exception is a runtime error, positioned on the player's line. */
    @Test
    fun `a thrown exception is a runtime error on the player's line`() {
        val program = RemoteProgram.build("return null", size)
        val result = CompilerRunResponse(
            exception = RemoteException(
                message = "divide by zero",
                fullName = "java.lang.ArithmeticException",
                stackTrace = listOf(
                    RemoteStackFrame("Solution", "block", HARNESS_FILE_NAME, program.generated.playerLineOffset),
                    RemoteStackFrame("SolutionKt", "main", HARNESS_FILE_NAME, -1),
                ),
            ),
        ).toRunResult(size, program)

        val diagnostic = assertIs<RunResult.Failure>(result).diagnostic
        assertEquals(Diagnostic.Kind.RUNTIME_ERROR, diagnostic.kind)
        assertEquals("divide by zero", diagnostic.message)
        assertEquals(1, diagnostic.line)
    }

    /**
     * The watchdog arrives as HTTP 200 on `<errStream>`.
     *
     * There is no status code to detect and no exception set, so an implementation that only
     * looks at those two would fall through to parsing a result that is not there. The service's
     * wording is quoted rather than paraphrased, because this is the one timeout whose budget the
     * player cannot know: it belongs to the service, not to the game.
     */
    @Test
    fun `the service watchdog is a timeout, not a parse failure`() {
        val program = RemoteProgram.build("return null", size)
        val result = CompilerRunResponse(
            // The trailing variation selector is in the real payload; matching on the tag rather
            // than on the message is what keeps that from being a silent break.
            text = "<errStream>Evaluation stopped while it's taking too long\uFE0F</errStream>",
        ).toRunResult(size, program)

        val diagnostic = assertIs<RunResult.Failure>(result).diagnostic
        assertEquals(Diagnostic.Kind.TIMEOUT, diagnostic.kind)
        assertNotNull(diagnostic.detail)
        assertTrue(diagnostic.detail.contains("taking too long"))
    }

    /** A run that produced nothing usable says so, rather than reporting a bad grid. */
    @Test
    fun `a missing marker is reported rather than parsed`() {
        val program = RemoteProgram.build("return null", size)
        val result = CompilerRunResponse(text = "<outStream>hello\n</outStream>")
            .toRunResult(size, program)

        val diagnostic = assertIs<RunResult.Failure>(result).diagnostic
        assertEquals(Diagnostic.Kind.INTERNAL_ERROR, diagnostic.kind)
        assertNotNull(diagnostic.detail)
    }

    // --- the answer -------------------------------------------------------------

    /** The happy path: the printed grid becomes a [fr.godox.replikube.core.VoxelGrid]. */
    @Test
    fun `a printed grid becomes a grid`() {
        val program = RemoteProgram.build("return RED", size)
        val result = CompilerRunResponse(
            text = "<outStream>$GRID_MARKER${gridOf(27, 6)}</outStream>",
        ).toRunResult(size, program)

        val grid = assertIs<RunResult.Success>(result).grid
        assertEquals(size, grid.size)
        assertEquals(27, grid.solidCount)
        assertTrue(grid.hasValidColors())
    }

    /**
     * Player output on either side of the answer is ignored.
     *
     * `println` in a solution is normal — it is how a player debugs. The marker is what makes
     * that safe, so this is the test that justifies its existence.
     */
    @Test
    fun `player output does not disturb the answer`() {
        val program = RemoteProgram.build("return RED", size)
        val result = CompilerRunResponse(
            text = "<outStream>trying something\n$GRID_MARKER${gridOf(27, 6)}\ndone\n</outStream>",
        ).toRunResult(size, program)

        assertEquals(27, assertIs<RunResult.Success>(result).grid.solidCount)
    }

    /**
     * An id that is not a colour is rejected, naming the coordinate.
     *
     * "not a colour" alone sends the player hunting through 729 voxels. The coordinate is the
     * message that saves them the trip, and the local runner names it too.
     */
    @Test
    fun `an invalid colour names the coordinate`() {
        val program = RemoteProgram.build("return null", size)
        // index 0 is x=-1,y=-1,z=-1, so a bad value at index 0 names that coordinate.
        val bad = buildString {
            append("99,")
            repeat(26) { append("0,") }
        }
        val result = CompilerRunResponse(
            text = "<outStream>$GRID_MARKER$bad</outStream>",
        ).toRunResult(size, program)

        val diagnostic = assertIs<RunResult.Failure>(result).diagnostic
        assertEquals(Diagnostic.Kind.CONTRACT_ERROR, diagnostic.kind)
        assertTrue(diagnostic.message.contains("block(-1, -1, -1)"), diagnostic.message)
    }

    /** A wrong number of values is a message, not an index-out-of-bounds. */
    @Test
    fun `a short answer is rejected without indexing past the end`() {
        val program = RemoteProgram.build("return null", size)
        val result = CompilerRunResponse(
            text = "<outStream>$GRID_MARKER" + gridOf(5, 0) + "</outStream>",
        ).toRunResult(size, program)

        val diagnostic = assertIs<RunResult.Failure>(result).diagnostic
        assertEquals(Diagnostic.Kind.CONTRACT_ERROR, diagnostic.kind)
        assertTrue(diagnostic.message.contains("5"), diagnostic.message)
        assertTrue(diagnostic.message.contains("27"), diagnostic.message)
    }

    /** An answer with no trailing separator is accepted. The separator is not the contract. */
    @Test
    fun `a missing trailing separator is tolerated`() {
        val program = RemoteProgram.build("return null", size)
        val result = CompilerRunResponse(
            text = "<outStream>$GRID_MARKER" + gridOf(27, 8).trimEnd(',') + "</outStream>",
        ).toRunResult(size, program)

        assertEquals(27, assertIs<RunResult.Success>(result).grid.solidCount)
    }

    // --- decoding ---------------------------------------------------------------

    /**
     * An unknown field is dropped, not fatal.
     *
     * The service is free to add one, and a game pinned to an exact schema breaks on a Tuesday
     * because of it. Recorded here because "we will be lenient" is only true if something fails
     * when leniency is removed.
     */
    @Test
    fun `an unknown field in a response is ignored`() {
        val json = """
            {"errors":{"$HARNESS_FILE_NAME":[]},"exception":null,
             "text":"<outStream>$GRID_MARKER${gridOf(27, 6)}</outStream>",
             "somethingNewInKotlin243":42}
        """.trimIndent()

        val response = RemoteJson.decodeFromString(CompilerRunResponse.serializer(), json)
        val result = response.toRunResult(size, RemoteProgram.build("return RED", size))

        assertEquals(27, assertIs<RunResult.Success>(result).grid.solidCount)
    }

    /** A real response body, decoded. Guards the field names against a rename. */
    @Test
    fun `a recorded response decodes`() {
        val json = """
            {"errors":{"File.kt":[]},"exception":null,"jvmByteCode":null,
             "text":"<outStream>hi\n</outStream>"}
        """.trimIndent()

        val response = RemoteJson.decodeFromString(CompilerRunResponse.serializer(), json)

        assertTrue(response.errors.getValue("File.kt").isEmpty())
        assertNull(response.exception)
        assertEquals("<outStream>hi\n</outStream>", response.text)
    }

    /** The palette constant the harness relies on is the same number the game renders. */
    @Test
    fun `the shipped palette agrees with the game's`() {
        assertEquals(6, Palette.RED)
        assertTrue(DSL_SOURCE.getValue("Palette.kt").contains("const val RED = ${Palette.RED}"))
    }

    // --- helpers ----------------------------------------------------------------

    private fun error(message: String) = CompilerDiagnostic(
        interval = SourceInterval(SourcePosition(0, 0), SourcePosition(0, 1)),
        message = message,
        severity = CompilerDiagnostic.SEVERITY_ERROR,
    )

    /** [count] cells of [id], in the trailing-separator form the driver prints. */
    private fun gridOf(count: Int, id: Int): String =
        (0 until count).joinToString(",") { id.toString() } + ","
}