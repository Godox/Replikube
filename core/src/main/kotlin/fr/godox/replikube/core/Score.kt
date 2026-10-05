package fr.godox.replikube.core

import kotlinx.serialization.Serializable

/**
 * Star rating for a solved level.
 *
 * Deliberately not a punishment system: three stars for a concise solution, one for
 * getting there at all. Attempts are tracked separately and never subtract stars.
 */
@Serializable
enum class Stars(val count: Int) {
    NONE(0),
    BRONZE(1),
    SILVER(2),
    GOLD(3),
    ;

    companion object {
        /**
         * Rate a solved level from the player's line count against the level's [par].
         *
         * Three stars at or under par, two within twice par, one for anything longer.
         */
        fun forLines(lines: Int, par: Int): Stars = when {
            lines <= par -> GOLD
            lines <= par * 2 -> SILVER
            else -> BRONZE
        }
    }
}

/** Per-level progress, persisted locally. */
@Serializable
data class LevelProgress(
    val levelId: String,
    val solved: Boolean = false,
    val stars: Stars = Stars.NONE,
    val attempts: Int = 0,
    /** The player's last working solution, so they can pick up where they left off. */
    val lastSolution: String? = null,
)

/** The whole save file. */
@Serializable
data class Progress(
    val levels: Map<String, LevelProgress> = emptyMap(),
) {
    /** Progress for [levelId], never null. */
    operator fun get(levelId: String): LevelProgress = levels[levelId] ?: LevelProgress(levelId)

    fun with(levelProgress: LevelProgress): Progress = copy(levels = levels + (levelProgress.levelId to levelProgress))

    /** Total stars earned across all levels. */
    val totalStars: Int get() = levels.values.sumOf { it.stars.count }

    /** Ids of solved levels. */
    val solvedIds: Set<String> get() = levels.values.filter { it.solved }.map { it.levelId }.toSet()

    companion object {
        val EMPTY = Progress()
    }
}
