package fr.godox.replikube.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import fr.godox.replikube.app.render.Cutaway
import fr.godox.replikube.app.render.Face
import fr.godox.replikube.app.render.PaletteColors
import fr.godox.replikube.app.render.Point
import fr.godox.replikube.app.render.Projection
import fr.godox.replikube.app.render.Quad
import fr.godox.replikube.app.render.Rect
import fr.godox.replikube.app.render.pick
import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette

/**
 * Draws one voxel grid in isometric projection, with drag to orbit and scroll to zoom.
 *
 * ### Why a hand-rolled painter
 *
 * A scene-graph engine would be the obvious choice for a 3D game, but it would add a
 * native dependency, an opaque render loop and its own windowing to a game whose entire 3D
 * content is axis-aligned cubes on a fixed grid. Painter's algorithm over such cubes is
 * exact — no depth buffer, no overdraw subtleties — and the result is a file of pure
 * geometry that a unit test can exercise with no toolkit at all.
 *
 * ### Every grid is drawn in full colour
 *
 * The target and the player's own solution are both drawn as solid, palette-coloured cubes.
 * An earlier design drew the target as a translucent ghost underneath the player's work, on
 * the argument that a solid target reads as an answer being handed over. That argument does
 * not survive the game having two panes: with the target above and the solution below, the
 * player is comparing two pictures, and a ghost is not comparable with a solid. A ghost is
 * also the wrong shape of information — it says *some cube is here* when the puzzle is about
 * *which cube and what colour*.
 *
 * Feedback that used to be carried by the ghost is carried by the player's pane instead:
 * voxels that are wrong are outlined, in their own colour still, so the pane stays a
 * faithful picture of the code that ran.
 *
 * @param grid the voxels to draw. Every renderable voxel is painted.
 * @param camera the shared camera, so two viewports stay in lockstep.
 * @param marked voxels to outline, for whatever this pane is reporting on.
 * @param cutaway voxels to omit, so a hollow shape can be seen into. Shared between panes
 *   for the same reason the camera is: two views of different extents cannot be compared.
 * @param onHover called with the voxel under the pointer, or `null` when there is none.
 */
