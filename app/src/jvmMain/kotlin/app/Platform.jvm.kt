package fr.godox.replikube.app

import fr.godox.replikube.app.progress.FileProgressStorage
import fr.godox.replikube.app.progress.ProgressStorage
import fr.godox.replikube.scripting.InProcessScriptRunner
import fr.godox.replikube.scripting.ScriptRunner

/**
 * The desktop runner: the Kotlin compiler, in this process.
 *
 * `InProcessScriptRunner` embeds `kotlin-compiler-embeddable` and drives it directly, which is
 * the whole reason the desktop build ships a jlink runtime with `jdk.compiler` in it — see the
 * `modules(...)` list in `app/build.gradle.kts`. It is unworkable in a browser, and the wasm
 * half of [platformScriptRunner] is where that becomes someone else's problem.
 *
 * Not cached: it owns a compiler instance that is expensive to construct, so a fresh one per
 * `GameModel` is the honest cost of a model being created once per window. Tests construct
 * their own runner and their own model.
 */
actual fun platformScriptRunner(): ScriptRunner = InProcessScriptRunner()

/**
 * Progress in the XDG data directory.
 *
 * Non-null on this target, and the constructor is why: there is a home directory and a path
 * to write to. The `ProgressStorage?` return type is nullable only because a sandboxed
 * browser can have no storage at all, and one shape for both platforms is worth more than
 * one nullable-checked call site here.
 */
actual fun platformProgressStorage(): ProgressStorage? =
    FileProgressStorage(FileProgressStorage.defaultPath())