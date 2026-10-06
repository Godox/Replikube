package fr.godox.replikube.app.render

import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.VoxelGrid

/**
 * Which voxel of [grid] is drawn under [point], if any.
 *
 * [point] is in the same relative coordinates the renderer uses — screen pixels measured
 * from the centre of the canvas, with y growing downwards.
 *
 * ### Why picking is not "invert the projection"
 *
 * The projection is a linear map from three dimensions to two, so it is not invertible:
 * a whole line of voxels projects onto every pixel. What breaks the tie is depth. The
 * renderer already computes [Projection.depth] per voxel and paints far-to-near, so the
 * voxel that survives at a given pixel is the one with the **greatest** depth among those
 * whose visible faces cover it. That is the same answer the painter's algorithm arrives at
 * visually, which is what makes a highlight land on the cube the player can actually see
 * rather than on one hidden behind it.
 *
 * Inverting the projection and rounding would instead pick the voxel along the ray through
 * the *centre* of the grid — a depth the camera is not at — so it would disagree with the
 * rendered image everywhere except dead centre. Testing the visible face quads keeps
 * picking and drawing defined by the same geometry, so they cannot drift apart.
 *
 * [cutaway] is honoured here for the same reason. Picking has to agree with painting about
 * which voxels exist on screen; a cut-away cube is not on screen, so it cannot be picked.
 */
fun Projection.pick(
    grid: VoxelGrid,
    point: Point,
    cutaway: Cutaway = Cutaway.Off,
): Coord? {
    // Rejecting on the grid's bounding box first makes the common case — a pointer over
    // the backdrop — cost one rectangle test instead of a walk over every voxel.
    if (!containsGrid(point)) return null

    var best: Coord? = null
    var bestDepth = Float.NEGATIVE_INFINITY

    for (coord in grid.allCoords()) {
        if (!grid.isSolid(coord)) continue
        // A cut-away voxel is not drawn, so it must not be pickable. Without this the
        // highlight would land on a cube the player can see straight through — picking and
        // painting would disagree about what the player is looking at, which is the exact
        // coupling `PickingTest` and the render test exist to protect.
        if (cutaway.hides(coord, grid.size)) continue

        val depth = depth(coord.x + 0.5f, coord.y + 0.5f, coord.z + 0.5f)
        // Skipping voxels that cannot win also means the far half of a dense grid is
        // rejected on one float compare. Ties keep the first voxel found, which is stable
        // because `allCoords` has a fixed order.
        if (depth <= bestDepth) continue

        val hit = visibleFaces(coord.x.toFloat(), coord.y.toFloat(), coord.z.toFloat())
            .any { (_, quad) -> quad.contains(point) }
        if (hit) {
            best = coord
            bestDepth = depth
        }
    }
    return best
}

/** Whether [point] lies inside the whole grid at the current camera. */
private fun Projection.containsGrid(point: Point): Boolean {
    val b = bounds()
    return point.x >= b.left && point.x <= b.right && point.y >= b.top && point.y <= b.bottom
}

/**
 * Whether [point] lies inside this quadrilateral.
 *
 * Winding-agnostic, because the renderer winds its faces for its own convenience and a test
 * that assumed a particular winding would break the moment a face is added or the camera
 * crossed the axis. This requires all four edge cross products to agree in sign, which is
 * exact for a convex quad; every quad here is a projected cube face, and a cube face
 * projects to a convex quad for any camera outside its plane.
 *
 * Points exactly on an edge give a zero cross product, which counts as agreeing with both
 * signs. Edges are therefore inclusive, so a pointer on the seam between two cubes picks
 * one of them instead of falling through the gap.
 */
fun Quad.contains(point: Point): Boolean {
    val signs = listOf(
        Edge(a, b).cross(point),
        Edge(b, c).cross(point),
        Edge(c, d).cross(point),
        Edge(d, a).cross(point),
    )
    return signs.all { it >= 0f } || signs.all { it <= 0f }
}

/** One directed edge of a [Quad], for the point-in-polygon test. */
private data class Edge(val from: Point, val to: Point) {
    /** This edge crossed with the vector from its start to [point]; the sign is the side. */
    fun cross(point: Point): Float {
        val ex = to.x - from.x
        val ey = to.y - from.y
        return ex * (point.y - from.y) - ey * (point.x - from.x)
    }
}