package fr.godox.replikube.app

import fr.godox.replikube.app.progress.LocalStorageProgressStorage
import fr.godox.replikube.app.progress.ProgressStorage
import fr.godox.replikube.scripting.RemoteScriptRunner
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
 * The remote runner: the player's source goes to a compiler service and comes back as a grid.
 *
 * ### Why a service rather than a compiler here
 *
 * The JVM runner embeds `kotlin-compiler-embeddable` and drives it on a thread it owns. There
 * is no version of that which works in a browser: no JVM, no filesystem to hold the compiler
 * jar, no threads, and a page that will not let a module download forty megabytes at startup.
 * So the question for wasm was never *how* to run the compiler but *where* it runs. The answer
 * is JetBrains' own playground backend, `api.kotlinlang.org/api/<version>/compiler/run`, which
 * compiles and runs a set of source files server-side and returns the diagnostics and output.
 *
 * ### What this costs, stated plainly
 *
 * The game is no longer offline, and the sandbox is thinner. Locally, `:dsl` reaches the
 * compiler as a jar on a classpath holding nothing else; remotely it travels as source text,
 * because the service has no classpath of ours to extend. The player's reach is therefore
 * whatever `:dsl` can see — which is why `:dsl` still has no dependencies, now for a second
 * reason. Both are recorded in `scripting/AGENT.md`.
 *
 * What did *not* change is what the player sees: same [ScriptRunner], same diagnostics, and a
 * compile error pointing at the same line of their own source. `RemoteScriptRunner` builds the
 * same wrapper `SolutionCompiler` does, so the line mapping is literally the same code.
 */
actual fun platformScriptRunner(): ScriptRunner = RemoteScriptRunner()