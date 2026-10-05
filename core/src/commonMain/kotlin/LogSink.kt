package fr.godox.replikube.core.log

/**
 * Where a [Log] line goes.
 *
 * The log itself is platform-neutral; only its destinations are not. A JVM process can
 * append to a file on disk, and a browser cannot -- there is no filesystem and no
 * blocking I/O, and anything that blocks the single thread the game runs on freezes the
 * page rather than failing.
 *
 * ### Why this is not `expect fun`
 *
 * It could be, but an interface buys three things here that `expect`/`actual` does not:
 * the JVM implementation keeps the whole existing `Log` file untouched, the wasm one can
 * be swapped in a test, and a caller can install its own sink (the render tests already
 * want that) without a compiler flag.
 *
 * Every method returns [Boolean] -- "did this line get written". The rule from
 * `core/AGENT.md` is that a logging failure must never become the failure being
 * diagnosed, so a sink that cannot write says so and is ignored, rather than throwing
 * from inside the logger.
 */
interface LogSink {
    /** A formatted line, already timestamped and levelled. */
    fun write(line: String): Boolean

    /**
     * Absolute path or location a human can be shown, for the failure screen.
     *
     * Null when there is no such place -- a browser console has no file to point at, and
     * the UI must not offer a path that does not exist.
     */
    fun location(): String?
}