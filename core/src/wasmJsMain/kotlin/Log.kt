package fr.godox.replikube.core.log

import kotlin.time.Clock
import kotlin.js.ExperimentalWasmJsInterop

/**
 * wasm half of the logging seam.
 *
 * There is no file and no blocking I/O, so this writes to `console.*`. The choice of
 * `console.log` for ordinary lines is deliberate: a wasm module's output is forwarded by
 * browser devtools to the *info* level, which some console filter presets hide, so a log
 * that "works" and shows nothing is worse than one that shows too much. Failures go to
 * `console.error` so they survive a console set to errors-and-warnings only.
 *
 * ### Why there is no buffered sink
 *
 * The JVM sink writes to a file, which implies flushing on a schedule. Nothing here needs
 * that: the console holds the text, and an in-memory buffer would only add a way to lose the
 * last few lines, which are usually the ones being debugged.
 *
 * ### Why `@JsFun` rather than `js("...")`
 *
 * Kotlin/Wasm rejects a `js()` body that is more than a single expression, and has no
 * `dynamic` type, so an inline `js("console.log(x)")` cannot be wrapped in a null check and
 * cannot be used inside a `buildMap` block. External declarations give both: a real
 * signature the compiler type-checks, and nullability handled on the Kotlin side.
 */
/** Writes to the browser console, routing by the level in the formatted line. */
private object ConsoleSink : LogSink {

    override fun write(line: String): Boolean {
        // The line already carries "LEVEL" as its second field, so routing on it keeps the
        // level decision in one place instead of threading it through `Log.write`.
        val console = jsConsole()
        if (line.contains(" ERROR ")) console.error(line) else console.log(line)
        return true
    }

    /**
     * Null, not a fake path.
     *
     * `core/AGENT.md` treats a log that reports a location it cannot back as a bug, and the
     * failure screen shows this string to a player who would go looking for a file that does
     * not exist. A browser console has no file to point at.
     */
    override fun location(): String? = null
}

internal actual fun platformDefaultLogSink(): LogSink = ConsoleSink

/**
 * No existence check on this target.
 *
 * A browser has no filesystem to check against, and there is no useful equivalent: a fetch
 * URL is not "present" or "absent" until it is requested. Claiming either would be the
 * crying wolf `core/AGENT.md` warns about, so this reports the value and leaves the verdict
 * to the request that follows it.
 */
internal actual fun describePlatformPath(value: Any?): String = when (value) {
    null -> " [null]"
    is String -> " [browser resource: resolved by the request that fetches it]"
    else -> " [not inspectable on wasm]"
}

/**
 * `HH:mm:ss.SSS` in UTC, formatted from the epoch directly.
 *
 * UTC rather than the browser's local zone: a log gathered from a player's bug report is
 * compared line by line against a build log, and two runs in two time zones would not line
 * up. The JVM sink uses the system zone because that log is local by nature; this one is
 * remote by nature, so the unambiguous zone is the more useful one.
 *
 * Arithmetic rather than a date-time library, because that is what the JVM half does too
 * once `DateTimeFormatter` has been given a zone -- and a log timestamp is not worth a
 * dependency whose API differs per target when the whole computation is three divisions.
 * `Instant.toEpochMilliseconds` is signed, so the pre-epoch case floors; that is 55 000
 * years before the game's first release and logs as `00:00:00.000`, which is fine.
 */
internal actual fun platformTimestamp(): String {
    val ms = Clock.System.now().toEpochMilliseconds()
    // Wrapped into [0, n) on purpose: Kotlin's `%` keeps the sign of the dividend, so a
    // negative remainder -- only reachable 55 000 years before the first release -- would
    // otherwise produce a timestamp with a leading minus sign.
    fun Long.mod(n: Long): Long = ((this % n) + n) % n
    val millis = ms.mod(1000)
    val secondOfDay = (ms / 1000).mod(86_400)
    fun twoDigits(v: Long) = v.toString().padStart(2, '0')
    fun threeDigits(v: Long) = v.toString().padStart(3, '0')
    return "${twoDigits(secondOfDay / 3600)}:" +
        "${twoDigits((secondOfDay / 60) % 60)}:" +
        "${twoDigits(secondOfDay % 60)}." +
        threeDigits(millis)
}

/**
 * What packaging changes on the web.
 *
 * A wasm build's equivalents of `java.io.tmpdir` and `user.dir` are the document origin and
 * the user agent: a module loaded from the wrong origin, or from a service worker serving a
 * stale build, is the wasm equivalent of a jar that went missing from the app image.
 *
 * `origin` and `href` go through `external val`s on a typed global rather than through `js()`,
 * because `location.href` is exactly the value a bug report needs verbatim and a string
 * surgery reconstruction of it is a bug waiting to happen.
 */
private external interface Location {
    val origin: String
    val href: String
}

private external interface Navigator {
    val userAgent: String
    val hardwareConcurrency: Int
}

private external interface Global {
    val location: Location
    val navigator: Navigator
}

/** `globalThis`, so a `file:` document without a `location` still resolves rather than throwing. */
private external val global: Global

private external interface Console {
    fun log(message: String)
    fun error(message: String)
}

/**
 * The console, looked up per call.
 *
 * A top-level `external val` would cache it at module init, which is too early to be
 * reliable: a module can be instantiated before the page has a console, and a wasm game can
 * be loaded into a worker or an iframe where `console` is replaced. Looking it up when a
 * line is actually written means the value is only needed once there is something to write.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun jsConsole(): Console = js("globalThis.console")

internal actual fun platformEnvironment(): Map<String, String> = buildMap {
    // Each entry is read inside its own `runCatching`, so one unavailable property -- a
    // `file:` document with no `navigator.hardwareConcurrency`, a browser that has removed an
    // API -- costs that value rather than the whole log line. `Log.environment` is a
    // diagnostic, and a diagnostic that throws has thrown away the evidence it was
    // collecting.
    put("userAgent", safe { global.navigator.userAgent })
    put("location.origin", safe { global.location.origin })
    put("location.href", safe { global.location.href })
    put("hardwareConcurrency", safe { global.navigator.hardwareConcurrency.toString() })
}

/** A property that could be missing or forbidden in this browser context. */
private inline fun safe(read: () -> String): String = runCatching(read).getOrDefault("<unavailable>")