@Composable
fun VoxelViewport(
    grid: VoxelGrid,
    size: GridSize,
    camera: OrbitState,
    modifier: Modifier = Modifier,
    marked: Set<Coord> = emptySet(),
    cutaway: Cutaway = Cutaway.Off,
    onHover: (Coord?) -> Unit = {},
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var hovered by remember { mutableStateOf<Coord?>(null) }
    var cursor by remember { mutableStateOf(Point(0f, 0f)) }
    var dragging by remember { mutableStateOf(false) }

    // Refit when the window or the grid changes, but only then. Refitting on every
    // recomposition would throw away the camera the player just set — including on every
    // frame of a drag, which would make the grid breathe instead of turn.
    remember(canvasSize, size, camera.yaw, camera.pitch) {
        if (canvasSize != IntSize.Zero) {
            camera.fit(size, canvasSize.toRect())
        }
        true
    }

    // Recomputed whenever the camera moves. Reading the snapshot values here is what makes
    // a drag repaint: the values are read during composition, so writing them invalidates
    // both the composition and the draw.
    val projection = camera.projection(size)
    // Clamped here rather than at each call site: `grid.size` and `size` are the same
    // thing, and a cutaway left over from a larger level would otherwise slice the new one
    // by a number of layers that means nothing on it.
    val cut = remember(cutaway, size) { cutaway.clampedTo(size) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(size, grid, cut) {
                detectDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = {
                        dragging = false
                        // Re-pick against the camera the drag actually ended at, so a drag
                        // that finishes over a cube does not leave the highlight on whatever
                        // the pointer passed over on its way there.
                        hovered = camera.projection(size).pick(grid, cursor, cut)
                    },
                    onDragCancel = { dragging = false },
                ) { change, dragAmount ->
                    change.consume()
                    camera.orbit(-dragAmount.x * DRAG_SCALE, -dragAmount.y * DRAG_SCALE)
                }
            }
            .pointerInput(size, grid, projection) {
                // Scroll is handled in its own pointer pass rather than alongside the drag,
                // because a wheel event must not also rotate the camera.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val scroll = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
                        if (scroll != 0f) {
                            camera.zoomBy(scroll)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(size, grid, canvasSize, cut) {
                // Hover is its own pass, at the Final stage so it runs after the drag
                // gesture has had its chance to consume the event. Sharing one handler with
                // `detectDragGestures` would mean a single pointerInput block deciding
                // between rotating and hovering, and the two need different lifetimes: a
                // drag handler is cancelled on release, a hover handler must outlive it.
                //
                // The camera is read through [camera] on every event rather than captured,
                // because a pointer coroutine is not re-launched when the camera moves: a
                // captured [Projection] would keep picking against the angle the pointer
                // handler was created with, so the highlight would slide off the cube it
                // belonged to as soon as the player rotated.
                val centre = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            PointerEventType.Move -> {
                                // Skipped while dragging, and not just to save the work:
                                // during an orbit the cube under the cursor changes every
                                // frame, so a highlight would strobe. Cleared instead, so
                                // the cube the drag started on is not left ringed.
                                if (dragging) {
                                    if (hovered != null) {
                                        hovered = null
                                        onHover(null)
                                    }
                                    continue
                                }
                                val point = (change.position - centre).toPoint()
                                val hit = camera.projection(size).pick(grid, point, cut)
                                cursor = point
                                // Only publish a change. Re-assigning an equal value is
                                // harmless for Compose, but calling `onHover` on every mouse
                                // move would wake the parent composable continuously.
                                if (hit != hovered) {
                                    hovered = hit
                                    onHover(hit)
                                }
                            }
                            // Leaving the canvas must clear the highlight, or the cube stays
                            // ringed while the pointer is somewhere else entirely.
                            PointerEventType.Exit -> {
                                if (hovered != null) {
                                    hovered = null
                                    onHover(null)
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            },
    ) {
        drawScene(
            size = size,
            grid = grid,
            projection = projection,
            marked = marked,
            cutaway = cut,
            hovered = hovered,
        )
    }
}

/** Converts a canvas size to the renderer's plain rectangle type. */
private fun IntSize.toRect() = Rect(0f, 0f, width.toFloat(), height.toFloat())

/** The screen centre of this canvas, where the grid is anchored. */
private fun DrawScope.centre() = Offset(this.size.width / 2f, this.size.height / 2f)

/**
 * Drops an [Offset] into the renderer's viewport-relative coordinates.
 *
 * The pointer reports canvas-absolute pixels while the projection works relative to the
 * canvas centre, so the caller subtracts the centre first. Doing that subtraction in one
 * named place is what keeps the two coordinate systems from being mixed up at a call site —
 * the mistake would not throw, it would pick the wrong voxel.
 */
private fun Offset.toPoint() = Point(x, y)

/**
 * Draws one complete frame.
 *
 * Split out from the composable so the renderer can be called directly from a test with no
 * window, no pointer input and no Compose runtime. [hovered] is relative to the canvas
 * centre, matching the projection.
 */
fun DrawScope.drawScene(
    size: GridSize,
    grid: VoxelGrid,
    projection: Projection,
    marked: Set<Coord> = emptySet(),
    cutaway: Cutaway = Cutaway.Off,
    hovered: Coord? = null,
) {
    // Qualified because `size` is the level's GridSize, which shadows DrawScope.size.
    val origin = centre()

    drawRect(Brush.verticalGradient(listOf(BACKDROP_TOP, BACKDROP_BOTTOM)))
    drawShell(size, projection, origin)
    drawVoxels(grid, projection, origin, marked, cutaway)

    // The highlight is a white fill over the cube the pointer is on, not an outline: a ring
    // on a face shared with the cube behind it reads as a seam, and the point is to answer
    // "which cube is this" at a glance. Drawn last so it is not painted over.
    //
    // The cutaway is re-checked here as well as in `pick`. A hovered voxel can become
    // hidden without any new pointer event — dragging the X slider while the pointer sits
    // still is exactly that — and a highlight on an invisible cube is worse than none.
    if (hovered != null && !cutaway.hides(hovered, grid.size) && PaletteColors.isRenderable(grid[hovered])) {
        drawCube(hovered, projection, origin, HOVER_TINT, border = HOVER_EDGE, borderWidth = 1f)
    }
}

/** Paints one cube in a flat [color], with an optional outline. */
private fun DrawScope.drawCube(
    coord: Coord,
    projection: Projection,
    origin: Offset,
    color: Color,
    border: Color,
    borderWidth: Float,
) {
    for ((face, quad) in projection.visibleFaces(coord.x.toFloat(), coord.y.toFloat(), coord.z.toFloat())) {
        val light = if (face == Face.TOP) PaletteColors.TOP_LIGHT else PaletteColors.SIDE_LIGHT
        drawFace(quad, origin, color, light, border, borderWidth)
    }
}

/** The wireframe bounding box of the level volume. */
private fun DrawScope.drawShell(size: GridSize, projection: Projection, origin: Offset) {
    val b = size.bounds
    val x0 = b.minX.toFloat()
    val x1 = (b.maxX + 1).toFloat()
    val y0 = b.minY.toFloat()
    val y1 = (b.maxY + 1).toFloat()
    val z0 = b.minZ.toFloat()
    val z1 = (b.maxZ + 1).toFloat()

    val path = Path()
    // The twelve edges, walked axis by axis. Drawing the full cage rather than only the far
    // half keeps the volume legible even when the camera is nearly edge-on.
    for (x in listOf(x0, x1)) {
        for (z in listOf(z0, z1)) {
            path.segment(origin + projection.project(x, y0, z), origin + projection.project(x, y1, z))
        }
    }
    for (x in listOf(x0, x1)) {
        for (y in listOf(y0, y1)) {
            path.segment(origin + projection.project(x, y, z0), origin + projection.project(x, y, z1))
        }
    }
    for (z in listOf(z0, z1)) {
        for (y in listOf(y0, y1)) {
            path.segment(origin + projection.project(x0, y, z), origin + projection.project(x1, y, z))
        }
    }
    drawPath(path, SHELL_COLOR, style = Stroke(width = 1f))
}

/**
 * Every filled voxel of [grid], painted back to front.
 *
 * @param marked voxels to outline, in [MARKED_TINT]. The cube keeps its own palette colour:
 *   an outline says "this one is wrong" without changing what the pane is a picture of.
 * @param cutaway voxels to omit entirely. Removing them rather than ghosting them is the
 *   point of the control: a hollow shape's interior is only legible if the cubes in front
 *   of it are gone, and a translucent cube in front of an opaque one does not let you see
 *   through to a useful depth.
 */
private fun DrawScope.drawVoxels(
    grid: VoxelGrid,
    projection: Projection,
    origin: Offset,
    marked: Set<Coord>,
    cutaway: Cutaway,
) {
    val cubes = grid.allCoords()
        .filter { PaletteColors.isRenderable(grid[it]) }
        .filterNot { cutaway.hides(it, grid.size) }
        // Painter's algorithm: ascending depth, i.e. far voxels first, so nearer ones
        // overdraw them. Sorting on the voxel centre is sufficient because unit cubes on a
        // lattice never interpenetrate.
        .sortedBy { c -> projection.depth(c.x + 0.5f, c.y + 0.5f, c.z + 0.5f) }

    for (c in cubes) {
        drawCube(
            coord = c,
            projection = projection,
            origin = origin,
            color = PaletteColors.of(grid[c]),
            border = if (c in marked) MARKED_TINT else CUBE_EDGE,
            borderWidth = if (c in marked) MARKED_EDGE else 1f,
        )
    }
}

/** Fills one face quad and strokes its silhouette. */
private fun DrawScope.drawFace(
    quad: Quad,
    origin: Offset,
    color: Color,
    light: Float,
    border: Color,
    borderWidth: Float,
) {
    val a = origin + quad.a
    val b = origin + quad.b
    val c = origin + quad.c
    val d = origin + quad.d
    val path = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        lineTo(d.x, d.y)
        close()
    }
    if (color != Color.Transparent) drawPath(path, color.shaded(light))
    if (borderWidth > 0f) drawPath(path, border, style = Stroke(width = borderWidth))
}

/** Multiplies a colour towards black for the side faces, without losing alpha. */
internal fun Color.shaded(factor: Float) = Color(
    red = (red * factor).coerceIn(0f, 1f),
    green = (green * factor).coerceIn(0f, 1f),
    blue = (blue * factor).coerceIn(0f, 1f),
    alpha = alpha,
)

private operator fun Offset.plus(p: Point) = Offset(this.x + p.x, this.y + p.y)

/**
 * Appends one straight edge.
 *
 * Compose's [Path.moveTo]/[Path.lineTo] take two floats rather than an [Offset], which
 * makes every call site a noisy `.x, .y`. This hides that.
 */
private fun Path.segment(from: Offset, to: Offset) {
    moveTo(from.x, from.y)
    lineTo(to.x, to.y)
}

private val BACKDROP_TOP = Color(0xFF171A21)
private val BACKDROP_BOTTOM = Color(0xFF0E1015)
private val SHELL_COLOR = Color(0x2EFFFFFF)
private val CUBE_EDGE = Color(0x26000000)

/** Fill for the cube under the pointer. */
private val HOVER_TINT = Color(0xFFFFFFFF)
private val HOVER_EDGE = Color(0xFFFFFFFF)

/** Outline for a voxel this pane is reporting on. */
private val MARKED_TINT = Color(0xFFFF5252)

/** Radian of rotation per pixel dragged. */
private const val DRAG_SCALE = 0.008f

/** Width of the outline drawn around a marked voxel. */
private const val MARKED_EDGE = 2f