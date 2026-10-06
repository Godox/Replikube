package fr.godox.replikube.app

import fr.godox.replikube.app.progress.FileProgressStorage
import fr.godox.replikube.app.progress.ProgressStore
import fr.godox.replikube.core.LevelProgress
import fr.godox.replikube.core.Progress
import fr.godox.replikube.core.Stars
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The save file, on the platform that has one.
 *
 * Progress is the only thing a player cannot recreate, so the store's job is to survive bad
 * input and never to be the reason the game will not start.
 *
 * ### Why this is a `jvmTest` and what it therefore does *not* cover
 *
 * [ProgressStore] is common — it owns the JSON and the forgiving-read policy — but everything
 * interesting about *this* file is about durability, and durability is a property of the
 * storage underneath it. So the temp-file-and-move in `FileProgressStorage`, the corrupt-file
 * rename, and the XDG default path are all only observable here.
 *
 * The wasm half, `LocalStorageProgressStorage`, has its own contract — a backup key that
 * covers a quota-refused write — and it is not tested by this file. It needs a browser to
 * mean anything, and the honest way to test it is a wasm test with a stubbed `Storage`, which
 * is the gap to close once the runner work makes the browser build worth running.
 *
 * The `ProgressStore` policy itself — round-tripping, stars never going down, attempts
 * accumulating, unknown keys ignored — is storage-independent and belongs in a `commonTest`.
 * These tests go through the file storage because that is the only one that exists on this
 * platform, which is convenient but incidental.
 */
class ProgressStoreTest {

    private fun store(): Pair<ProgressStore, Path> {
        val dir = Files.createTempDirectory("replikube-test")
        dir.toFile().deleteOnExit()
        val file = dir.resolve("progress.json")
        return ProgressStore(FileProgressStorage(file)) to file
    }

    @Test
    fun `a missing file reads as empty progress, not an error`() {
        val (store, file) = store()
        assertFalse(file.exists())
        assertEquals(Progress.EMPTY, store.load())
    }

    @Test
    fun `progress round-trips`() {
        val (store, _) = store()
        val progress = Progress(
            levels = mapOf(
                "hello-layers" to LevelProgress("hello-layers", solved = true, stars = Stars.GOLD, attempts = 4),
                "frame" to LevelProgress("frame", solved = true, stars = Stars.BRONZE, attempts = 9),
            ),
        )
        assertTrue(store.save(progress))
        assertEquals(progress, store.load())
    }

    @Test
    fun `the last solution is preserved`() {
        val (store, _) = store()
        val code = "fun block(x: Int, y: Int, z: Int): Int = RED\n"
        val saved = store.recordSolve(Progress.EMPTY, "spiral", Stars.SILVER, code)
        assertTrue(store.save(saved))

        val reloaded = store.load()
        assertEquals(code, reloaded["spiral"].lastSolution)
        assertTrue(reloaded["spiral"].solved)
    }

    @Test
    fun `stars never go down`() {
        val (store, _) = store()
        val best = store.recordSolve(Progress.EMPTY, "sphere", Stars.GOLD, "good")
        // A later, sloppier solve must not erase three stars. The stored code is still
        // replaced: it is the player's *last working* solution, and the editor reopens on
        // whatever they actually wrote last.
        val worse = store.recordSolve(best, "sphere", Stars.BRONZE, "bad")
        assertEquals(Stars.GOLD, worse["sphere"].stars)
        assertEquals("bad", worse["sphere"].lastSolution)
    }

    @Test
    fun `attempts accumulate`() {
        val (store, _) = store()
        var progress = Progress.EMPTY
        repeat(3) { progress = store.recordAttempt(progress, "diamond") }
        assertEquals(3, progress["diamond"].attempts)
        assertFalse(progress["diamond"].solved)
    }

    @Test
    fun `a corrupt save file is set aside rather than crashing the game`() {
        val (store, file) = store()
        Files.createDirectories(file.parent)
        file.writeText("{ this is not json")

        assertEquals(Progress.EMPTY, store.load())
        // Kept for the curious, so a player can recover hand-typed progress.
        assertTrue(file.resolveSibling("${file.fileName}.corrupt").exists())
    }

    @Test
    fun `an unknown key from a newer build is ignored`() {
        val (store, file) = store()
        Files.createDirectories(file.parent)
        file.writeText(
            """{"levels":{"spiral":{"levelId":"spiral","solved":true,"stars":"SILVER","attempts":1,"futureField":42}}}""",
        )
        val loaded = store.load()
        assertEquals(Stars.SILVER, loaded["spiral"].stars)
    }

    @Test
    fun `an unwritable location reports failure instead of throwing`() {
        val blocked = Files.createTempDirectory("replikube-test").resolve("a-file")
        blocked.writeText("not a directory")
        val store = ProgressStore(FileProgressStorage(blocked.resolve("nested").resolve("progress.json")))
        // Nothing here should throw: a read-only home directory loses stars, it does not
        // take the game down with it.
        assertFalse(store.save(Progress.EMPTY))
    }

    @Test
    fun `a write leaves no temp file behind`() {
        val (store, file) = store()
        assertTrue(store.save(Progress.EMPTY))
        // The temp file is what makes the write safe, so leaving one behind would mean either
        // a failed move or a bug in the cleanup -- and the next write would then collide with
        // it. Asserted because "it works" is true either way.
        assertFalse(file.resolveSibling("${file.fileName}.tmp").exists())
        assertTrue(file.exists())
    }

    @Test
    fun `the default path follows the XDG data directory`() {
        val xdg = System.getenv("XDG_DATA_HOME")
        val path = FileProgressStorage.defaultPath()
        assertTrue(path.endsWith("replikube/progress.json"), "unexpected default path: $path")
        if (!xdg.isNullOrBlank()) {
            assertTrue(path.startsWith(xdg), "$path should be under XDG_DATA_HOME=$xdg")
        }
    }

    @Test
    fun `total stars and solved count are derived from the levels`() {
        val progress = Progress(
            levels = mapOf(
                "a" to LevelProgress("a", solved = true, stars = Stars.GOLD),
                "b" to LevelProgress("b", solved = true, stars = Stars.BRONZE),
                "c" to LevelProgress("c"),
            ),
        )
        assertEquals(4, progress.totalStars)
        assertEquals(setOf("a", "b"), progress.solvedIds)
    }
}