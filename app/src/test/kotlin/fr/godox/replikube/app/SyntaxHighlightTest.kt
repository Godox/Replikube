package fr.godox.replikube.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import fr.godox.replikube.app.render.PaletteColors
import fr.godox.replikube.dsl.Palette
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The editor's syntax highlighter.
 *
 * A highlighter is code that looks correct and is silently wrong: one mis-tokenized string
 * swallows the rest of the file, one dropped span hides a token, and neither shows up as a
 * crash. So the tests here are about *invariants over the whole output* rather than
 * examples — the text must come back byte-identical, the spans must tile without overlap,
 * and every colour must be readable.
 */
class SyntaxHighlightTest {

    // -- the invariant that makes the whole technique safe --------------------------

    @Test
    fun `highlighting never changes a single character`() {
        // This is not a cosmetic property. The editor hands the result to a
        // VisualTransformation and declares the offset mapping to be the identity, which is
        // only true if the text is unchanged. Shift it by one character and the caret moves
        // somewhere else after every keystroke — the classic failure of this technique, and
        // one that a screenshot of coloured text would never reveal.
        val sources = listOf(
            "return null",
            "return if (x == 0 && y == 0 && z == 0) MAGENTA else null",
            "// a comment with RED and return in it",
            "val s = \"hello \\\" world\"",
            "return when (y) { 1 -> RED\n0 -> YELLOW\nelse -> GREEN }",
            "",
            "\n\n\n",
            "\"unterminated",
            "//",
            "/* not a block comment */",
            "0x1F + 0b1010 + 1_000 + 3.14f",
            "if (x !in -1..1) return 0",
            "val fn = { a: Int -> a * 2 }",
        )
        for (source in sources) {
            assertEquals(source, highlight(source).text, "round-trip of ${source.take(30)}")
        }
    }

    @Test
    fun `spans are ordered and never overlap`() {
        // Spans that overlap or run backwards are not a cosmetic problem either: Compose
        // applies them in order, so an overlapping span silently overrides another one and
        // a token changes colour depending on how the scanner happened to split it.
        for (source in listOf(
            "return if (x == 0) RED else null // done",
            "val t = \"a//b\" // real comment",
            "1 + 2 // 3",
        )) {
            val spans = highlight(source).spanStyles.sortedBy { it.start }
            var previousEnd = 0
            for (span in spans) {
                assertTrue(span.start >= previousEnd, "overlap or out-of-order in: $source")
                assertTrue(span.end > span.start, "empty span in: $source")
                assertTrue(span.end <= source.length, "span past end of text in: $source")
                previousEnd = span.end
            }
        }
    }

    // -- what each token kind gets ----------------------------------------------------

    @Test
    fun `a keyword is coloured and an ordinary name is not`() {
        val h = highlight("return null")
        assertEquals(SyntaxStyles.keyword.color, h.colorAt(0), "`return` should be a keyword")
        assertEquals(SyntaxStyles.keyword.color, h.colorAt(7), "`null` should be a keyword")
    }

    @Test
    fun `null is treated as a keyword not an identifier`() {
        // `return null` is the game's contract for an empty voxel. It has to read as
        // language rather than as a variable name, or the one line every player writes
        // looks like a mistake.
        assertEquals(SyntaxStyles.keyword.color, highlight("return null").colorAt(7))
    }

    @Test
    fun `a player's own name is left as plain text`() {
        val h = highlight("val greens = 1")
        assertEquals(SyntaxStyles.plain.color, h.colorAt(4))
        assertEquals(SyntaxStyles.keyword.color, h.colorAt(0))
    }

    @Test
    fun `a palette name is painted in its own colour`() {
        // The point of colouring a palette constant differently from every other word is
        // that it teaches the palette without a legend. This pins the mapping the player is
        // being taught: `GREEN` is green.
        val h = highlight("return GREEN")
        assertEquals(paletteTokenColor(Palette.GREEN), h.colorAt(7))
    }

    @Test
    fun `palette names are distinguished from lookalike identifiers`() {
        // `GREENISH` is the player's variable, not a colour. Getting this wrong would paint
        // half of a player's own code in palette colours and make the editor useless.
        // Offsets are looked up rather than counted. `GREEN` is a prefix of `GREENISH`, so the
        // colour is the *last* occurrence and the variable the first — spelled out here because
        // "4" and "14" are the kind of numbers that are wrong silently and in a hurry.
        val source = "val GREENISH = GREEN"
        val h = highlight(source)
        assertEquals(
            SyntaxStyles.plain.color,
            h.colorAt(source.indexOf("GREENISH")),
            "GREENISH should be plain",
        )
        assertEquals(
            paletteTokenColor(Palette.GREEN),
            h.colorAt(source.lastIndexOf("GREEN")),
            "GREEN should be a colour",
        )
    }

