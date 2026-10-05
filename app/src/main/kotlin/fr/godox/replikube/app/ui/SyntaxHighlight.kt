package fr.godox.replikube.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import fr.godox.replikube.app.render.PaletteColors
import fr.godox.replikube.dsl.Palette
import kotlin.math.pow

/**
 * Colours the player's Kotlin, for the code editor.
 *
 * ### Why this is a pure function and not a `VisualTransformation`
 *
 * A `VisualTransformation` is the only way to colour text inside a `BasicTextField` without
 * replacing the whole field, but it cannot be tested on its own: it hands back a
 * `TransformedText`, so testing it means standing up a text layout. The tokenizer is
 * therefore a pure [String] to [AnnotatedString] function, which the editor wraps in a
 * `VisualTransformation` and a test can call directly. A highlighter is exactly the kind of
 * code that looks right and is wrong — one mis-tokenized string swallows the rest of the
 * file — so it wants to be something you can hand a string to and inspect.
 *
 * ### Why a lexer and not the Kotlin compiler
 *
 * The compiler is available — that is the premise of the game — but invoking a full parse
 * per keystroke to decide which of six colours a token gets is not worth it, and a parse
 * that *fails* has no tree to read colours off. A parse fails on every keystroke while a
 * player is halfway through typing `if`. This is a lexer: it cannot fail, and anything it
 * does not recognise is left unstyled, so a half-typed line still colours the part of it
 * that is complete.
 *
 * ### Why palette names are painted in their own colour
 *
 * `RED` is not a name here, it is a colour. Painting it in its own colour teaches the
 * palette without a legend, and puts the mapping from constant to cube where the player is
 * already looking. The shades are lifted toward white where the raw value would be
 * unreadable — see [liftedForText], which is the reason this file has a contrast ratio in
 * it at all.
 */
internal fun highlight(code: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < code.length) {
        val c = code[i]
        when {
            // A line comment runs to the end of the line, taking everything with it. The
            // tutorial starters are mostly prose, so this is the commonest token here.
            c == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                withStyle(SyntaxStyles.comment) { append(code, i, end) }
                i = end
            }

            // Strings. Escapes are honoured so `\"` does not end the token early, and an
            // unterminated string stops at the newline: a player who has just typed `"`
            // should still see their line, not a wall of green to the bottom of the file.
            c == '"' -> {
                var j = i + 1
                while (j < code.length && code[j] != '"' && code[j] != '\n') {
                    if (code[j] == '\\') j++
                    j++
                }
                val end = minOf(j + 1, code.length)
                withStyle(SyntaxStyles.string) { append(code, i, end) }
                i = end
            }

            c.isDigit() -> {
                var j = i
                while (j < code.length && (code[j].isDigit() || code[j] in ".xXbBoO_")) j++
                withStyle(SyntaxStyles.number) { append(code, i, j) }
                i = j
            }

            c.isLetter() || c == '_' -> {
                var j = i
                while (j < code.length && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                val style = when {
                    // Only a whole word is a palette name: `GREENISH` is the player's own
                    // variable, not a colour.
                    word in PALETTE_NAMES -> SyntaxStyles.paletteToken(PALETTE_NAMES.getValue(word))
                    word in KEYWORDS -> SyntaxStyles.keyword
                    else -> SyntaxStyles.plain
                }
                withStyle(style) { append(word) }
                i = j
            }

            else -> {
                append(c)
                i++
            }
        }
    }
}

/**
 * The Kotlin keywords reachable from a function body.
 *
 * A short list, not the language's. This is a colour hint, not a linter, and listing sixty
 * hard keywords to colour eight of them helps nobody. `null` is here rather than treated as
 * an ordinary name because in this game it is a *value*: it is the answer for an empty
 * voxel, and the contract the player is given is `return null`.
 */
private val KEYWORDS = setOf(
    "as", "break", "continue", "do", "else", "false", "for", "fun", "if", "in",
    "is", "null", "return", "true", "until", "val", "var", "when", "while",
)

/**
 * The palette's names, built from [Palette.nameOf] rather than written out.
 *
 * Derived so the editor's vocabulary cannot drift from the DSL: adding a colour to the
 * palette adds it to the highlighter, with no second list to forget.
 */
private val PALETTE_NAMES: Map<String, Int> =
    (0..Palette.MAX).associate { Palette.nameOf(it) to it }

/**
 * The colours the editor paints tokens with.
 *
 * Each is contrast-checked against [AppColors.editor] in `SyntaxHighlightTest`, because a
 * token colour that is too dark does not look like a bug — it looks like the token was not
 * recognised, which sends a player hunting for a highlighting bug that does not exist.
 */
