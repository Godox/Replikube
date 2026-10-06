package fr.godox.replikube.app

import fr.godox.replikube.app.progress.ProgressStorage
import fr.godox.replikube.scripting.ScriptRunner

/**
 * The two decisions a target has to make, and the only `expect` declarations in `:app`.
 *
 * They are collected here, rather than sitting next to the code that uses them, because they
 * are the seam of the whole port and it is useful to be able to read both of them at once.
 *
 * ### Both return interfaces, neither takes a platform type
 *
 * `ScriptRunner` has two implementations with nothing in common but the interface, and
 * `ProgressStorage` has the same problem. An `expect class` would demand a shared supertype
 * that is a fiction; a constructor parameter typed `Path` would demand the common half to
 * know about the JVM's filesystem. So the seam is a function returning an interface, and
 * everything platform-specific stays behind it.
 *
 * ### Why a function and not a default argument
 *
 * Constructor default arguments are resolved on *every* target, used or not. A
 * `runner: ScriptRunner = InProcessScriptRunner()` default would fail to compile the wasm
 * target on the default alone, even in a code path that never runs there. A function the
 * platform implements is evaluated only where it is called.
 */
expect fun platformScriptRunner(): ScriptRunner

/**
 * Where progress is stored, or `null` when the platform cannot store it.
 *
 * Nullable because "no storage" is a real answer rather than an error: a wasm module in a
 * sandboxed `iframe`, or served from a `file:` document, can reach no `localStorage` at all.
 * The game still runs there, it just does not remember anything, which is a far better
 * outcome than a module that refuses to instantiate.
 */
expect fun platformProgressStorage(): ProgressStorage?