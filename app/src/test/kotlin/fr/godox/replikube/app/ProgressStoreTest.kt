package fr.godox.replikube.app

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
 * The save file.
 *
 * Progress is the only thing a player cannot recreate, so the store's job is to survive
 * bad input and never to be the reason the game will not start.
 */
class ProgressStoreTest {

    private fun store(): Pair<ProgressStore, Path> {
        val dir = Files.createTempDirectory("replikube-test")
        dir.toFile().deleteOnExit()
        val file = dir.resolve("progress.json")
        return ProgressStore(file) to file
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
        val store = ProgressStore(blocked.resolve("nested").resolve("progress.json"))
        // Nothing here should throw: a read-only home directory loses stars, it does not
        // take the game down with it.
        assertFalse(store.save(Progress.EMPTY))
    }

    @Test
    fun `the default path follows the XDG data directory`() {
        val xdg = System.getenv("XDG_DATA_HOME")
        val path = ProgressStore.defaultPath()
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
