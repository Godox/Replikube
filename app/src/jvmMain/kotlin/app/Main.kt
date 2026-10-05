package fr.godox.replikube.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.godox.replikube.app.progress.NoProgressStorage
import fr.godox.replikube.app.progress.ProgressStore
import fr.godox.replikube.levels.LevelCatalog

/**
 * The desktop entry point: one window, one screen, one [GameModel].
 *
 * ### Why `main` is here and not in `commonMain`
 *
 * It used to be, and it was 100% AWT — `application {}`, `Window`, `rememberWindowState` —
 * which is not a thing a browser has. The shape of the two entry points is genuinely
 * different and there is no common denominator worth having:
 *
 * - The JVM opens an OS window, which is a *stateful object* the caller owns and closes,
 *   hence `rememberWindowState` and `onCloseRequest`.
 * - The browser mounts into a canvas element that already exists in the page, and it has no
 *   lifecycle the Kotlin code gets to end.
 *
 * So the shared part is [ReplikubeApp] — everything inside the window — and these are the two
 * short wrappers around it. What is duplicated is the window plumbing, which is precisely the
 * part that has to differ.
 *
 * ### Why the model is remembered rather than rebuilt
 *
 * Switching levels and running code both mutate the model; reconstructing it would discard a
 * player's in-progress solution and reset the camera. It is created once per window rather
 * than injected from outside, because there is exactly one composition root — tests build
 * their own, with their own runner and store.
 */
fun main() {
    logEnvironment(
        mapOf(
            "jvm" to System.getProperty("java.version"),
            "java.home" to System.getProperty("java.home"),
            // Named because it is the first thing to be absent in a packaged build: a jlink
            // runtime missing `jdk.compiler` takes down the embedded runner at first use, and
            // `java.class.path` is what tells that case apart from a development one.
            "java.class.path" to System.getProperty("java.class.path"),
        ),
    )

    application {
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(1280.dp, 900.dp)),
            title = "replikube",
        ) {
            ReplikubeApp(GameModel.Factory(catalog = LevelCatalog(), progressStore = progressStore()))
        }
    }
}

/**
 * A store over the platform's storage, falling back to one that cannot persist.
 *
 * The desktop always has a `~/.local/share/replikube/progress.json`, so the fallback is dead
 * code today. It is here rather than a `!!` because `platformProgressStorage` is nullable for
 * the browser's sake, and a nullable platform decision should not become a crash on a target
 * where it cannot happen.
 */
private fun progressStore() =
    ProgressStore(platformProgressStorage() ?: NoProgressStorage)