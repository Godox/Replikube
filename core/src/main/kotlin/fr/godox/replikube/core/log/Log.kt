package fr.godox.replikube.core.log

import java.io.File
import java.io.PrintWriter
import java.net.JarURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A log that works when the app has no console.
 *
 * ### Why this exists
 *
 * replikube is a desktop game, not a server. When something goes wrong the player sees a
 * window, and the only evidence available is whatever the app chose to write down. The
 * hardest class of bug to report is the one that only reproduces in a *packaged* build —
 * an AppImage mounts read-only over FUSE, `java.io.tmpdir` may be somewhere unexpected, and
 * a resource that resolves from a Gradle build directory may not resolve from a jar. None of
 * that is visible from the UI, and none of it is visible from a terminal the player does not
 * have.
 *
 * So the log is always on, goes to a file in a location a player can be told about, and
 * mirrors to stderr when there is one. `REPLIKUBE_LOG=/path` overrides the location.
 *
 * ### Why it is this small
 *
 * No framework, no configuration, no levels. It has one job: be present, be cheap enough to
 * leave on in a shipped build, and never be the reason the app fails to start. Every
 * failure path below degrades to writing less, never to throwing — a logger that can crash
 * its own application is worse than no logger.
 */
object Log {

    private const val MAX_BYTES = 512L * 1024L
    private const val NAME = "replikube.log"

    private val timestamp = DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS")
        .withZone(ZoneId.systemDefault())

    /**
     * The log file, or null when none could be opened.
     *
     * Shown to the player on the failure screen, because "the log is at …" is only useful
     * if the path is actually communicated.
     */
    val file: File? = openLogFile()

    private val writer: PrintWriter? = file?.let { path ->
        runCatching { PrintWriter(path.bufferedWriter(), /* append = */ true) }.getOrNull()
    }

    // -- the three levels -------------------------------------------------------------

    /** Something happened that is worth recording and is expected. */
    fun i(tag: String, message: String) = write("INFO ", tag, message)

    /** Something unexpected that was recovered from. */
    fun w(tag: String, message: String, error: Throwable? = null) = write("WARN ", tag, message, error)

    /** Something that stopped the thing the player asked for. */
    fun e(tag: String, message: String, error: Throwable? = null) = write("ERROR", tag, message, error)

    // -- structured helpers for the two questions this log exists to answer -------------

    /**
     * Records how a path resolved, and whether it actually exists on disk.
     *
     * The single most useful line in a packaged-build bug report. A path that *looks*
     * plausible but is a `jar:file:` URL, a FUSE mount that has since been unmounted, or a
     * directory that does not exist is indistinguishable from a working one until you check.
     *
     * The value is usually a [URL] rather than a [File], and the distinction matters: a
     * `file:/x/y.jar` URL string handed straight to [File] becomes a *relative* path called
     * `file:/x/y.jar`, which of course does not exist. An earlier version of this function
     * did exactly that and reported every jar in the app image as [MISSING] — a log that
     * cries wolf is worse than no log, so [asFile] converts properly.
     */
    fun path(label: String, value: Any?) {
        val text = value?.toString() ?: "<null>"
        val asFile = asFile(value)
        val state = when {
            value == null -> "null"
            asFile == null -> "not a filesystem path (${(value as? URL)?.protocol ?: "?"} URL)"
            asFile.exists() -> "exists"
            else -> "MISSING"
        }
        i("path", "$label = $text [$state]")
    }

    /**
     * The file [value] refers to, or null if it is not a path at all.
     *
     * Goes through the URL's own converter rather than string surgery, because an AppImage
     * mount path can contain characters that need percent-decoding, and because a `jar:`
     * URL names the jar *and* an entry inside it — only the connection knows which file to
     * go and look at.
     */
    private fun asFile(value: Any?): File? = when (value) {
        null -> null
        is File -> value
        is URL -> when (value.protocol) {
            "file" -> runCatching { File(value.toURI()) }.getOrNull()
            "jar" -> runCatching {
                File((value.openConnection() as JarURLConnection).jarFileURL.toURI())
            }.getOrNull()
            else -> null
        }

        else -> runCatching { File(value.toString()) }.getOrNull()
    }

