package fr.godox.replikube.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import fr.godox.replikube.app.ui.AppColors
import fr.godox.replikube.app.ui.GameScreen
import fr.godox.replikube.core.log.Log

/**
 * The whole application, minus the window.
 *
 * One [GameModel] for the lifetime of the page, created here and remembered, so switching
 * levels or running code never rebuilds it. The model is built from a [GameModel.Factory]
 * rather than with defaults, because the two things it needs -- a `ScriptRunner` and a
 * progress store -- do not exist by default on either target. See that class.
 *
 * ### Why there is no `main()` here
 *
 * `main` is a different function on each platform: a window and an `exitApplication` on the
 * JVM, a canvas element in a browser. What is genuinely shared is everything inside the
 * window, and this is that. Two short `main`s and one screen beats one `main` with a `when`
 * in it -- or what it used to be, one `main` that was 100% AWT and had to be deleted.
 *
 * This file is `App.kt` and not `Main.kt` for a mechanical reason: the two per-target entry
 * points are both called `Main.kt` in the same package, and on the JVM a file's class name is
 * its name plus `Kt`, so both would be `MainKt` and the compile fails with `Duplicate JVM
 * class name` -- a name clash with no mention of the file that lost. Naming the shared half
 * after what it contains keeps `fr.godox.replikube.app.MainKt` unique, which is also the
 * `mainClass` in `app/build.gradle.kts`.
 */
@Composable
fun ReplikubeApp(factory: GameModel.Factory) {
    val model = remember(factory) { factory.create() }
    GameScreen(model, Modifier.fillMaxSize().background(AppColors.background))
}

/**
 * Records the environment before anything else runs.
 *
 * A packaged build differs from a development one in ways no exception message mentions --
 * on the JVM a read-only FUSE mount, a different temp directory, jars under the app image
 * rather than beside them; in a browser the origin and whether a service worker is serving a
 * stale build. So the first thing the log records is which of those it is running as.
 */
fun logEnvironment(extra: Map<String, Any?> = emptyMap()) {
    Log.i(TAG, "=== replikube starting ===")
    Log.environment()
    if (extra.isNotEmpty()) Log.paths("module locations", extra)
}

/**
 * Log tag for this file.
 *
 * Private, and deliberately named `TAG` like every other file's tag constant in this package
 * -- which sounds like a collision and is not. Each file declares its own `private const val
 * TAG`, so two files in one package each have one and neither sees the other's.
 *
 * It only breaks when one of them is `internal`: then it *is* visible to the other file, both
 * declarations enter scope, and every `Log.i(TAG, ...)` in the affected file becomes an
 * overload-resolution ambiguity between two `String`s. The diagnostic names `TAG` twice and
 * nothing else, so it reads like a type problem rather than a visibility one.
 */
private const val TAG = "App"