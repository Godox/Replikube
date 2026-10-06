package fr.godox.replikube.app.progress

import fr.godox.replikube.core.LevelProgress
import fr.godox.replikube.core.Progress
import fr.godox.replikube.core.Stars
import kotlinx.serialization.json.Json

/**
 * Reads and writes the player's progress.
 *
 * The store is deliberately forgiving: a corrupt or unreadable save resets to empty progress
 * rather than refusing to start. Losing stars is an annoyance; a game that will not launch
 * because of a bad JSON file is a bug with no upside.
 *
 * ### Why the durability lives in [ProgressStorage]
 *
 * This class used to take a `java.nio.file.Path` and do the safe-write dance itself: write a
 * temp file, then move it over the original. That is the right way to survive a crash on a
 * filesystem, and it is meaningless in a browser, where `localStorage.setItem` is already
 * atomic and there is no rename.
 *
 * So the two operations that differ are behind [ProgressStorage] and the JSON policy stays
 * here. `ProgressStoreTest` used to assert the temp-file behaviour directly; it now asserts
 * it through a recording storage, which tests the *contract* rather than one implementation
 * of it. The JVM's implementation of that contract is `FileProgressStorage`, and it is what
 * actually protects a packaged build.
 */
class ProgressStore(private val storage: ProgressStorage) {

    private val json = Json {
        prettyPrint = true
        // A save file written by a newer build should not stop an older one from starting.
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Reads progress, returning empty progress if there is nothing usable to read. */
    fun load(): Progress {
        val text = storage.read() ?: return Progress()
        return runCatching {
            json.decodeFromString(Progress.serializer(), text)
        }.getOrElse {
            // Keep the bad text for the curious rather than deleting a player's data. If the
            // copy cannot be kept -- a read-only disk, a full quota -- the save is replaced
            // anyway: refusing to start would be a worse outcome than losing a file that was
            // already unreadable.
            storage.quarantine()
            Progress()
        }
    }

    /**
     * Writes [progress], reporting whether it landed.
     *
     * Returns `false` rather than throwing: progress is not worth interrupting play for, and
     * the caller surfaces a warning instead.
     */
    fun save(progress: Progress): Boolean =
        runCatching { storage.write(json.encodeToString(progress)) }.getOrDefault(false)

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
}