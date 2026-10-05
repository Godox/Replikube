package fr.godox.replikube.app

import fr.godox.replikube.app.progress.ProgressStore
import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.Diff
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.LevelProgress
import fr.godox.replikube.core.LoadedLevel
import fr.godox.replikube.core.Progress
import fr.godox.replikube.core.Stars
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.core.WrongVoxel
import fr.godox.replikube.dsl.Palette
import fr.godox.replikube.levels.LevelCatalog
import fr.godox.replikube.scripting.Diagnostic
import fr.godox.replikube.scripting.RunResult
import fr.godox.replikube.scripting.ScriptRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The game state machine.
 *
 * This is where the actual rules live — what counts as a solve, what earns three stars,
 * when a stale result may be shown. All of it is testable without a window, which is the
 * main reason [GameModel] is a plain class rather than a pile of Compose state.
 *
 * No test here compiles Kotlin. Targets are stored matrices, so the catalogue needs no runner
 * at all and the real one is used; the stub answers the player's editor contents with whatever
 * the test wants. `InProcessScriptRunnerTest` covers compilation properly.
 */
class GameModelTest {

    /**
     * The level most of these tests open: a 3x3x3 grid, one colour per layer.
     *
     * Smallest level in the campaign, and the only one whose starter is a working solution,
     * which is what makes it the natural subject for the state-machine rules.
     */
    private val levelId = "hello-layers"

    /**
     * A level with empty space in it, for the tests that need one.
     *
     * [levelId] is solid in every cell, so there is no coordinate a test could call "extra" —
     * a near miss needs somewhere to put the mistake. `single-voxel` is 5x5x5 with one voxel.
     */
    private val sparseLevelId = "single-voxel"

    /**
     * A real level's target grid, straight from the catalogue.
     *
     * Read from the shipped metadata rather than invented here. The alternative — a hand-built
     * grid standing in for the level — is what made these tests lie: they passed against a
     * 3x3x3 red bottom layer while the level they actually opened was 27 solid voxels in three
     * colours, so every assertion about solving was measuring a grid the game never shows.
     */
    private fun levelTarget(id: String): VoxelGrid {
        val loaded = runBlocking { LevelCatalog().load(id) }
        return loaded.getOrElse { error("level $id did not load; the level files are inconsistent: $it") }
            .target
    }

    /** [levelId]'s target. */
    private val target: VoxelGrid by lazy { levelTarget(levelId) }

    /**
     * Answers the player's editor contents, and counts how often.
     *
     * Targets are stored matrices, so this is only ever asked about *player* code. It used to
     * have to recognise the catalogue's reference solutions by matching them against the text
     * the game supplies, which was the price of level targets being compiled through the same
     * runner; that ambiguity is gone, and so is the matching.
     */
    private class Stub(
        private val player: (GridSize) -> RunResult,
    ) : ScriptRunner {
        var playerCalls = 0
            private set

        override suspend fun run(source: String, size: GridSize, timeoutMillis: Long): RunResult {
            playerCalls++
            return player(size)
        }
    }

    private class Fixture(val model: GameModel, val stub: Stub, val saveFile: Path)