    /** Records a whole group of named paths, one line each. */
    fun paths(label: String, values: Map<String, Any?>) {
        i("path", "--- $label ---")
        values.forEach { (name, value) -> path(name, value) }
    }

    /**
     * Dumps the process environment that packaging changes.
     *
     * `APPIMAGE` and `APPDIR` only exist inside a packaged build, and `java.io.tmpdir` is
     * what the compiler writes into — so between them these three answer most "works in
     * development, fails when packaged" questions on the first line of the log.
     */
    fun environment() {
        i("env", "--- runtime environment ---")
        listOf("APPIMAGE", "APPDIR", "TMPDIR", "HOME", "XDG_STATE_HOME", "JAVA_HOME", "LANG")
            .forEach { i("env", "$it=${System.getenv(it) ?: "<unset>"}") }
        path("java.io.tmpdir", System.getProperty("java.io.tmpdir"))
        path("user.dir", System.getProperty("user.dir"))
        i("env", "java.version=${System.getProperty("java.version")}")
        i("env", "java.home=${System.getProperty("java.home")}")
        i("env", "os.name=${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        i("env", "log file=${file?.absolutePath ?: "<unavailable>"}")
    }

    /** Records a stack trace as a single multi-line block. */
    fun trace(tag: String, error: Throwable) = write("ERROR", tag, error.stackTraceToString())

    // -- the writer --------------------------------------------------------------------

    private fun write(level: String, tag: String, message: String, error: Throwable? = null) {
        val at = timestamp.format(Instant.now())
        val line = buildString {
            append(at).append(' ').append(level).append(' ').append('[').append(tag).append("] ")
            append(message)
            if (error != null) append(' ').append(quote(error))
        }
        // Both sinks, each in its own guard: a full disk should cost the file, not the
        // stderr mirror, and a closed stream should cost neither the app nor the log.
        runCatching { writer?.println(line) }
        runCatching { System.err.println(line) }
        runCatching { writer?.flush() }
    }

    /** A throwable on one line, with its causes — long, but greppable. */
    private fun quote(error: Throwable): String = buildString {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) append(" <- ")
            append(current::class.java.name).append(": ").append(current.message)
            current = current.cause.takeIf { it !== current }
            depth++
        }
    }

    // -- file selection ----------------------------------------------------------------

    /**
     * Picks a log file, trying the places a packaged Linux app can actually write.
     *
     * In preference order: an explicit override, then the XDG state directory, then the
     * temp directory. The temp directory is last because it is the one that can vanish
     * between sessions, but it is also the one guaranteed to exist and be writable inside
     * a read-only AppImage mount — so it beats failing to log at all.
     */
    private fun openLogFile(): File? {
        val override = System.getenv("REPLIKUBE_LOG")
        val candidates = buildList {
            override?.let { add(File(it)) }
            val stateHome = System.getenv("XDG_STATE_HOME")
                ?: System.getProperty("user.home")?.let { "$it/.local/state" }
            stateHome?.let { add(File(it, "replikube/$NAME")) }
            add(File(System.getProperty("java.io.tmpdir"), "replikube/$NAME"))
        }
        // `firstOrNull` needs a predicate, so the opened file is tested for null rather than
        // returned — the value is fetched again below.
        return candidates.firstOrNull { it.open() != null }
    }

    /**
     * Opens [this] for append, creating parents and discarding a log that has grown too big.
     *
     * Truncating rather than rotating: a single unbounded file in a state directory is a
     * nuisance, and the alternative (keeping rotated logs around) needs a retention policy
     * for a log nobody reads often. Truncating on startup also means each run starts with
     * one run's worth of evidence, which is what a bug report actually wants.
     */
    private fun File.open(): File? = runCatching {
        parentFile?.mkdirs()
        if (exists() && length() > MAX_BYTES) delete()
        if (createNewFile() || exists()) this else null
    }.getOrNull()
}
