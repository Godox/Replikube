package fr.godox.replikube.app.progress

/**
 * Where a progress save lives, as text.
 *
 * ### Why this is an interface and not `expect fun`
 *
 * The thing that differs between a desktop app and a page is not the *encoding* of a save --
 * that is JSON either way -- but the storage, and the two are so unlike each other that
 * modelling them as one function with a platform body would be mostly platform code:
 *
 * - The JVM writes `~/.local/share/replikube/progress.json` through a temp file and a move,
 *   because a crash mid-write must not leave truncated JSON where a valid save used to be.
 *   It can also *rename* a corrupt file aside, which is a filesystem-only courtesy.
 * - A browser has `localStorage`, whose writes are atomic by construction and where the
 *   quota is a fixed few megabytes rather than a disk that happens to be full. Renaming
 *   aside means writing a second key.
 *
 * `expect fun read(): String?` / `expect fun write(text: String): Boolean` would force the
 * atomic-move dance and the corrupt-file rename into the common half, where neither can
 * happen. This way the common half owns the *policy* -- JSON, forgiving reads -- and each
 * platform owns only the two operations that genuinely differ.
 *
 * Nothing else in the app knows which one it has.
 */
interface ProgressStorage {

    /**
     * The stored save, or `null` if there is nothing stored.
     *
     * Returning `null` for "absent" and `null` for "unreadable" is deliberately the same
     * thing: see [ProgressStore.load], which treats both as empty progress. A store that
     * could not tell them apart would only push that decision up here, where it cannot be
     * tested.
     */
    fun read(): String?

    /**
     * Writes [text] as the save, returning whether it landed.
     *
     * `false` rather than throwing, for the same reason [ProgressStore.save] does: a
     * progress file is not worth interrupting play over, and the UI can warn about it.
     */
    fun write(text: String): Boolean

    /**
     * Sets the save aside, so the next [read] finds nothing.
     *
     * Used when a save turns out to be unparseable, so the player's data is not silently
     * overwritten by the empty progress that replaces it. Returning `false` is fine -- the
     * data is being discarded either way, and failing to keep a copy is not worth a second
     * warning.
     */
    fun quarantine(): Boolean
}

/** A storage that remembers nothing. For tests, and as a fallback when a platform has neither. */
object NoProgressStorage : ProgressStorage {
    override fun read(): String? = null
    override fun write(text: String): Boolean = true
    override fun quarantine(): Boolean = true
}