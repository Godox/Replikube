package fr.godox.replikube.app.progress

import fr.godox.replikube.core.LevelProgress
import fr.godox.replikube.core.Progress
import fr.godox.replikube.core.Stars
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Reads and writes `progress.json`.
 *
 * The store is deliberately forgiving: a corrupt or unreadable save file resets to empty
 * progress rather than refusing to start. Losing stars is an annoyance; a game that will
 * not launch because of a bad JSON file is a bug with no upside.
 *
 * Writes go through a temp file and an atomic move, so a crash mid-write cannot leave a
 * half-written save behind — the next launch would otherwise read truncated JSON and, by
 * the rule above, silently wipe the player's progress.
 */
class ProgressStore(private val file: Path) {

    constructor() : this(defaultPath())

    private val json = Json {
        prettyPrint = true
        // A save file written by a newer build should not stop an older one from starting.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Reads progress, returning empty progress if there is nothing usable to read. */
    fun load(): Progress {
        if (!file.exists()) return Progress()
        return runCatching {
            json.decodeFromString(Progress.serializer(), file.readText())
        }.getOrElse {
            // Keep the bad file for the curious rather than deleting a player's data.
            runCatching { Files.move(file, file.resolveSibling("${file.name}.corrupt")) }
            Progress()
        }
    }

    /**
     * Writes [progress], reporting whether it landed.
     *
     * Returns `false` rather than throwing: progress is not worth interrupting play for,
     * and the caller can surface a warning.
     */
    fun save(progress: Progress): Boolean = runCatching {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.name}.tmp")
        Files.writeString(tmp, json.encodeToString(progress))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    /** Records one attempt, keeping the player's last working solution. */
    fun recordAttempt(progress: Progress, levelId: String): Progress =
        progress.withLevel(levelId) { it.copy(attempts = it.attempts + 1) }

    /**
     * Records a solve and awards stars, never downgrading a previous best.
     *
     * The stored code is replaced unconditionally: it is the player's *last working*
     * solution, and the editor reopens on whatever they actually wrote last, not on the
     * shortest thing that ever passed.
     */
    fun recordSolve(progress: Progress, levelId: String, stars: Stars, solution: String): Progress =
        progress.withLevel(levelId) {
            it.copy(
                solved = true,
                stars = maxOf(it.stars, stars),
                lastSolution = solution,
            )
        }

    private fun Progress.withLevel(levelId: String, update: (LevelProgress) -> LevelProgress): Progress =
        with(this[levelId].let(update))

    companion object {
        /**
         * `~/.local/share/replikube/progress.json`, following the XDG base directory spec.
         *
         * `XDG_DATA_HOME` is honoured when set, which is what makes it easy to throw away a
         * save while testing without touching the real one.
         */
        fun defaultPath(): Path {
            val xdg = System.getenv("XDG_DATA_HOME")
            val base = if (xdg.isNullOrBlank()) {
                val home = System.getProperty("user.home") ?: "."
                File(home, ".local/share")
            } else {
                File(xdg)
            }
            return base.toPath().resolve("replikube").resolve("progress.json")
        }
    }
}