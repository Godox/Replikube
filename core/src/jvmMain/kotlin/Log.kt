package fr.godox.replikube.core.log

/**
 * JVM half of the logging seam.
 *
 * `platformDefaultLogSink` opens the same file the old `Log` object opened, in the same
 * order of preference, so a player's existing log keeps working and the truncation and
 * "never throws" behaviour are unchanged. Everything about *where* is here; everything
 * about *what a line looks like* stays in the common `Log`.
 */

/** Absolute filesystem path, or null when the value is not a path at all. */
private fun asFile(value: Any?): java.io.File? = when (value) {
    null -> null
    is java.io.File -> value
    // A `file:/x/y.jar` URL handed straight to File becomes a *relative* path called
    // "file:/x/y.jar", which of course does not exist -- the original reason this file
    // exists. `toURI()` is what decodes it, and a percent-encoded AppImage mount path
    // needs it too.
    is java.net.URL -> when (value.protocol) {
        "file" -> runCatching { java.io.File(value.toURI()) }.getOrNull()
        // A `jar:` URL names the jar *and* an entry inside it; only the connection knows
        // which file to go and look at.
        "jar" -> runCatching {
            java.io.File((value.openConnection() as java.net.JarURLConnection).jarFileURL.toURI())
        }.getOrNull()

        else -> null
    }

    else -> runCatching { java.io.File(value.toString()) }.getOrNull()
}

/**
 * Appends to a file and mirrors to stderr.
 *
 * Two sinks because the interesting case is a user launching a packaged binary, where
 * nobody is reading stdout. Each is guarded independently in [write]: a full disk should
 * cost the file, not the stderr mirror.
 */
private class FileAndStderrSink(private val file: java.io.File) : LogSink {

    private val writer = runCatching { java.io.PrintWriter(file.bufferedWriter(), true) }.getOrNull()

    override fun write(line: String): Boolean {
        var wrote = false
        runCatching { writer?.println(line) }.onSuccess { wrote = wrote || (writer != null) }
        runCatching { System.err.println(line) }
        runCatching { writer?.flush() }
        return wrote
    }

    override fun location(): String = file.absolutePath
}

/**
 * The sink `:core` uses unless something replaces it.
 *
 * Preference order: an explicit `REPLIKUBE_LOG`, then the XDG state directory, then the
 * temp directory. The temp directory is last because it is the one that can vanish between
 * sessions, but it is also the only one guaranteed to exist and be writable inside a
 * read-only AppImage mount -- so it beats failing to log at all.
 */
internal actual fun platformDefaultLogSink(): LogSink = FileAndStderrSink(
    runCatching { openLogFile() }.getOrNull() ?: fallbackSink(),
)

/**
 * A sink that only mirrors to stderr, used when no log file could be opened at all.
 *
 * Having one of these rather than a null sink is deliberate: the log's first job is to be
 * present, and `System.err` is the one destination that still works when the disk is full
 * or the mount is read-only.
 */
private fun fallbackSink(): java.io.File =
    java.io.File(System.getProperty("java.io.tmpdir") ?: ".", "replikube/replikube.log")
        .also { runCatching { it.parentFile?.mkdirs(); it.createNewFile() } }

private fun openLogFile(): java.io.File? {
    val override = System.getenv("REPLIKUBE_LOG")
    val candidates = buildList {
        override?.let { add(java.io.File(it)) }
        val stateHome = System.getenv("XDG_STATE_HOME")
            ?: System.getProperty("user.home")?.let { "$it/.local/state" }
        stateHome?.let { add(java.io.File(it, "replikube/replikube.log")) }
        add(fallbackSink())
    }
    return candidates.firstOrNull { it.open() != null }
}

/**
 * Opens this file for append, creating parents and discarding a log that grew too big.
 *
 * Truncating rather than rotating: a single unbounded file in a state directory is a
 * nuisance, and keeping rotated logs needs a retention policy for a log nobody reads often.
 * Truncating on startup also means each run starts with one run's worth of evidence, which
 * is what a bug report actually wants.
 */
private fun java.io.File.open(): java.io.File? = runCatching {
    parentFile?.mkdirs()
    if (exists() && length() > MAX_BYTES) delete()
    if (createNewFile() || exists()) this else null
}.getOrNull()

/** Truncation limit, matching the pre-KMP behaviour. */
private const val MAX_BYTES = 512L * 1024L

/**
 * `[exists]`, `[MISSING]`, `[null]`, or `[not a filesystem path (jar URL)]`.
 *
 * A log that cries wolf is worse than no log: this exists because the previous version
 * reported every jar in the app image as MISSING while sitting on disk.
 */
internal actual fun describePlatformPath(value: Any?): String {
    val file = asFile(value)
    return when {
        value == null -> " [null]"
        file == null -> " [not a filesystem path (${(value as? java.net.URL)?.protocol ?: "?"} URL)]"
        file.exists() -> " [exists]"
        else -> " [MISSING]"
    }
}

/**
 * `HH:mm:ss.SSS` in the system zone -- byte-for-byte what the pre-KMP logger produced.
 *
 * `java.time` rather than `kotlinx-datetime`: this is the JVM source set, and the whole
 * point of the shared formatter was to avoid *this* function existing twice. Reaching for
 * the multiplatform datetime library here would mean converting between `kotlin.time`
 * and `kotlinx.datetime` instants to produce a string `java.time` formats directly, which
 * is more moving parts than the duplication it avoids.
 */
internal actual fun platformTimestamp(): String =
    java.time.format.DateTimeFormatter
        .ofPattern("HH:mm:ss.SSS")
        .withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.now())

/**
 * The process environment that packaging changes.
 *
 * `java.io.tmpdir` is what the compiler writes into, and `java.home` names the bundled
 * runtime -- a player code compiled to a newer class file version than this reports as an
 * install problem, so the log needs to be able to show both.
 */
internal actual fun platformEnvironment(): Map<String, String> = buildMap {
    listOf("APPIMAGE", "APPDIR", "TMPDIR", "HOME", "XDG_STATE_HOME", "JAVA_HOME", "LANG")
        .forEach { put(it, System.getenv(it) ?: "<unset>") }
    put("java.io.tmpdir", System.getProperty("java.io.tmpdir") ?: "<unset>")
    put("user.dir", System.getProperty("user.dir") ?: "<unset>")
    put("java.version", System.getProperty("java.version") ?: "<unset>")
    put("java.home", System.getProperty("java.home") ?: "<unset>")
    put("os", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
}