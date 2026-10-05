package fr.godox.replikube.app.ui

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp

/**
 * The game's colours and text styles, in one place.
 *
 * A puzzle game about voxels is mostly flat dark chrome around a bright viewport, so the
 * chrome is deliberately desaturated: the only saturated colour on screen should be the
 * player's own voxels. Three families of grey, one accent, one error red.
 *
 * Material 3 is deliberately *not* used. Everything here is a label, a hairline and a
 * rounded rectangle, all of which are ten lines each, and pulling in a full Material
 * dependency for them would add a version pin and a theme system this game does not want.
 * The window is a tool, not a website.
 */
internal object AppColors {
    /** Window background, the darkest layer. */
    val background = Color(0xFF0E1015)

    /** Cards, the side bar, the result panel. */
    val surface = Color(0xFF14171E)

    /** The code editor's well, a shade darker than [surface] so it reads as inset. */
    val editor = Color(0xFF11141A)

    /** Primary body text. */
    val foreground = Color(0xFFD7DCE5)

    /** Secondary text: labels, hints, things the player can afford to skim. */
    val muted = Color(0xFF7C879B)

    /** Faint text: line numbers. */
    val faint = Color(0xFF4A5261)

    /** Hairline borders. */
    val divider = Color(0xFF2A2F3A)

    /** The single interactive accent: focus, selection, the Run button. */
    val accent = Color(0xFF6EA8FE)

    /** Anything that failed. */
    val error = Color(0xFFFF6B6B)

    /** A level was solved. */
    val success = Color(0xFF66BB6A)

    /** Earned stars. */
    val star = Color(0xFFFFCA28)

    /** Two or three voxels off. */
    val near = Color(0xFFFFB300)

    /** Wrong colour, in the diff and in the viewport. */
    val wrong = Color(0xFFFF5252)

    /** A voxel that should not be there. */
    val extra = Color(0xFFFFB300)
}

/**
 * The four text roles.
 *
 * Four, not fifteen. The UI is a level title, some captions, a code block and a message;
 * inventing a role for each one is how a design system ends up with a style nobody uses.
 */
internal object AppText {
    /** Level titles and panel headings. */
    val title = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)

    /** Panel body. */
    val body = TextStyle(fontSize = 13.sp, lineHeight = 19.sp)

    /** Labels and metadata. */
    val caption = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, color = AppColors.muted)

    /** The editor field and diagnostic snippets. */
    val code = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )

    /** Gutter numbers, one step smaller than the code they sit beside. */
    val gutter = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 20.sp,
        color = AppColors.faint,
    )
}

/**
 * A plain label. [BasicText] rather than Material's `Text`: see [AppColors].
 *
 * [color] and [fontWeight] default to `null` meaning "keep whatever [style] says", so a
 * caption's muted colour survives a call site that does not care about colour at all.
 */
@Composable
internal fun Label(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = AppText.body,
    color: Color? = null,
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val override = if (color == null && fontWeight == null) {
        null
    } else {
        TextStyle(color = color ?: Color.Unspecified, fontWeight = fontWeight)
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = override?.let(style::merge) ?: style,
        maxLines = maxLines,
        overflow = overflow,
    )
}
