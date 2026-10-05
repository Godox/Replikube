package fr.godox.replikube.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import fr.godox.replikube.app.progress.FileProgressStorage
import fr.godox.replikube.app.progress.ProgressStore
import fr.godox.replikube.app.ui.GameScreen
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.LoadedLevel
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette
import fr.godox.replikube.levels.LevelCatalog
import fr.godox.replikube.scripting.RunResult
import fr.godox.replikube.scripting.ScriptRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Composes the whole game screen with no window and no display.
 *
 * The per-level renders in `ViewportRenderTest` prove the projection; this proves the
 * *layout*: that the two viewports both appear, that the level list, editor gutter and feedback
 * panel all paint, and that a state the UI has to survive — a level whose shape data cannot be
 * read — produces a message instead of an exception.
 *
 * It is also the only check that can catch a crash in a composable. Everything else in the
 * suite runs without a player in front of it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GameScreenRenderTest {

    /**
     * The level every test here opens: the first one in curriculum order, 3x3x3 and solid in
     * every cell.
     *
     * Curriculum order — the catalogue's own — and not [LevelCatalog.resourceIds], which is
     * whatever order the classpath enumerates in. Those differ: the enumerator is
     * alphabetical, so "the first level" read as `arch`, a 7x7x7 shape, while the screen
     * under test opened `hello-layers`. A test that names the wrong level still renders, and
     * still draws pixels, so it fails on its assertion with nothing pointing at the cause.
     */
    private val firstLevelId = LevelCatalog().ids.first()

    /**
     * The real target of [firstLevelId], as the player would have to produce it.
     *
     * Read from the shipped matrix rather than drawn here. An invented three-layer tower is
     * *almost* `hello-layers` — the same three colours, the same layers — which is exactly the
     * trap: it looked like the level while disagreeing with it on which colour sits where, so
     * a test asking for a solve was measuring a shape the game never shows.
     */
    private fun realTarget(size: GridSize): VoxelGrid {
        val level = LevelCatalog.readMetadata(firstLevelId)
        check(level.size == size) { "these tests run against $firstLevelId, which is ${level.size}, not $size" }
        return runBlocking { LevelCatalog().load(firstLevelId) }.getOrThrow().target
    }

    /** A grid that is nothing like the target, for tests that need the stub to be wrong. */
    private fun tower(size: GridSize): VoxelGrid = VoxelGrid.build(size) { _, y, _ ->
        when (y) {
            0 -> Palette.RED
            1 -> Palette.YELLOW
            2 -> Palette.GREEN
            else -> Palette.EMPTY
        }
    }

    /**
     * @param player what the stub answers for the player's code. Defaults to the real target,
     *   so the default state of a test is "the player got it right".
     */
    private fun model(
        player: (GridSize) -> RunResult = { RunResult.Success(realTarget(it)) },
    ): GameModel {
        val dir = Files.createTempDirectory("replikube-test").also { it.toFile().deleteOnExit() }
        val stub = object : ScriptRunner {
            override suspend fun run(source: String, size: GridSize, timeoutMillis: Long): RunResult =
                player(size)
        }
        return GameModel(
            // Real catalogue: targets are stored matrices, so there is nothing to stub. The
            // stub runner answers the player's code only.
            catalog = LevelCatalog(),
            runner = stub,
            progressStore = ProgressStore(FileProgressStorage(dir.resolve("progress.json"))),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    /**
     * A screen being drawn, held open so more can be composed into it.
     *
     * Opening one is what starts the level load: [GameScreen] opens the first unsolved
     * level from a `LaunchedEffect`, so nothing can be awaited before the scene has been
     * rendered at least once.
     */
    private inner class Screen(val model: GameModel) {
        private val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) { GameScreen(model = this@Screen.model) }

        /** Composes until the model has a level, or has said it cannot build one. */
        fun openLevel(what: String): Screen = apply {
            scene.render()
            val settled = runBlocking {
                withTimeoutOrNull(5_000) { model.state.first { it.level != null || it.levelError != null } }
                    ?: error("timed out waiting for $what")
            }
            // The stub answers with a grid it was handed the size for, so a target that
            // does not match its level means the harness lied — and would otherwise show up
            // much later as a confusing frame.
            settled.level?.let {
                check(it.target.size == it.level.size) {
                    "target is ${it.target.size} but level ${it.id} is ${it.level.size}"
                }
            }
        }

        /**
         * Puts [code] in the editor and runs it to completion.
         *
         * The code is passed in rather than read from the editor, because for the tutorial
         * levels the starter *is* the reference solution — the stub would answer from its
         * reference branch and every assertion about the player's run would be vacuous.
         */
        fun runOnce(code: String = ATTEMPT): Screen = apply {
            model.setCode(code)
            model.run()
            runBlocking {
                withTimeoutOrNull(5_000) { model.state.first { it.outcome !is RunOutcome.Running } }
            }
        }

        /** Composes a few more times, then writes a PNG and prints a text silhouette. */
        fun snapshot(name: String): BufferedImage {
            repeat(3) { scene.render() }
            val png = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG)).bytes
            val image = assertNotNull(ImageIO.read(ByteArrayInputStream(png)), "could not decode the frame")
            File(OUT_DIR, "$name.png").writeBytes(png)
            println()
            println("=== $name, $WIDTH×$HEIGHT ===")
            println(silhouette(image))
            return image
        }
    }

    private fun screen(model: GameModel) = Screen(model)

    @Test
    fun `renders the play screen`() {
        val frame = screen(model()).openLevel("the first level").snapshot("screen-play")
        assertTrue('#' in silhouette(frame), "nothing was drawn at all")
    }

    @Test
    fun `renders an unsolved level with the diff feedback`() {
        val model = model(
            player = {
                // Two of the target's three layers, so the panel has counts to show and
                // the viewport has something to highlight.
                RunResult.Success(VoxelGrid.build(it) { _, y, _ ->
                    if (y <= 1) Palette.RED else Palette.EMPTY
                })
            },
        )
        screen(model).openLevel("the first level").runOnce().snapshot("screen-unsolved")
        assertTrue(model.state.value.outcome is RunOutcome.Unsolved)
    }

    /**
     * Both panes are on screen, and they are different.
     *
     * The requirement behind this is that the player compares their solution against the
     * target, which is impossible if either pane is hidden or if the two are drawn
     * identically. Asserted on the rendered pixels rather than on the composable tree: a
     * pane that is laid out but clipped to nothing, or drawn behind the other, still exists
     * in the tree and still passes a structural check.
     */
    @Test
    fun `the target and the player's solution are both drawn, and they differ`() {
        val model = model(
            player = {
                // Two of the target's three layers, so the panes are visibly different.
                RunResult.Success(VoxelGrid.build(it) { _, y, _ ->
                    if (y <= 1) Palette.RED else Palette.EMPTY
                })
            },
        )
        val frame = screen(model).openLevel("the first level").runOnce().snapshot("screen-both-panes")

        // The viewport column sits between the level list and the editor. Sampled as two
        // horizontal bands: the target pane above, the solution pane below.
        val (upper, lower) = viewportBands(frame)
        assertTrue(brightPixels(upper) > 400, "the target pane looks empty")
        assertTrue(brightPixels(lower) > 400, "the solution pane looks empty")
        assertTrue(
            brightPixels(upper) != brightPixels(lower),
            "both panes drew the same amount: the player's grid is not being shown",
        )
    }

    @Test
    fun `renders a solved level`() {
        val model = model()
        screen(model).openLevel("the first level").runOnce().snapshot("screen-solved")
        assertTrue(model.state.value.outcome is RunOutcome.Solved)
    }

    @Test
    fun `renders a level whose shape data cannot be read`() {
        // Was "a level whose reference solution will not build", back when the target came
        // from compiling that solution. The target is data now, so the failure is a matrix
        // that cannot be turned into a grid — and the screen still has to survive it.
        //
        // The level handed over is a *valid* one: `Level` refuses to hold a matrix the wrong
        // length, so a broken one cannot be built here. The failure is simulated at the only
        // place it can actually occur — the catalogue's load, which is the step that turns the
        // stored matrix into a grid.
        val dir = Files.createTempDirectory("replikube-test").also { it.toFile().deleteOnExit() }
        val level = LevelCatalog.readMetadata(firstLevelId)
        val model = GameModel(
            catalog = object : LevelCatalog() {
                override val levels = listOf(level)
                override suspend fun load(id: String): Result<LoadedLevel> =
                    Result.failure(IllegalStateException("matrix is 1 cell, size says ${level.size}"))
            },
            runner = object : ScriptRunner {
                override suspend fun run(source: String, size: GridSize, timeoutMillis: Long) =
                    RunResult.Success(tower(size))
            },
            progressStore = ProgressStore(FileProgressStorage(dir.resolve("progress.json"))),
            scope = CoroutineScope(Dispatchers.Unconfined),
        )

        screen(model).openLevel(level.id).snapshot("screen-level-error")

        assertNull(model.state.value.level)
        assertNotNull(model.state.value.levelError)
    }

    /**
     * The two viewport bands, target above and solution below.
     *
     * The column spans from just after the level list to just before the editor, and the
     * two panes split it about half way. These bounds are fixed rather than measured
     * because the layout is fixed too — a test that derived them from the rendered image
     * would be testing its own detector.
     */
    private fun viewportBands(image: BufferedImage): Pair<BufferedImage, BufferedImage> {
        val left = image.width * 22 / 100
        val right = image.width * 62 / 100
        val top = image.height * 16 / 100
        val bottom = image.height * 86 / 100
        val middle = (top + bottom) / 2

        fun band(fromY: Int, toY: Int) = image.getSubimage(left, fromY, right - left, toY - fromY)

        return band(top, middle - BAND_GAP) to band(middle + BAND_GAP, bottom)
    }

    /** How many pixels in [image] are bright enough to be geometry rather than backdrop. */
    private fun brightPixels(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                val lum = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)
                if (lum > 240) count++
            }
        }
        return count
    }

    /** Reduces an image to a `#`/`+`/`.` grid, brightest pixel per cell wins. */
    private fun silhouette(image: BufferedImage, cols: Int = 150, rows: Int = 44): String {
        val cellW = (image.width / cols).coerceAtLeast(1)
        val cellH = (image.height / rows).coerceAtLeast(1)
        return buildString {
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    var peak = 0
                    for (y in row * cellH until minOf((row + 1) * cellH, image.height)) {
                        for (x in col * cellW until minOf((col + 1) * cellW, image.width)) {
                            val rgb = image.getRGB(x, y)
                            val lum = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)
                            if (lum > peak) peak = lum
                        }
                    }
                    append(if (peak > 480) '#' else if (peak > 240) '+' else '.')
                }
                append('\n')
            }
        }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720

        /**
         * Rows skipped either side of the split between the two panes.
         *
         * The band between them holds the second pane's caption and the `8.dp` gutter, so
         * sampling right up to the midpoint would measure the caption rather than cubes.
         */
        const val BAND_GAP = 24

        val OUT_DIR = File("build/renders").apply { mkdirs() }

        /**
         * A **body**, unlike any reference solution, so the stub's player branch answers.
         *
         * Deliberately wrong as well: it fills the two lower layers where the target fills
         * three, so the lower pane differs from the upper one and a screenshot shows both.
         */
        const val ATTEMPT = "return if (y <= 1) RED else null"
    }
}
