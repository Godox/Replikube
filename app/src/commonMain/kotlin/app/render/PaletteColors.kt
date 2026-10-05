package fr.godox.replikube.app.render

import androidx.compose.ui.graphics.Color
import fr.godox.replikube.dsl.Palette

/**
 * The game's colours as Compose [Color]s.
 *
 * Lives in `:app` rather than `:dsl`: palette *indices* are part of the player-facing
 * contract and must never move, but the exact RGB a player sees is a presentation choice
 * that a theme is free to change. Keeping the numbers here means `:core` and `:dsl` stay
 * free of any UI dependency.
 */
object PaletteColors {

    /** Base hue per palette id, indexed by id so lookup is a single array read. */
    private val base = arrayOf(
        Color(0x000000), // EMPTY — never drawn as a face, see [isRenderable]
        Color(0xFFFFFFFF), // WHITE
        Color(0xFFC8C8C8), // LIGHT_GRAY
        Color(0xFF8C8C8C), // GRAY
        Color(0xFF585858), // DARK_GRAY
        Color(0xFF1C1C1C), // BLACK
        Color(0xFFE53935), // RED
        Color(0xFFF57C00), // ORANGE
        Color(0xFFFDD835), // YELLOW
        Color(0xFFAFB42B), // LIME
        Color(0xFF43A047), // GREEN
        Color(0xFF00ACC1), // CYAN
        Color(0xFF1E88E5), // BLUE
        Color(0xFF3949AB), // INDIGO
        Color(0xFF8E24AA), // PURPLE
        Color(0xFFD81B60), // MAGENTA
        Color(0xFF6D4C41), // BROWN
    )

    /** The unlit colour for a palette id. */
    fun of(color: Int): Color = base.getOrElse(color) { Color(0xFFFF00FF) }

    /**
     * How much a face darkens by, per face.
     *
     * A single directional light gives the eye enough to read which way is up without
     * needing real lighting. Top is brightest, then the two sides; the darkest face is
     * still legible, because a face that goes black hides the shape's silhouette.
     */
    fun shade(factor: Float): Color = Color(factor, factor, factor)

    const val TOP_LIGHT = 1.0f
    const val SIDE_LIGHT = 0.78f
    const val SIDE_LIGHT_BACK = 0.62f

    /** Whether a palette id draws a cube at all. */
    fun isRenderable(color: Int): Boolean = color != Palette.EMPTY
}