internal object SyntaxStyles {
    val plain = SpanStyle(color = AppColors.foreground)
    val keyword = SpanStyle(color = Color(0xFFC792EA))
    val string = SpanStyle(color = Color(0xFFC3E88D))
    val number = SpanStyle(color = Color(0xFFF78C6C))
    val comment = SpanStyle(color = Color(0xFF7A8899), fontStyle = FontStyle.Italic)

    /**
     * The token's own colour, lifted if the raw value would be unreadable as text.
     *
     * Bold as well as coloured, because the palette greys cannot all be told apart by hue
     * even once they are lifted — `WHITE`, `LIGHT_GRAY` and `GRAY` are the same colour at
     * different lightnesses, and a player reading `GRAY` in the editor should be able to
     * tell it is a palette constant rather than their own variable name.
     */
    fun paletteToken(id: Int): SpanStyle =
        SpanStyle(color = paletteTokenColor(id), fontWeight = FontWeight.SemiBold)
}

/**
 * Each palette colour, lifted by the least amount that makes it readable as text.
 *
 * A cube face and a line of text do not have the same legibility constraint. The palette is
 * chosen so `BLACK` and `DARK_GRAY` read as distinct *cubes* against lit faces with drawn
 * edges; the same values as 14sp monospace on the editor's dark background are not legible
 * at all. Rather than pick sixteen new colours by eye — which would drift from the palette
 * the player sees in the viewport — each value is lifted toward white until it clears a
 * contrast ratio of [MIN_TEXT_CONTRAST]. The hue survives, so `BLACK` still reads as the
 * grey end of the palette and the two places still agree about what a token means.
 */
private val liftedPalette: Map<Int, Color> =
    (0..Palette.MAX).associateWith { PaletteColors.of(it).liftedForText() }

/** The colour a palette token is painted in the editor. */
internal fun paletteTokenColor(id: Int): Color = liftedPalette[id] ?: AppColors.foreground

/** Contrast ratio below which 14sp text on the editor background is not comfortably readable. */
internal const val MIN_TEXT_CONTRAST = 4.5f

/**
 * This colour lightened toward white by the least amount that reaches [target] contrast
 * against [against], or unchanged if it already does.
 *
 * Bisected rather than solved in closed form. WCAG contrast is a ratio of two linear
 * luminances, and luminance is a weighted sum of cube roots, so there is no tidy formula
 * worth deriving — and a subtly wrong one here would silently ship a palette of unreadable
 * greys that still compiles and still renders. Thirty-two steps of bisection resolve the
 * blend to far under one 8-bit step.
 */
internal fun Color.liftedForText(
    against: Color = AppColors.editor,
    target: Float = MIN_TEXT_CONTRAST,
): Color {
    // Alpha is forced to opaque before anything else is measured. `PaletteColors.of(EMPTY)`
    // is fully transparent — "no cube" has no colour — and carrying that alpha into a token
    // would render the word `EMPTY` as nothing at all, which is precisely the name the player
    // most needs to read. Contrast is a function of the RGB channels alone, so dropping alpha
    // changes no ratio; it only stops a transparent colour from being mistaken for a legible
    // one. Both exits below must therefore go through [opaque], not `this`.
    val opaque = Color(red = red, green = green, blue = blue, alpha = 1f)
    if (contrastRatio(opaque, against) >= target) return opaque
    var lo = 0f
    var hi = 1f
    repeat(BISECTION_STEPS) {
        val mid = (lo + hi) / 2f
        if (contrastRatio(mixTowardWhite(mid), against) >= target) hi = mid else lo = mid
    }
    return mixTowardWhite(hi).copy(alpha = 1f)
}

private const val BISECTION_STEPS = 32

/** This colour blended toward white by [amount] in `0..1`. */
internal fun Color.mixTowardWhite(amount: Float): Color =
    Color(
        red = red + (1f - red) * amount,
        green = green + (1f - green) * amount,
        blue = blue + (1f - blue) * amount,
        alpha = alpha,
    )

/**
 * The WCAG contrast ratio between two colours: 1 when identical, 21 for black on white.
 *
 * The published definition rather than an approximation. An approximation is fine for a
 * designer's eye and useless for a test that has to fail when a colour is too dark.
 */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** WCAG relative luminance: each channel linearised, then weighted. */
private fun relativeLuminance(c: Color): Float =
    0.2126f * linearise(c.red) +
        0.7152f * linearise(c.green) +
        0.0722f * linearise(c.blue)

/** One sRGB channel, expressed as a fraction, converted to linear light. */
private fun linearise(channel: Float): Float =
    if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)