    @Test
    fun `every palette name the DSL knows is highlighted`() {
        // The name list is derived from the palette rather than written out, so this also
        // pins that derivation: a colour added to the DSL appears in the editor with no
        // second list to forget.
        for (id in 0..Palette.MAX) {
            val word = Palette.nameOf(id)
            assertEquals(
                paletteTokenColor(id),
                highlight("return $word").colorAt(7),
                "$word should be painted as palette id $id",
            )
        }
    }

    @Test
    fun `a comment takes the whole rest of the line with it`() {
        // Tutorial starters are mostly prose, and the failure mode here is a keyword or a
        // palette name *after* a `//` keeping its colour, which reads as a highlighting bug
        // in the player's own code.
        val h = highlight("return RED // then GREEN and null")
        assertEquals(SyntaxStyles.comment.color, h.colorAt(11))
        assertEquals(SyntaxStyles.comment.color, h.colorAt(h.text.length - 1))
    }

    @Test
    fun `a comment stops at the newline`() {
        val source = "// hidden\nreturn RED"
        val h = highlight(source)
        assertEquals(SyntaxStyles.comment.color, h.colorAt(0))
        assertEquals(paletteTokenColor(Palette.RED), h.colorAt(source.indexOf("RED")))
    }

    @Test
    fun `a slash that is not a comment is left alone`() {
        // Division is ordinary Kotlin and appears in real solutions. Treating a bare `/` as
        // the start of a comment would grey out the rest of the line for no reason.
        val h = highlight("return (a + b) / 2")
        assertNull(h.colorAt(15), "the `/` should carry no style of its own")
    }

    @Test
    fun `numbers are coloured as one token`() {
        val source = "return x + 42"
        val h = highlight(source)
        val digits = source.indexOf("42")
        assertEquals(SyntaxStyles.number.color, h.colorAt(digits))
        assertEquals(SyntaxStyles.number.color, h.colorAt(digits + 1))
    }

    @Test
    fun `a string is coloured, and an escape does not end it early`() {
        val source = "return \"a \\\" b\""
        val h = highlight(source)
        val open = source.indexOf('"')
        assertEquals(SyntaxStyles.string.color, h.colorAt(open))
        assertEquals(SyntaxStyles.string.color, h.colorAt(source.length - 1), "closing quote")
    }

    @Test
    fun `an unterminated string stops at the newline`() {
        // A player who has just typed `"` must still see their line. A highlighter that
        // runs to end-of-file turns one half-typed character into a green wall and looks
        // like the editor has hung.
        val source = "val a = \"oops\nreturn RED"
        val h = highlight(source)
        assertEquals(SyntaxStyles.string.color, h.colorAt(source.indexOf('"')))
        assertEquals(paletteTokenColor(Palette.RED), h.colorAt(source.indexOf("RED")))
    }

    // -- legibility ------------------------------------------------------------------

    @Test
    fun `every token colour is readable on the editor background`() {
        // A token that is too dark does not look like a colour problem. It looks like the
        // token was not recognised — which sends a player hunting for a highlighting bug
        // that does not exist, and it is invisible in a code review of this file.
        val styles = listOf(
            SyntaxStyles.keyword,
            SyntaxStyles.string,
            SyntaxStyles.number,
            SyntaxStyles.comment,
            SyntaxStyles.plain,
        ) + (0..Palette.MAX).map { SyntaxStyles.paletteToken(it) }

        for (style in styles) {
            val color = assertNotNull(style.color, "a token style with no colour")
            assertEquals(
                1f, color.alpha,
                "${hex(color)} is not opaque — it would render as nothing",
            )
            assertTrue(
                contrastRatio(color, AppColors.editor) >= MIN_TEXT_CONTRAST,
                "${hex(color)} on ${hex(AppColors.editor)} is " +
                    "${"%.2f".format(contrastRatio(color, AppColors.editor))}:1, " +
                    "below the ${MIN_TEXT_CONTRAST}:1 needed to read 14sp text",
            )
        }
    }

