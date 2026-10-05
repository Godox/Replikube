package fr.godox.replikube.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import fr.godox.replikube.app.progress.NoProgressStorage
import fr.godox.replikube.app.progress.ProgressStore
import fr.godox.replikube.levels.LevelCatalog

/**
 * The browser entry point.
 *
 * `ComposeViewport` is not a window: it mounts into an element the page already has, creates
 * its own `<canvas>` inside it, and sizes that canvas to the element. There is no
 * `onCloseRequest` because a tab has no close button — the page navigates away or not at all
 * — and no window state to remember, because the container already has a size.
 *
 * The id is the one thing that must agree with the HTML, which is why `index.html` declares
 * it as well. It is a string constant rather than a parameter so that a mismatch is a compile
 * error in one place instead of a blank page in production.
 *
 * ### Why `ComposeViewport` and not `CanvasBasedWindow`
 *
 * `CanvasBasedWindow` is deprecated in Compose 1.9, and the reason matters more than the
 * rename: it renders straight to a canvas and skips the HTML interop layer entirely, so there
 * is no `WebElementView` support — any Compose component that needs to interop with an HTML
 * element cannot work under it — and no accessibility tree, so a screen reader sees an
 * unlabelled graphic. The game being mostly cubes on a canvas does not need either *today*,
 * but the editor is a text field, and a text field without an accessibility tree is unusable
 * with a screen reader. `ComposeViewport` keeps both.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    logEnvironment(mapOf("viewport" to VIEWPORT_ID))

    ComposeViewport(viewportContainerId = VIEWPORT_ID) {
        ReplikubeApp(GameModel.Factory(catalog = LevelCatalog(), progressStore = progressStore()))
    }
}

/**
 * Where progress goes, when the page is allowed to keep anything.
 *
 * `localStorage` is per-origin, so a player who opens the game from a different host loses
 * their save — which is why the key is namespaced and why a missing storage falls back to a
 * session-only store instead of failing.
 */
private fun progressStore() =
    ProgressStore(platformProgressStorage() ?: NoProgressStorage)

/**
 * Must match the `id` of the host element in `wasmJsMain/resources/index.html`.
 *
 * It identifies the *container*, not a canvas: `ComposeViewport` creates the `<canvas>`
 * itself, sized to whatever this element measures. Handing it a canvas id instead -- which is
 * what the deprecated `CanvasBasedWindow` took, and why the port reads like a rename -- fails
 * at compile time with `No value passed for parameter 'viewportContainer'` and
 * `No parameter with name 'canvasElementId'`, because the two parameters genuinely differ
 * rather than merely being spelled differently.
 */
private const val VIEWPORT_ID = "replikubeViewport"