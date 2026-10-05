package fr.godox.replikube.app.progress

import kotlin.js.ExperimentalWasmJsInterop

/**
 * Progress in `localStorage`.
 *
 * ### Why the whole write dance disappears here
 *
 * [FileProgressStorage] writes a temp file and moves it over the real one, because a
 * filesystem write is not atomic and a half-written save would be read as *empty progress*.
 * `localStorage.setItem` has no such window: the browser either stores the new value or
 * leaves the old one. So the entire class of bug the temp file exists to prevent cannot occur,
 * and reproducing the mechanism here would be theatre.
 *
 * ### Why there is a `.bak` key
 *
 * Quota. A browser will refuse to store new data once the origin's quota is spent, and it
 * refuses by throwing -- so [write] can fail in a way a disk never did, mid-play, with the
 * player having no idea why their stars stopped sticking. Keeping the previous value under a
 * second key means a failed write still leaves a readable save: [read] falls back to the
 * backup, which costs one extra key and turns "progress was lost" into "progress is one
 * solve old".
 *
 * ### Why not IndexedDB
 *
 * It has a larger quota and is the right answer for a game that stored replays or
 * screenshots. A progress file is a few kilobytes of JSON, `localStorage` is synchronous --
 * so `ProgressStore.load` can stay non-suspending and the game can show the player's stars
 * before its first frame -- and its failure mode is the one above, which the backup key
 * already covers. Moving to IndexedDB would mean making every read a coroutine for a quota
 * this data does not come close to.
 */
class LocalStorageProgressStorage(private val key: String = DEFAULT_KEY) : ProgressStorage {

    override fun read(): String? =
        storage()?.let { local ->
            // The primary value first, the backup second: the backup exists only to cover a
            // failed write, so it must never win when the primary is intact.
            runCatching { local.getItem(key) }.getOrNull()
                ?: runCatching { local.getItem(backupKey) }.getOrNull()
        }

    override fun write(text: String): Boolean {
        val local = storage() ?: return false
        return runCatching {
            // Copy the current value aside *before* overwriting it. If this write throws on
            // quota, the old value is already safe.
            runCatching { local.getItem(key) }.getOrNull()?.let { previous ->
                runCatching { local.setItem(backupKey, previous) }
            }
            local.setItem(key, text)
            true
        }.getOrDefault(false)
    }

    override fun quarantine(): Boolean {
        val local = storage() ?: return false
        // Kept under a distinct key rather than deleted, so a player who reports "it reset
        // my progress" can be handed the text back.
        return runCatching {
            val bad = local.getItem(key) ?: return@runCatching true
            local.setItem("$key.corrupt", bad)
            local.removeItem(key)
            local.removeItem(backupKey)
            true
        }.getOrDefault(false)
    }

    /**
     * The shadow key [write] copies to before overwriting [key].
     *
     * A property rather than a function, so every use above is a value: written as
     * `backupKey()` it reads as a call but is a field initialised in declaration order, and
     * the compiler's "Function invocation 'backupKey()' expected" is the only clue that the
     * parentheses were wrong.
     */
    private val backupKey = "$key.bak"

    /**
     * The page's storage, or `null` when there is none.
     *
     * Null is a real case, not a formality: a wasm module loaded into a sandboxed `iframe`,
     * or from a `file:` document in some browsers, has no accessible storage at all. Touching
     * `localStorage` there throws on *access*, so it is looked up defensively and every
     * operation above is already written to survive a `null`.
     */
    private fun storage(): Storage? = runCatching { jsLocalStorage() }.getOrNull()

    companion object {
        const val DEFAULT_KEY = "replikube.progress"
    }
}

/** The four methods of `window.localStorage` this class uses. */
private external interface Storage {
    fun getItem(key: String): String?
    fun setItem(key: String, value: String)
    fun removeItem(key: String)
}

/**
 * `window.localStorage`, or throws.
 *
 * Both halves of this shape are forced by Kotlin/Wasm rather than chosen. `js("...")` must be
 * a single expression sitting *directly* in the body of a top-level function or a property
 * initializer: not inside a class — even inside a `companion object`, which is where this
 * started — and not wrapped in anything, because a `runCatching { js(...) }` is still one
 * expression but the `js` call is no longer the body's expression. Both mistakes report
 * "Calls to 'js(code)' must be a single expression inside a top-level function body or a
 * property initializer", which describes the code as malformed rather than its *placement* as
 * wrong. Same shape as `core`'s wasm `Log`, for the same reason.
 *
 * So the guarding lives at the call site, which wants a `null` to fall back from anyway:
 * touching `localStorage` throws on access in a sandboxed frame or a `file:` document.
 * `globalThis` rather than `window` for the same reason `core`'s wasm `Log` uses it — a worker
 * or a module context has no `window`.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun jsLocalStorage(): Storage = js("globalThis.localStorage")