    @Test
    fun `the palette greys that need lifting get it`() {
        // `BLACK` as a cube face is drawn nearly black and reads fine, because a face has
        // lit edges to find it by. As text on the editor it is invisible. This pins that the
        // lift is actually applied where it is needed, and — just as importantly — *not*
        // applied where it is not, since lifting everything would flatten the palette into
        // sixteen near-identical greys.
        assertTrue(
            contrastRatio(paletteTokenColor(Palette.BLACK), AppColors.editor) >= MIN_TEXT_CONTRAST,
        )
        assertEquals(
            PaletteColors.of(Palette.WHITE),
            paletteTokenColor(Palette.WHITE),
            "WHITE is already readable and must not be touched",
        )
    }

    @Test
    fun `a lifted palette colour keeps its hue`() {
        // The whole reason for lifting rather than inventing sixteen text colours is that
        // the editor and the viewport agree about what a token means. A lift that swung the
        // hue would leave `RED` reading as pink and break that agreement.
        for (id in 0..Palette.MAX) {
            val raw = PaletteColors.of(id)
            val lifted = paletteTokenColor(id)
            if (contrastRatio(raw, AppColors.editor) >= MIN_TEXT_CONTRAST) continue
            val rawHue = hueOf(raw)
            val liftedHue = hueOf(lifted)
            assertTrue(
                hueDistance(rawHue, liftedHue) < HUE_TOLERANCE,
                "${Palette.nameOf(id)} moved hue: ${hex(raw)} -> ${hex(lifted)}",
            )
        }
    }

    // -- the lifting maths ------------------------------------------------------------

    @Test
    fun `lifting is the least lift that reaches the target`() {
        // Not "lift until it looks right". One step more than needed is invisible, but a
        // rule that over-lifts is a rule that will eventually over-lift past legibility.
        val nearly = Color(0xFF6E6E6E)
        val lifted = nearly.liftedForText(target = MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(lifted, AppColors.editor) >= MIN_TEXT_CONTRAST)
        assertTrue(contrastRatio(lifted, AppColors.editor) < MIN_TEXT_CONTRAST + 0.25f)
    }

    @Test
    fun `a colour that already passes is returned untouched`() {
        val white = Color.White
        assertEquals(white, white.liftedForText())
    }

    @Test
    fun `contrastRatio matches the published anchors`() {
        // The two values every implementation of this formula is checked against. If these
        // fail the formula is wrong and every contrast assertion above is meaningless.
        assertEquals(21f, contrastRatio(Color.Black, Color.White), absoluteTolerance)
        assertEquals(21f, contrastRatio(Color.White, Color.Black), absoluteTolerance)
        assertEquals(1f, contrastRatio(Color(0xFF123456), Color(0xFF123456)), absoluteTolerance)
    }

    @Test
    fun `contrastRatio is symmetric`() {
        val a = Color(0xFF3B6EA5)
        val b = Color(0xFF11141A)
        assertEquals(contrastRatio(a, b), contrastRatio(b, a), absoluteTolerance)
    }

    // -- helpers ----------------------------------------------------------------------

    private companion object {
        const val absoluteTolerance = 0.01f

        /** Hue tolerance in degrees: lifting toward white desaturates, and a greyscale has no hue. */
        const val HUE_TOLERANCE = 12f

        /** The style in force at [index], or null if the character carries none. */
        fun AnnotatedString.colorAt(index: Int): Color? = spanStyles
            .filter { index >= it.start && index < it.end }
            .lastOrNull()
            ?.item
            ?.color
    }
}

/** A colour as `RRGGBB`, for assertion messages that a player could read. */
private fun hex(c: Color): String = "#%02X%02X%02X".format(
    (c.red * 255).toInt(),
    (c.green * 255).toInt(),
    (c.blue * 255).toInt(),
)

/** Hue in degrees, or null for a grey with no hue to speak of. */
private fun hueOf(c: Color): Float? {
    val r = c.red
    val g = c.green
    val b = c.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    if (abs(max - min) < 0.02f) return null
    val delta = max - min
    val hue = when (max) {
        r -> 60 * (((g - b) / delta) % 6)
        g -> 60 * (((b - r) / delta) + 2)
        else -> 60 * (((r - g) / delta) + 4)
    }
    return if (hue < 0) hue + 360 else hue
}

/** Shortest distance between two hues in degrees, treating null as matching anything grey. */
private fun hueDistance(a: Float?, b: Float?): Float = when {
    a == null || b == null -> 0f
    else -> {
        val direct = abs(a - b)
        minOf(direct, 360 - direct)
    }
}
