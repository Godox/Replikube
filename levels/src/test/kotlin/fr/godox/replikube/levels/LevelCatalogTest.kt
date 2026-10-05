package fr.godox.replikube.levels

import fr.godox.replikube.core.Verifier
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.core.render.AsciiRenderer
import fr.godox.replikube.core.render.paletteLegend
import fr.godox.replikube.dsl.Palette
import fr.godox.replikube.scripting.InProcessScriptRunner
import fr.godox.replikube.scripting.RunResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Build-time validation of the whole campaign.
 *
 * A level's target is a stored matrix and its reference solution is text beside it, so
 * *nothing here needs a compiler to open a level* — but the two can disagree, and that is
 * what these tests exist to prevent. The guarantee used to hold by construction; it is now
 * a test, which is a weaker guarantee in principle and a stronger one in practice, because
 * it fails on the machine that authored the level instead of in front of a player.
 *
 * Compiling 20 real solutions is slow (tens of seconds). That is the price of the guarantee,
 * and it is a price paid once per build rather than once per playthrough — and once per
 * *launch* no longer, which was the point.
 */
class LevelCatalogTest {

    private val catalog = LevelCatalog()

    /**
     * Every level on the classpath, in *discovery* order.
     *
     * Deliberately [LevelCatalog.resourceIds] and not [catalog]'s own `ids`: this suite is
     * about the files, and wants to fail on a level that exists but was not adopted, not only
     * on one the catalogue happens to have ordered. Ordering is asserted separately, against
     * `catalog.levels`.
     */
    private val ids = LevelCatalog.resourceIds()

    @Test
    fun `every level id has metadata and a reference solution`() {
        assertEquals(20, ids.size, "the v1 curriculum is 20 levels, found: $ids")
        for (id in ids) {
            val level = LevelCatalog.readMetadata(id)
            assertEquals(id, level.id, "metadata id does not match its file name")
            assertTrue(level.title.isNotBlank(), "$id has no title")
            assertTrue(level.hint.isNotBlank(), "$id has no hint")
            assertTrue(level.par > 0, "$id has a non-positive par")
            assertTrue(level.reference.isNotBlank(), "$id's reference solution is empty")
        }
    }

    @Test
    fun `difficulty numbers are consecutive and unique`() {
        val difficulties = catalog.levels.map { it.difficulty }
        assertEquals(difficulties.sorted(), difficulties, "levels are not in curriculum order")
        assertEquals((1..ids.size).toList(), difficulties.sorted(), "difficulty must be 1..N with no gaps")
    }

