package fr.godox.replikube.core.log

/**
 * The platform half of the logging seam.
 *
 * Four functions, each for something that genuinely cannot be shared: where a line goes,
 * what the environment looks like, how a timestamp is rendered, and whether a path exists.
 * Everything else -- levels, tags, the levels themselves, the structured `path`/`paths`
 * helpers -- lives in common `Log` and behaves identically on both targets.
 *
 * `actual` rather than an injected interface for the timestamp and the path check, because
 * both are total functions of their input with no interesting behaviour to vary. The
 * *sink* stays an interface: that one is swapped in tests and by the UI, so it needs a
 * seam a caller can install.
 */

/** Where lines go unless [Log.sink] has been replaced. */
internal expect fun platformDefaultLogSink(): LogSink

/**
 * Suffix for [Log.path] describing whether a path resolved.
 *
 * On the JVM this checks the filesystem, which is the single most useful line in a
 * packaged-build bug report. On wasm there is nothing to check, so it reports the value
 * without claiming a verdict.
 */
internal expect fun describePlatformPath(value: Any?): String

/** `HH:mm:ss.SSS`. Same width and field order on both targets, so logs can be compared. */
internal expect fun platformTimestamp(): String

/** The environment variables and properties packaging changes. */
internal expect fun platformEnvironment(): Map<String, String>