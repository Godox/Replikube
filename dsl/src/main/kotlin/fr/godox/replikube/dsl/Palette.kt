package fr.godox.replikube.dsl

/**
 * The palette.
 *
 * These are the *only* ways to name a colour in player code, and they are plain `Int`
 * constants so `when` branches read naturally:
 *
 * ```kotlin
 * fun block(x: Int, y: Int, z: Int): Int = when {
 *     y == 1 -> RED
 *     else -> GREEN
 * }
 * ```
 *
 * Indices are stable forever: levels and shared solutions depend on them. `EMPTY` (0)
 * means no voxel. See [Palette.nameOf] for the reverse lookup.
 */
object Palette {
    /** No voxel here. */
    const val EMPTY = 0

    const val WHITE = 1
    const val LIGHT_GRAY = 2
    const val GRAY = 3
    const val DARK_GRAY = 4
    const val BLACK = 5
    const val RED = 6
    const val ORANGE = 7
    const val YELLOW = 8
    const val LIME = 9
    const val GREEN = 10
    const val CYAN = 11
    const val BLUE = 12
    const val INDIGO = 13
    const val PURPLE = 14
    const val MAGENTA = 15
    const val BROWN = 16

    /** Highest valid colour id. */
    const val MAX = 16

    private val names = listOf(
        "EMPTY",
        "WHITE",
        "LIGHT_GRAY",
        "GRAY",
        "DARK_GRAY",
        "BLACK",
        "RED",
        "ORANGE",
        "YELLOW",
        "LIME",
        "GREEN",
        "CYAN",
        "BLUE",
        "INDIGO",
        "PURPLE",
        "MAGENTA",
        "BROWN",
    )

    /** Human-readable name of [id], for error messages and UI. */
    fun nameOf(id: Int): String = names.getOrElse(id) { "UNKNOWN($id)" }

    /** Whether [id] is a colour a solution may return. */
    fun isValid(id: Int): Boolean = id in EMPTY..MAX
}

// Top-level aliases so solutions can write RED instead of Palette.RED. Redeclaring them
// at file scope (not inside the object) is what makes them visible to player code.

/** No voxel here. @see Palette.EMPTY */
const val EMPTY = Palette.EMPTY
/** @see Palette.WHITE */
const val WHITE = Palette.WHITE
/** @see Palette.LIGHT_GRAY */
const val LIGHT_GRAY = Palette.LIGHT_GRAY
/** @see Palette.GRAY */
const val GRAY = Palette.GRAY
/** @see Palette.DARK_GRAY */
const val DARK_GRAY = Palette.DARK_GRAY
/** @see Palette.BLACK */
const val BLACK = Palette.BLACK
/** @see Palette.RED */
const val RED = Palette.RED
/** @see Palette.ORANGE */
const val ORANGE = Palette.ORANGE
/** @see Palette.YELLOW */
const val YELLOW = Palette.YELLOW
/** @see Palette.LIME */
const val LIME = Palette.LIME
/** @see Palette.GREEN */
const val GREEN = Palette.GREEN
/** @see Palette.CYAN */
const val CYAN = Palette.CYAN
/** @see Palette.BLUE */
const val BLUE = Palette.BLUE
/** @see Palette.INDIGO */
const val INDIGO = Palette.INDIGO
/** @see Palette.PURPLE */
const val PURPLE = Palette.PURPLE
/** @see Palette.MAGENTA */
const val MAGENTA = Palette.MAGENTA
/** @see Palette.BROWN */
const val BROWN = Palette.BROWN