    @Test
    fun `ids are unique and filenames are in kebab case`() {
        assertEquals(ids.size, ids.toSet().size, "duplicate level id")
        for (id in ids) {
            assertTrue(id.matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")), "'$id' should be kebab-case")
        }
    }

    /**
     * The matrix is the shape, and its size has to agree with the declared grid.
     *
     * Checked here even though `Level.init` already refuses a mismatched pair: that check
     * runs during *deserialisation*, and a level that fails to deserialise is silently
     * dropped from the catalogue. This asserts instead that all 20 survived, so a broken
     * matrix shows up as one named level rather than as a campaign quietly one shorter.
     */
    @Test
    fun `every level's target matrix matches its declared size`() {
        val failures = ids.mapNotNull { id ->
            val level = LevelCatalog.readMetadata(id)
            val expected = level.size.voxelCount
            if (level.target.size != expected) "$id: matrix is ${level.target.size}, size needs $expected" else null
        }
        assertEquals(emptyList(), failures, "malformed target matrices:\n${failures.joinToString("\n")}")
        assertEquals(ids.size, catalog.levels.size, "a level was dropped during deserialisation")
    }

    @Test
    fun `every level carries its reference solution inline`() {
        // The solution is text in the level's own file, not a separate resource: there is no
        // filename to drift out of sync, and nothing to go missing from the classpath.
        //
        // Deliberately asserts almost nothing beyond "not blank". A body may legitimately open
        // with a comment, a `val`, or a helper function, and half the curriculum does; and a
        // line-count ceiling would only ever be tuned to whatever the levels happen to be,
        // which makes it a rubber stamp rather than a check. The real test of a reference
        // solution is `every reference solution still produces exactly its stored target`,
        // which compiles all twenty.
        for (id in ids) {
            assertTrue(
                LevelCatalog.readMetadata(id).reference.isNotBlank(),
                "$id has no reference solution",
            )
        }
    }

    @Test
    fun `levels are small enough to read on screen`() {
        for (id in ids) {
            val size = LevelCatalog.readMetadata(id).size
            assertTrue(size.voxelCount <= 9 * 9 * 9, "$id is ${size.voxelCount} voxels, too many to read")
        }
    }

    @Test
    fun `unknown level ids fail rather than return an empty level`() {
        val result = runBlocking { catalog.load("no-such-level") }
        assertTrue(result.isFailure, "expected a failure for an unknown id")
        assertTrue(result.exceptionOrNull() is NoSuchLevelException)
    }

    /** Every level must load and be a plausible puzzle. */
    @Test
    fun `every level loads and declares a real shape`() {
        val failures = mutableListOf<String>()
        for (id in ids) {
            val loaded = runBlocking { catalog.load(id) }.getOrElse {
                failures += "$id: ${it.message}"
                continue
            }
            val grid = loaded.target
            if (grid.size != loaded.level.size) {
                failures += "$id: target is ${grid.size}, metadata says ${loaded.level.size}"
            }
            if (grid.solidCount == 0) {
                failures += "$id: target is empty, which is not a puzzle"
            }
            if (!grid.hasValidColors()) {
                failures += "$id: target contains an out-of-range colour at ${grid.firstInvalidCell()}"
            }
        }
        assertEquals(emptyList(), failures, "levels failed validation:\n${failures.joinToString("\n")}")
    }

    /**
     * The guarantee that replaced compiling at load time.
     *
     * The target is stored data now, so it *can* disagree with the reference solution sitting
     * beside it in the same file. This compiles every one and asserts an exact match, which
     * means a level can never be unsolvable by its own account, and a shape can never drift
     * from the solution that documents it.
     *
     * Slow, by nature: twenty real compiles. Paid once per build rather than once per launch.
     * Regenerate the matrices with `./gradlew :levels:test --tests '*GenerateTargetsTest*'
     * -Dreplikube.regenerate=true` after editing a reference solution.
     */
    @Test
    fun `every reference solution still produces exactly its stored target`() {
        val runner = InProcessScriptRunner()
        val failures = mutableListOf<String>()
        for (id in ids) {
            val loaded = runBlocking { catalog.load(id) }.getOrThrow()
            val result = runBlocking { runner.run(loaded.reference, loaded.level.size) }
            when (result) {
                is RunResult.Failure ->
                    failures += "$id: reference solution did not run: ${result.diagnostic}"

                is RunResult.Success -> {
                    if (result.grid != loaded.target) {
                        val (want, got) = describeMismatch(loaded.target, result.grid)
                        failures += "$id: reference solves to a different shape — $want, target is $got"
                    }
                }
            }
        }
        assertEquals(
            emptyList(),
            failures,
            "reference solutions disagree with their stored targets " +
                "(re-run GenerateTargetsTest with -Dreplikube.regenerate=true):\n" +
                failures.joinToString("\n"),
        )
    }

    /**
     * A human-readable account of where two grids differ.
     *
     * "does not match" is useless when the fix is regenerating a matrix: the point is to see
     * *which* voxels, so the author can tell an intentional shape change from a transposed or
     * off-by-one matrix.
     */
    private fun describeMismatch(target: VoxelGrid, actual: VoxelGrid): Pair<String, String> {
        val diffs = target.allCoords().filter { target[it] != actual[it] }
        val shown = diffs.take(4).joinToString { "${it.x},${it.y},${it.z}" }
        val more = if (diffs.size > 4) " (+${diffs.size - 4} more)" else ""
        return "solution differs at ${shown}$more" to "the stored matrix"
    }

    @Test
    fun `the stored target is its own solution`() {
        // Structural: a target that does not match itself would mean the verifier is broken,
        // since the player is graded against exactly this grid.
        for (id in ids) {
            val loaded = runBlocking { catalog.load(id) }.getOrThrow()
            val graded = assertIs<Verifier.Result.Graded>(loaded.verify(loaded.target), "$id")
            assertTrue(graded.diff.isSolved, "$id does not verify against itself: ${graded.diff.errorCount} voxels differ")
        }
    }

    @Test
    fun `targets are cached after the first load`() {
        val first = runBlocking { catalog.load("hello-layers") }.getOrThrow()
        val second = runBlocking { catalog.load("hello-layers") }.getOrThrow()
        assertTrue(first === second, "expected the cached level instance")
    }

    @Test
    fun `levels use more than one colour, so the palette is exercised`() {
        val colourful = ids.count { id ->
            runBlocking { catalog.load(id) }.getOrThrow().target.colorsUsed().size > 2
        }
        assertTrue(colourful >= 6, "only $colourful levels use 3+ colours; the palette is under-used")
    }

    /**
     * Not an assertion but the authoring tool: prints every level as text.
     *
     * Run with `./gradlew :levels:test --tests '*LevelCatalogTest.show*' -i`. This is how the
     * curriculum is actually tuned — comparing shapes in the terminal is far quicker than
     * opening them in the viewport one at a time.
     */
    @Test
    fun showEveryLevel() {
        for (id in ids) {
            val loaded = runBlocking { catalog.load(id) }.getOrThrow()
            println()
            println("=".repeat(72))
            println("${loaded.level.difficulty}. ${loaded.level.title}  ($id)  ${loaded.level.size}")
            println("par ${loaded.level.par}   hint: ${loaded.level.hint}")
            println("target: ${loaded.target.solidCount} voxels, colours ${loaded.target.colorsUsed().map(Palette::nameOf)}")
            println(AsciiRenderer.render(loaded.target))
            println("legend: ${paletteLegend(loaded.target)}")
        }
    }
}