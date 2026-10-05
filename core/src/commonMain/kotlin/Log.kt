package fr.godox.replikube.core.log

/**
 * A log that works when the app has no console.
 *
 * ### Why this exists
 *
 * replikube ships as a desktop app *and* as a page in a browser. When something goes wrong
 * the player sees a window, and the only evidence available is whatever the app chose to
 * write down. The hardest class of bug to report is the one that only reproduces in a
 * *packaged* build -- an AppImage mounts read-only over FUSE, a wasm module 404s on one of
 * its fetches, and a resource that resolves from a Gradle build directory may not resolve
 * from a jar. None of that is visible from the UI.
 *
 * So the log is always on, goes somewhere a player can be told about, and mirrors to
 * stderr when there is one. `REPLIKUBE_LOG=/path` overrides the location on the JVM.
 *
 * ### Why it is this small
 *
 * No framework, no configuration, no levels. It has one job: be present, be cheap enough
 * to leave on in a shipped build, and never be the reason the app fails to start. Every
 * failure path below degrades to writing less, never to throwing -- a logger that can
 * crash its own application is worse than no logger.
 *
 * ### Why the sink is injected
 *
 * The destinations are genuinely different per platform -- a file on the JVM, the browser
 * console on wasm -- but the formatting and the structured helpers are not, and splitting
 * them would mean writing the interesting half twice. `LogSink` moves only the part that
 * cannot be shared. See the per-platform `LogSink` for where each one actually writes.
 */
object Log {

    /**
     * Where lines go. Settable, because a test needs to capture them and a browser needs
     * to point them at the console.
     *
     * Defaults to whatever the platform's default sink is, resolved lazily so that a test
     * which installs a sink before the first `Log.i` is not overwritten by a later
     * initialisation.
     */
    var sink: LogSink = defaultSink()
        set(value) {
            field = value
        }

    private fun defaultSink(): LogSink = platformDefaultLogSink()

    /**
     * The log's own location, or null when there is none.
     *
     * Shown to the player on the failure screen, because "the log is at ..." is only useful
     * if the location is actually communicated -- and a browser console has no path, so
     * the UI has to cope with null rather than printing an empty string.
     */
    fun location(): String? = runCatching { sink.location() }.getOrNull()

    // -- the three levels -------------------------------------------------------------

    /** Something happened that is worth recording and is expected. */
    fun i(tag: String, message: String) = write("INFO ", tag, message)

    /** Something unexpected that was recovered from. */
    fun w(tag: String, message: String, error: Throwable? = null) = write("WARN ", tag, message, error)

    /** Something that stopped the thing the player asked for. */
    fun e(tag: String, message: String, error: Throwable? = null) = write("ERROR", tag, message, error)

    /** Records a stack trace as a single multi-line block. */
    fun trace(tag: String, error: Throwable) = write("ERROR", tag, error.stackTraceToString())

    // -- structured helpers for the two questions this log exists to answer -------------

    /**
     * Records how a path resolved, and whether it actually exists.
     *
     * The single most useful line in a packaged-build bug report. A path that *looks*
     * plausible but is a `jar:file:` URL, a FUSE mount that has since been unmounted, or a
     * directory that does not exist is indistinguishable from a working one until you check.
     *
     * On the JVM the sink can answer "does this exist", which is why the existence check
     * lives here rather than in the shared formatting: a browser has no meaningful
     * equivalent, and claiming a path is fine when it cannot be checked would be exactly
     * the crying wolf that `core/AGENT.md` warns against.
     */
    fun path(label: String, value: Any?) {
        i("path", "$label = ${value?.toString() ?: "<null>"}${describePath(value)}")
    }

    /** Records a whole group of named paths, one line each. */
    fun paths(label: String, values: Map<String, Any?>) {
        i("path", "--- $label ---")
        values.forEach { (name, value) -> path(name, value) }
    }

    /**
     * Dumps what the packaging changed.
     *
     * `APPIMAGE` and `APPDIR` only exist inside a packaged JVM build, and a wasm build
     * needs the origin and user agent instead -- between them those answer most "works in
     * development, fails when packaged" questions on the first line of the log.
     */
    fun environment() {
        i("env", "--- runtime environment ---")
        platformEnvironment().forEach { (name, value) -> i("env", "$name=$value") }
        i("env", "log location=${location() ?: "<no file: console only>"}")
    }

    // -- the writer --------------------------------------------------------------------

    /**
     * Formats one line and hands it to the sink.
     *
     * The timestamp comes from the platform helper rather than from a clock in common
     * code, so the JVM can keep its `java.time`-derived format and the browser its UTC one
     * without either having to be wrong about the other's environment. The format itself
     * is asserted to be the same width on both -- see [platformTimestamp].
     */
    private fun write(level: String, tag: String, message: String, error: Throwable? = null) {
        val line = buildString {
            append(platformTimestamp())
            append(' ').append(level).append(' ').append('[').append(tag).append("] ")
            append(message)
            if (error != null) append(' ').append(quote(error))
        }
        // Guarded even though the sink contract says it never throws: a bug in one sink
        // must not take down the game that is trying to report that bug.
        runCatching { sink.write(line) }
    }

    /** A throwable on one line, with its causes -- long, but greppable. */
    private fun quote(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) append(" <- ")
            append(current::class.simpleName).append(": ").append(current.message)
            current = current.cause.takeIf { it !== current }
            depth++
        }
    }

    /** Formats a path's verification state. See [path] for why this is platform-specific. */
    private fun describePath(value: Any?): String = describePlatformPath(value)
}