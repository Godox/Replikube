package fr.godox.replikube.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import fr.godox.replikube.app.ui.AppColors
import fr.godox.replikube.app.ui.GameScreen
import fr.godox.replikube.core.Level
import fr.godox.replikube.core.log.Log
import fr.godox.replikube.dsl.Replikube
import fr.godox.replikube.levels.LevelCatalog

/**
 * The whole application: one window, one screen, one [GameModel].
 *
 * The model is created here and remembered for the window's lifetime, so switching levels
 * or running code never rebuilds it. It is not injected from outside because there is
 * exactly one composition root; tests construct their own.
 *
 * The environment is logged before anything else. A packaged build differs from a
 * development one in ways no exception message mentions — a read-only FUSE mount, a
 * different temp directory, jars under the app image rather than beside them — so the first
 * thing the log records is which of those it is running as.
 */
fun main() {
    Log.i(TAG, "=== replikube starting ===")
    Log.environment()
    Log.paths("module jars", moduleLocations())

    application {
        val model = remember { GameModel() }
        Window(
            onCloseRequest = ::exitApplication,
            state = rememberWindowState(size = DpSize(1360.dp, 860.dp)),
            title = "Replikube",
        ) {
            GameScreen(model, Modifier.fillMaxSize().background(AppColors.background))
        }
    }
    Log.i(TAG, "=== replikube exiting ===")
}

/** Log tag for this file. */
private const val TAG = "Main"

/**
 * Where each module ended up on disk.
 *
 * In a packaged app the classpath is a flat directory of jars under the app image, and the
 * question "is `:levels` actually there" is answered here once rather than guessed at from
 * a failure three layers down. Classes are named by their own module so the reader can tell
 * which entry went missing.
 */
private fun moduleLocations(): Map<String, Any?> = mapOf(
    "core.Level" to Level::class.java,
    "levels.LevelCatalog" to LevelCatalog::class.java,
    "dsl.Replikube" to Replikube::class.java,
    "app.GameModel" to GameModel::class.java,
).mapValues { (_, type) -> type.protectionDomain?.codeSource?.location }
