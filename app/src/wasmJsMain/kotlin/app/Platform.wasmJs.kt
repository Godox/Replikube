package fr.godox.replikube.app

import fr.godox.replikube.app.progress.LocalStorageProgressStorage
import fr.godox.replikube.app.progress.ProgressStorage
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.scripting.Diagnostic
import fr.godox.replikube.scripting.RunResult
import fr.godox.replikube.scripting.ScriptRunner

/**
 * Progress in `localStorage`, when the page is allowed to have any.
 *
 * The `null` is a real case rather than defensive noise: a wasm module loaded into a
 * sandboxed `iframe`, or served from a `file:` document, can reach no `localStorage` at all,
 * and touching it there throws on *access*. So the storage is built lazily and reported as
 * absent, and the game runs with a session-only progress rather than failing to instantiate.
 *
 * ### Why the backup key is worth its cost
 *
 * A browser refuses a write once the origin's quota is spent, and it refuses by *throwing*,
 * mid-play. `LocalStorageProgressStorage` copies the previous value aside before overwriting,
 * so a write that fails on quota still leaves a readable save — the player's stars are one
 * solve old rather than gone.
 */
actual fun platformProgressStorage(): ProgressStorage? =
    runCatching { LocalStorageProgressStorage() }.getOrNull()

/**
 * No runner yet: this is the decision the wasm port has not made.
 *
 * ### Why this is not `InProcessScriptRunner` behind a flag
 *
 * The JVM runner embeds `kotlin-compiler-embeddable` and drives it on a thread it owns. There
 * is no version of that which works here: no JVM, no filesystem to hold the compiler jar, no
 * threads, and a browser that will not let a module download forty megabytes at startup. So
 * the question for wasm is not *how* to run the compiler but *where* it runs.
 *
 * The answer is a compiler service: the wasm build posts the player's source to
 * `api.kotlinlang.org/api/2.4.20/compiler/run`, which compiles and runs it server-side and
 * returns the output. That path has been verified end to end against the stored level
 * matrices; what is not yet written is the client.
 *
 * ### Why this returns a failing runner rather than throwing
 *
 * It fails as a *diagnostic* — the same shape as a player's typo — so the whole pipeline above
 * it is exercised and the player sees a sentence instead of a blank screen. `error(...)` in
 * the composition root would take down the game with a stack trace, which tells a player
 * nothing and a developer less than a result panel saying "running code is not available in
 * the browser build yet".
 *
 * Deliberately loud rather than quietly empty: a build that appears to work and never grades
 * anything is worse than one that says it cannot.
 */
actual fun platformScriptRunner(): ScriptRunner = UnavailableRunner

/** A runner that reports that player code cannot be run here, as a normal failure. */
private object UnavailableRunner : ScriptRunner {

    override suspend fun run(source: String, size: GridSize, timeoutMillis: Long): RunResult =
        RunResult.Failure(
            Diagnostic(
                kind = Diagnostic.Kind.INTERNAL_ERROR,
                message = "Running code is not available in the browser build yet",
                detail = "The desktop build compiles player code in-process with the Kotlin " +
                    "compiler. A browser has no JVM to run it in, so this build has to send " +
                    "the source to a compiler service instead -- that client is not written " +
                    "yet. The desktop build is unaffected.",
            ),
        )
}