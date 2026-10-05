package fr.godox.replikube.scripting

import fr.godox.replikube.core.GridSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SolutionCompiler].
 *
 * These are the cheap half: no compiler is invoked, so they can pin the exact text of the
 * wrapper and the line arithmetic that turns a compiler message into something the player
 * can act on. [InProcessScriptRunnerTest] then checks the whole round trip.
 */
class SolutionCompilerTest {

    private val size3 = GridSize(3, 3, 3)

    private fun generate(code: String, size: GridSize = size3) = SolutionCompiler.generate(code, size)

    @Test
    fun `the wrapper declares the signature the player does not write`() {
        val generated = generate("return RED")

        assertTrue(
            generated.text.contains("override fun block(x: Int, y: Int, z: Int): Int? {"),
            "the player must never have to type the signature:\n${generated.text}",
        )
        assertTrue(
            generated.text.contains("class Solution : Replikube {"),
            "the wrapper implements the contract:\n${generated.text}",
        )
    }

    @Test
    fun `the player's code lands inside block and nowhere else`() {
        val generated = generate("return if (y == 0) RED else null")

        assertTrue(generated.text.contains("return if (y == 0) RED else null"))
        // The body is spliced in, so the opening brace is the line immediately above the
        // player's first line and nothing else can be declared at top level.
        val lines = generated.text.lines()
        assertEquals(
            "    override fun block(x: Int, y: Int, z: Int): Int? {",
            lines[generated.playerLineOffset - 2],
        )
        assertTrue(generated.text.trimEnd().endsWith("    }\n}"))
    }

    @Test
    fun `player lines map back one for one`() {
        val code = """
            |return when {
            |    y == 1 -> RED
            |    else -> BLUE
            |}
            """.trimMargin()
        val generated = generate(code)

        val first = generated.playerLineOffset
        assertEquals("return when {", generated.text.lines()[first - 1])
        assertEquals(code.split("\n"), generated.playerLines)

        // Every player line is reachable, and the wrapper's own lines are not.
        generated.playerLines.indices.forEach { index ->
            val playerLine = index + 1
            assertEquals(playerLine, generated.toPlayerLine(first + index))
        }
        assertNull(generated.toPlayerLine(first - 1), "the signature line is not the player's")
        assertNull(generated.toPlayerLine(first + generated.playerLines.size), "nor the closing brace")
        assertNull(generated.toPlayerLine(1), "nor the header")
    }

    @Test
    fun `the offset survives a trailing newline on the last line`() {
        // The player hitting Enter at the end of their code must not shift every diagnostic
        // by one line.
        val withoutNewline = generate("return RED\nreturn RED")
        val withNewline = generate("return RED\nreturn RED\n")

        assertEquals(withoutNewline.playerLineOffset, withNewline.playerLineOffset)
        assertEquals(withoutNewline.playerLines, withNewline.playerLines)
    }

    @Test
    fun `a blank body still produces a well-formed wrapper`() {
        // The runner rejects blank source before it ever gets here, but if it did not, the
        // generated file has to stay structurally valid rather than losing a brace.
        val generated = generate("\n\n")

        assertEquals(listOf(""), generated.playerLines)
        assertTrue(generated.text.trimEnd().endsWith("    }\n}"))
    }

    @Test
    fun `the marker never survives into the generated source`() {
        val generated = generate("return RED")

        assertFalse("replikube:player-code" in generated.text, "the splice marker leaked")
    }

    @Test
    fun `Level constants are baked in per level`() {
        val generated = SolutionCompiler.generate("return null", GridSize(4, 5, 6))

        assertTrue(generated.text.contains("const val sizeX = 4"))
        assertTrue(generated.text.contains("const val sizeY = 5"))
        assertTrue(generated.text.contains("const val sizeZ = 6"))
    }

    @Test
    fun `the starter template is a body and ends with a return`() {
        val starter = SolutionTemplate.starter(size3)

        assertFalse("fun block" in starter, "the player must not be shown a signature")
        assertTrue(starter.trimEnd().endsWith("return null"))
        // And it must be a body that fits the generated wrapper, so pressing Run on an
        // untouched editor produces an empty grid rather than a wall of errors.
        assertEquals(starter.trimEnd().split("\n"), generate(starter).playerLines)
    }
}