    private fun fixture(
        player: (GridSize) -> RunResult = { RunResult.Success(target) },
        saveFile: Path? = null,
    ): Fixture {
        val dir = Files.createTempDirectory("replikube-test")
        dir.toFile().deleteOnExit()
        val save = saveFile ?: dir.resolve("progress.json")
        val stub = Stub(player)

        val model = GameModel(
            // The real catalogue, not a stub: targets are stored matrices now, so there is
            // nothing to stub. The stub runner is left answering player code only.
            catalog = LevelCatalog(),
            runner = stub,
            progressStore = ProgressStore(save),
            // Unconfined so a run starts eagerly; the await helpers below cover the one
            // real suspension point, the Dispatchers.IO hop inside run().
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
        return Fixture(model, stub, save)
    }

    private fun GameModel.await(predicate: (GameState) -> Boolean, what: String): GameState =
        runBlocking {
            withTimeoutOrNull(5_000) { state.first(predicate) }
                ?: error("timed out waiting for $what; state was $state")
        }

    private fun GameModel.open(levelId: String): GameState {
        start(levelId)
        return await({ it.level != null || it.levelError != null }, "level $levelId to load")
    }

    /**
     * Runs whatever is in the editor and returns the outcome.
     *
     * Checks that the stub actually saw a player run. Without that, a test that forgets to
     * set the editor gets the level's own reference solution instead — silently, because
     * [SolutionTemplates] hands out the real solution for the tutorial levels.
     */
    private fun Fixture.runAndAwait(): RunOutcome {
        val before = stub.playerCalls
        model.run()
        val outcome = model.await({ it.outcome !is RunOutcome.Running }, "the run to finish").outcome
        check(stub.playerCalls > before) {
            "the editor still held a level's reference solution, so the run was answered by " +
                "the stub's reference branch. Call setCode() first."
        }
        return outcome
    }

    private fun Fixture.setCode(code: String) = model.setCode(code)

    private val solution = "return RED"

    @Test
    fun `opening a level reads its target and shows a starter`() {
        val fixture = fixture()
        val state = fixture.model.open(levelId)
        assertNull(state.levelError)
        assertEquals(target, assertNotNull(state.level).target)
        assertFalse(
            state.code.contains("fun block"),
            "the player never types a signature, so the editor must not show one: ${state.code}",
        )
        assertTrue(state.code.contains("return"), "the starter is a body: ${state.code}")
        assertEquals(RunOutcome.Idle, state.outcome)
    }

    @Test
    fun `a tutorial starter solves its level, so the first one teaches by editing`() {
        // Worth pinning: the starter for a tutorial level is deliberately a working
        // solution, which is also why the stub above has to be told the two call sites
        // apart. It differs from the reference only by a comment explaining itself.
        val level = LevelCatalog.readMetadata(levelId)
        val starter = SolutionTemplates.forLevel(level)

        assertTrue(starter.contains("//"), "a tutorial starter explains itself: $starter")
        assertEquals(
            // `trimEnd` on both: the starter is a template and ends with a newline, while the
            // reference is stored inline in the level file and does not. Without it the
            // comparison is really about a trailing blank line, and says nothing useful.
            level.reference.trimEnd().lines(),
            starter.trimEnd().lines().filterNot { it.trimStart().startsWith("//") },
            "apart from its comments, a tutorial starter is the reference solution",
        )
    }

    @Test
    fun `the exact target solves the level and awards three stars at or under par`() {
        val fixture = fixture()
        fixture.model.open(levelId)
        fixture.setCode(solution)

        val outcome = fixture.runAndAwait()
        assertTrue(outcome is RunOutcome.Solved, "expected a solve, got $outcome")
        assertEquals(Stars.GOLD, outcome.stars)
        assertTrue(fixture.model.state.value.progress[levelId].solved)
    }

    @Test
    fun `a near miss says which voxels are wrong instead of just failing`() {
        // Right shape, right place: one voxel in the wrong colour, one voxel too many.
        // Built against a level that has empty space, since a grid solid in every cell has
        // nowhere to put the extra voxel.
        val sparse = levelTarget(sparseLevelId)
        val centre = sparse.allCoords().first { sparse[it] != Palette.EMPTY }
        val empty = sparse.allCoords().first { it != centre && sparse[it] == Palette.EMPTY }
        val nearly = VoxelGrid.build(sparse.size) { x, y, z ->
            when (Coord(x, y, z)) {
                centre -> Palette.BLUE
                empty -> Palette.RED
                else -> sparse[x, y, z]
            }
        }
        val fixture = fixture(player = { RunResult.Success(nearly) })
        fixture.model.open(sparseLevelId)
        fixture.setCode(solution)

        val outcome = fixture.runAndAwait()
        assertTrue(outcome is RunOutcome.Unsolved, "expected unsolved, got $outcome")
        assertEquals(
            Diff(
                wrongColor = listOf(WrongVoxel(centre, expected = sparse[centre], actual = Palette.BLUE)),
                extra = listOf(empty),
            ),
            outcome.diff,
        )
        assertFalse(fixture.model.state.value.progress[sparseLevelId].solved)
    }

    @Test
    fun `a compile failure is reported with the player's own line number`() {
        val fixture = fixture(
            player = {
                RunResult.Failure(
                    Diagnostic(
                        kind = Diagnostic.Kind.COMPILE_ERROR,
                        message = "Expecting a top level declaration",
                        line = 2,
                        snippet = "  oops",
                    ),
                )
            },
        )
        fixture.model.open(levelId)
        fixture.setCode("$solution\noops")

        val outcome = fixture.runAndAwait()
        assertTrue(outcome is RunOutcome.Failed, "expected a failure, got $outcome")
        assertEquals(Diagnostic.Kind.COMPILE_ERROR, outcome.diagnostic.kind)
        assertEquals(2, outcome.diagnostic.line)
        assertEquals("  oops", outcome.diagnostic.snippet)
        assertNull(fixture.model.state.value.result, "a failed run has no grid to show")
    }

    @Test
    fun `a timeout is reported as a failure, not as a crash`() {
        val fixture = fixture(
            player = { RunResult.Failure(Diagnostic(Diagnostic.Kind.TIMEOUT, "Your code ran for too long.")) },
        )
        fixture.model.open(levelId)
        fixture.setCode(solution)

        val outcome = fixture.runAndAwait()
        assertEquals(Diagnostic.Kind.TIMEOUT, (outcome as RunOutcome.Failed).diagnostic.kind)
    }

    @Test
    fun `a run against the wrong grid size is a contract error naming the right size`() {
        val fixture = fixture(player = { RunResult.Success(VoxelGrid.empty(GridSize(2, 2, 2))) })
        fixture.model.open(levelId)
        fixture.setCode(solution)

        val outcome = fixture.runAndAwait()
        assertTrue(outcome is RunOutcome.Failed, "expected a failure, got $outcome")
        assertEquals(Diagnostic.Kind.CONTRACT_ERROR, outcome.diagnostic.kind)
        assertTrue("3×3×3" in outcome.diagnostic.message, "should name the size: ${outcome.diagnostic.message}")
    }

    @Test
    fun `a runtime failure names the offending coordinate`() {
        val fixture = fixture(
            player = {
                RunResult.Failure(
                    Diagnostic(Diagnostic.Kind.CONTRACT_ERROR, "Colour 99 is not in the palette at (0, 0, -1)."),
                )
            },
        )
        fixture.model.open(levelId)
        fixture.setCode(solution)

        val outcome = fixture.runAndAwait()
        assertTrue("(0, 0, -1)" in (outcome as RunOutcome.Failed).diagnostic.message)
    }

    @Test
    fun `every attempt is counted, solved or not`() {
        val fixture = fixture()
        fixture.model.open(levelId)
        fixture.setCode(solution)
        repeat(2) { fixture.runAndAwait() }
        assertEquals(2, fixture.model.state.value.progress[levelId].attempts)
    }

    @Test
    fun `stars fall as the solution outgrows par`() {
        val fixture = fixture()
        val par = fixture.model.state.value.levels.first { it.id == levelId }.par
        fixture.model.open(levelId)

        // Blank lines are not counted, so padding has to be real lines.
        fun padded(extra: Int) = (solution + "\n" + (1..extra).joinToString("\n") { "// note $it" })

        fixture.setCode(padded(0))
        assertEquals(Stars.GOLD, fixture.runAndAwait().let { (it as RunOutcome.Solved).stars }, "at par")

        fixture.setCode(padded(par))
        assertEquals(Stars.SILVER, fixture.runAndAwait().let { (it as RunOutcome.Solved).stars }, "one over par")

        fixture.setCode(padded(par * 2))
        assertEquals(Stars.BRONZE, fixture.runAndAwait().let { (it as RunOutcome.Solved).stars }, "over twice par")
    }

    @Test
    fun `a solve never downgrades the stars already earned`() {
        val fixture = fixture()
        val par = fixture.model.state.value.levels.first { it.id == levelId }.par
        fixture.model.open(levelId)

        fixture.setCode(solution)
        fixture.runAndAwait()
        assertEquals(Stars.GOLD, fixture.model.state.value.progress[levelId].stars)

        fixture.setCode(solution + "\n" + (1..par * 2).joinToString("\n") { "// note $it" })
        fixture.runAndAwait()
        assertEquals(Stars.BRONZE, (fixture.model.state.value.outcome as RunOutcome.Solved).stars)
        assertEquals(Stars.GOLD, fixture.model.state.value.progress[levelId].stars, "the best run should stand")
    }

    @Test
    fun `the last working solution is saved and reloaded`() {
        val fixture = fixture()
        fixture.model.open(levelId)
        fixture.setCode("$solution // mine")
        fixture.runAndAwait()

        // The model holds progress in memory; the store is what persists it. Reading it
        // back through a second store proves the file the game wrote is the file it reads.
        val store = ProgressStore(fixture.saveFile)
        assertTrue(store.save(fixture.model.state.value.progress))
        assertTrue(store.load()[levelId].lastSolution!!.contains("// mine"))
    }

    @Test
    fun `progress from a previous session is loaded`() {
        val dir = Files.createTempDirectory("replikube-test")
        dir.toFile().deleteOnExit()
        val save = dir.resolve("progress.json")
        ProgressStore(save).save(
            Progress(
                levels = mapOf(
                    levelId to LevelProgress(levelId, solved = true, stars = Stars.GOLD, lastSolution = "kept"),
                ),
            ),
        )

        val model = fixture(saveFile = save).model
        assertEquals(3, model.state.value.progress.totalStars)
        assertEquals(setOf(levelId), model.state.value.progress.solvedIds)

        // Reopening restores the player's own code, not the starter: the starter is only
        // for a level with nothing saved against it.
        model.open(levelId)
        assertEquals("kept", model.state.value.code)
        assertEquals("kept", model.starterCode(model.state.value.levels.first { it.id == levelId }))
    }

    @Test
    fun `reset restores the starter and discards the player's edits`() {
        val fixture = fixture()
        fixture.model.open(levelId)
        fixture.setCode("garbage")
        fixture.model.resetCode()
        assertEquals(
            SolutionTemplates.forLevel(LevelCatalog.readMetadata(levelId)),
            fixture.model.state.value.code,
        )
        assertFalse(fixture.model.state.value.code.contains("garbage"))
    }

    @Test
    fun `the reference solution is only revealed after the level is beaten`() {
        val fixture = fixture()
        fixture.model.open(levelId)
        assertFalse(fixture.model.state.value.canRevealReference)

        val starter = fixture.model.state.value.code
        fixture.model.revealReference()
        assertEquals(starter, fixture.model.state.value.code, "revealing should be a no-op before a solve")

        fixture.setCode(solution)
        fixture.runAndAwait()
        assertTrue(fixture.model.state.value.canRevealReference)

        fixture.model.revealReference()
        assertEquals(fixture.model.state.value.level!!.reference, fixture.model.state.value.code)
    }

    @Test
    fun `switching levels clears the previous result`() {
        val fixture = fixture()
        fixture.model.open(levelId)
        fixture.setCode(solution)
        fixture.runAndAwait()
        assertNotNull(fixture.model.state.value.result)

        val second = fixture.model.state.value.levels.first { it.id != levelId }
        fixture.model.start(second.id)
        fixture.model.await({ it.level?.id == second.id }, "the second level")

        assertNull(fixture.model.state.value.result)
        assertEquals(RunOutcome.Idle, fixture.model.state.value.outcome)
    }

    @Test
    fun `a level with no readable shape data reports why rather than showing nothing`() {
        // Reachable only by a hand-edited or truncated level file: metadata whose matrix does
        // not match its declared size. It has to fail visibly, because the alternative is a
        // blank pane with no explanation — and running is a no-op, not a crash, because there
        // is no target to grade against.
        val dir = Files.createTempDirectory("replikube-test")
        dir.toFile().deleteOnExit()
        // The level handed over is a *valid* one: `Level` refuses to be constructed with a
        // matrix the wrong length, so a broken one cannot be built here at all. The failure is
        // simulated at the only place it can actually occur — the catalogue's load, which is
        // what turns the stored matrix into a grid.
        val model = GameModel(
            catalog = object : LevelCatalog() {
                override val levels = listOf(LevelCatalog.readMetadata(levelId))
                override suspend fun load(id: String): Result<LoadedLevel> =
                    Result.failure(IllegalStateException("matrix is 1 cell, size says 27"))
            },
            runner = Stub { RunResult.Success(target) },
            progressStore = ProgressStore(dir.resolve("progress.json")),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        val state = model.open(levelId)
        assertNotNull(state.levelError)
        assertNull(state.level)

        model.run()
        assertEquals(RunOutcome.Idle, model.state.value.outcome)
    }

    @Test
    fun `an unknown level id is ignored rather than crashing`() {
        val model = fixture().model
        model.start("no-such-level")
        assertNull(model.state.value.level)
        assertNull(model.state.value.currentLevelId)
    }

    @Test
    fun `toggling the hint does not disturb the editor`() {
        val fixture = fixture()
        fixture.model.open(levelId)
        fixture.setCode(solution)
        fixture.model.toggleHint()
        assertTrue(fixture.model.state.value.hintVisible)
        assertEquals(solution, fixture.model.state.value.code)
        fixture.model.toggleHint()
        assertFalse(fixture.model.state.value.hintVisible)
    }
}
