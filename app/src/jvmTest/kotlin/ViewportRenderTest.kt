package fr.godox.replikube.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import fr.godox.replikube.app.render.Cutaway
import fr.godox.replikube.app.render.Point
import fr.godox.replikube.app.render.Projection
import fr.godox.replikube.app.render.Rect
import fr.godox.replikube.app.render.pick
import fr.godox.replikube.app.ui.drawScene
import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette
import fr.godox.replikube.levels.LevelCatalog
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Renders real levels to PNG files, so the isometric painter can be looked at.
 *
 * A renderer is the one kind of code where "the tests pass" says almost nothing: a cube
 * drawn behind another cube is a passing test and a broken picture. These render every
 * level's target to `app/build/renders/` with no window and no display, which makes the
 * projection reviewable in a diff and catchable by eye.
 *
 * Run with:
 *
 * ```
 * ./gradlew :app:test --tests '*ViewportRenderTest*' -i
 * open app/build/renders/
 * ```
 */
@OptIn(ExperimentalComposeUiApi::class)
class ViewportRenderTest {

    private val outDir = File("build/renders").apply { mkdirs() }

    @Test
    fun `renders every level's target and the player's attempt at it`() = runBlocking {
        val catalog = LevelCatalog()
        val written = mutableListOf<File>()

        for (level in catalog.levels) {
            val loaded = catalog.load(level.id).getOrThrow()
            val projection = Projection(loaded.level.size).apply {
                zoom = fitZoom(Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()))
            }

            // The player's "attempt": the target shifted one voxel along x. It exercises the
            // marked path — every target voxel is missing, one is extra — without needing a
            // real run.
            val attempt = shiftX(loaded.target)
            val marked = attempt.allCoords().filter { attempt[it] != loaded.target[it] }.toSet()

            written += render(level.id, loaded.level.size, projection, attempt, marked)
            written += render("${level.id}-target", loaded.level.size, projection, loaded.target, emptySet())
        }

        assertEquals(catalog.levels.size * 2, written.size, "expected two renders per level")
        assertTrue(written.all { it.length() > 0 }, "every render should be a non-empty PNG")
        println("wrote ${written.size} renders to ${outDir.absolutePath}")
    }

    /**
     * Prints a text silhouette of a rendered frame.
     *
     * A PNG cannot be diffed in a terminal, and "it compiles" says nothing about whether
     * the projection is right. Reducing the real rendered pixels to `#`/`.` is crude but it
     * is the rendered output, not a second drawing of it — so if the isometric maths is
     * wrong, this is where it shows.
     */
    @Test
    fun `prints an ascii silhouette of a rendered level`() = runBlocking {
        val catalog = LevelCatalog()
        for (id in listOf("hello-layers", "spiral", "cathedral")) {
            val loaded = catalog.load(id).getOrThrow()
            val projection = Projection(loaded.level.size).apply {
                zoom = fitZoom(Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()))
            }
            val pixels = renderPixels(loaded.level.size, projection, loaded.target, emptySet())
            println()
            println("=== $id (${loaded.level.title}) ===")
            println(silhouette(pixels))
        }
    }

    /**
     * The hover highlight lands on a voxel that is actually visible.
     *
     * The end-to-end statement of what picking is for: pick a voxel, draw the frame with it
     * hovered, and confirm the rendered pixels actually changed. Without this, `pick` could
     * be self-consistently wrong — agreeing with itself about which cube is nearest while
     * the painter draws a different one — and every assertion in `PickingTest` would still
     * pass.
     */
    @Test
    fun `the hover highlight paints over the voxel picking named`() {
        val size = GridSize(3, 3, 3)
        val grid = VoxelGrid.build(size) { _, y, _ -> if (y >= 0) Palette.CYAN else Palette.EMPTY }
        val projection = Projection(size).apply {
            zoom = fitZoom(Rect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()))
        }

        val target = Coord(0, 1, 0)
        val point = projection.project(target.x + 0.5f, target.y + 0.5f, target.z + 0.5f)
        assertEquals(target, projection.pick(grid, point), "the named voxel must be the hovered one")

        val plain = renderPixels(size, projection, grid, emptySet())
        val hovered = renderPixels(size, projection, grid, emptySet(), hovered = target)

        // The highlight is a near-white fill, so it must be much brighter than the cyan
        // cube it covers. Counted over the whole frame because the exact pixel depends on
        // where the face lands.
        val plainBright = countBright(plain)
        val hoveredBright = countBright(hovered)
        assertTrue(
            hoveredBright > plainBright + 500,
            "hovering $target should paint a white face: bright pixels went " +
                "$plainBright -> $hoveredBright",
        )
    }

    /** How many pixels are brighter than a white highlight ever gets. */
    private fun countBright(image: BufferedImage): Int {
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val rgb = image.getRGB(x, y)
                if (((rgb shr 16) and 0xFF) > 235 &&
                    ((rgb shr 8) and 0xFF) > 235 &&
                    (rgb and 0xFF) > 235
                ) {
                    count++
                }
            }
        }
        return count
    }

    private fun renderPixels(
        size: GridSize,
        projection: Projection,
        grid: VoxelGrid,
        marked: Set<Coord>,
        cutaway: Cutaway = Cutaway.Off,
        hovered: Coord? = null,
    ): BufferedImage {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                drawScene(size, grid, projection, marked, cutaway, hovered)
            }
        }
        // Round-tripped through PNG and decoded with ImageIO rather than read back out of
        // Skia directly: ImageIO is a stable JDK API, and the encoded bytes are exactly
        // what the file on disk contains, so the text dump describes the saved picture.
        val png = checkNotNull(scene.render().encodeToData(EncodedImageFormat.PNG)).bytes
        scene.close()
        return assertNotNull(ImageIO.read(ByteArrayInputStream(png)))
    }

    /** Reduces an image to a `#`/`+`/`.` grid, brightest pixel per cell wins. */
    private fun silhouette(image: BufferedImage, cols: Int = 110, rows: Int = 40): String {
        val cellW = image.width / cols
        val cellH = image.height / rows
        return buildString {
            for (row in 0 until rows) {
                for (col in 0 until cols) {
                    // Max over the cell, not a single sample: a cube edge can be two
                    // pixels wide, and one sampled pixel per cell draws holes in it.
                    var peak = 0
                    for (y in row * cellH until (row + 1) * cellH) {
                        for (x in col * cellW until (col + 1) * cellW) {
                            val rgb = image.getRGB(x, y)
                            val lum = ((rgb shr 16) and 0xFF) + ((rgb shr 8) and 0xFF) + (rgb and 0xFF)
                            if (lum > peak) peak = lum
                        }
                    }
                    // The backdrop is a dark blue-grey; anything brighter is geometry.
                    append(if (peak > 500) '#' else if (peak > 260) '+' else '.')
                }
                append('\n')
            }
        }
    }

    private fun render(
        name: String,
        size: GridSize,
        projection: Projection,
        grid: VoxelGrid,
        marked: Set<Coord>,
    ): File {
        val scene = ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            Canvas(Modifier.fillMaxSize()) {
                drawScene(size, grid, projection, marked)
            }
        }
        val image = scene.render()
        val file = File(outDir, "$name.png")
        val data = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)) { "PNG encoding failed for $name" }
        file.writeBytes(data.bytes)
        scene.close()
        return file
    }

    /** The same grid with every voxel moved one step along +x. */
    private fun shiftX(grid: VoxelGrid): VoxelGrid = VoxelGrid.build(grid.size) { x, y, z ->
        grid[x - 1, y, z]
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
    }
}