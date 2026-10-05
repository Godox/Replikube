package fr.godox.replikube.app

import fr.godox.replikube.app.render.Point
import fr.godox.replikube.app.render.Projection
import fr.godox.replikube.app.render.Quad
import fr.godox.replikube.app.render.contains
import fr.godox.replikube.app.render.pick
import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.core.coords
import fr.godox.replikube.dsl.Palette
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hit-testing: which voxel does a pointer position name?
 *
 * Picking is the one piece of the renderer that is driven by the mouse rather than by the
 * camera, so it is exactly the code that can look fine in a screenshot and still be wrong.
 * These tests pin the properties a hover highlight depends on.
 */
class PickingTest {

    private val size3 = GridSize(3, 3, 3)

    private fun projection(yaw: Float = Projection.DEFAULT_YAW, pitch: Float = Projection.DEFAULT_PITCH) =
        Projection(size3, yaw, pitch, zoom = 40f)

    /** The screen position of a voxel's centre, in the renderer's relative coordinates. */
    private fun Projection.centreOf(c: Coord) = project(c.x + 0.5f, c.y + 0.5f, c.z + 0.5f)

    // --- Quad.contains ------------------------------------------------------------------

    @Test
    fun `a point inside a square is contained`() {
        val quad = Quad(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f))
        assertTrue(quad.contains(Point(5f, 5f)))
    }

    @Test
    fun `a point outside a square is not contained`() {
        val quad = Quad(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f))
        assertTrue(!quad.contains(Point(-1f, 5f)), "left of the left edge")
        assertTrue(!quad.contains(Point(5f, 11f)), "below the bottom edge")
        assertTrue(!quad.contains(Point(50f, 50f)), "diagonally away")
    }

    @Test
    fun `containment does not depend on winding`() {
        // The renderer may wind a quad either way round depending on which face is being
        // drawn, and the camera can mirror it by crossing an axis. A test that assumed one
        // winding would pass until that happened.
        val clockwise = Quad(Point(0f, 0f), Point(0f, 10f), Point(10f, 10f), Point(10f, 0f))
        val counter = Quad(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f))

        for (p in listOf(Point(1f, 1f), Point(9f, 9f), Point(5f, 0.5f), Point(0f, 5f))) {
            assertEquals(clockwise.contains(p), counter.contains(p), "disagreed at $p")
            assertTrue(counter.contains(p), "$p is inside the square")
        }
    }

    @Test
    fun `edges and corners are inclusive`() {
        // A pointer on the seam between two cubes should name one of them rather than
        // falling through the gap.
        val quad = Quad(Point(0f, 0f), Point(10f, 0f), Point(10f, 10f), Point(0f, 10f))
        assertTrue(quad.contains(Point(0f, 0f)), "corner")
        assertTrue(quad.contains(Point(10f, 5f)), "midpoint of the right edge")
        assertTrue(quad.contains(Point(5f, 0f)), "midpoint of the top edge")
    }

    // --- pick ---------------------------------------------------------------------------

    @Test
    fun `a single cube is picked at its own centre`() {
        val grid = VoxelGrid.build(size3) { x, y, z ->
            if (x == 0 && y == 0 && z == 0) Palette.RED else Palette.EMPTY
        }
        val projection = projection()

        assertEquals(
            Coord(0, 0, 0),
            projection.pick(grid, projection.centreOf(Coord(0, 0, 0))),
        )
    }

    @Test
    fun `an empty voxel is never picked`() {
        // The grid still has holes where nothing was drawn. Hovering one must report no
        // voxel, not the nearest cube — otherwise the tooltip lies about what is there.
        val grid = VoxelGrid.build(size3) { _, _, _ -> Palette.EMPTY }
        val projection = projection()

        assertNull(projection.pick(grid, Point(0f, 0f)), "a grid with nothing in it")
        assertNull(projection.pick(grid, projection.centreOf(Coord(0, 0, 0))))
    }

    @Test
    fun `a point outside the grid picks nothing`() {
        val grid = VoxelGrid.filled(size3, Palette.BLUE)
        val projection = projection()

        val far = Point(projection.bounds().right + 500f, 0f)
        assertNull(projection.pick(grid, far), "well outside the silhouette")
    }

    @Test
    fun `the nearest cube wins where two overlap on screen`() {
        // The reason picking iterates by depth rather than returning the first match: a
        // ray through the middle of the grid hits several cubes, and the one the player can
        // see is the nearest.
        val grid = VoxelGrid.build(size3) { _, _, _ -> Palette.BLUE }
        val projection = projection()

        val centre = projection.pick(grid, Point(0f, 0f))
        assertNotNull(centre, "the middle of a solid grid is a cube")

        // Whatever is picked must be nearer than every other candidate along that ray, or
        // the highlight would appear on a hidden cube. Checking against the rendered
        // silhouette is what makes this meaningful.
        val pickedDepth = projection.depth(centre.x + 0.5f, centre.y + 0.5f, centre.z + 0.5f)
        val hiddenBehind = grid.allCoords()
            .filter { it != centre }
            .filter { projection.centreOf(it).let { p -> p.x * p.x + p.y * p.y < 4f } }
            .map { projection.depth(it.x + 0.5f, it.y + 0.5f, it.z + 0.5f) }
        assertTrue(
            hiddenBehind.all { it < pickedDepth },
            "picked depth $pickedDepth must exceed the depths behind it ($hiddenBehind)",
        )
    }

    @Test
    fun `a hole picks the cube behind it rather than nothing`() {
        // Depth ordering, end to end. This is the property a hover highlight depends on: if
        // picking ignored depth, the highlight would appear on whichever cube the scan
        // happened to reach first, which after a hole opens is a cube the player cannot see.
        val solid = VoxelGrid.filled(size3, Palette.BLUE)
        val projection = projection()
        fun depthOf(c: Coord) = projection.depth(c.x + 0.5f, c.y + 0.5f, c.z + 0.5f)

        val front = assertNotNull(projection.pick(solid, Point(0f, 0f)), "a solid grid has a front")
        val holed = VoxelGrid.build(size3) { x, y, z ->
            if (Coord(x, y, z) == front) Palette.EMPTY else Palette.BLUE
        }

        val behind = assertNotNull(
            projection.pick(holed, Point(0f, 0f)),
            "a hole should reveal what is behind it, not nothing",
        )
        assertNotEquals(front, behind, "the hole is empty, so nothing can name it")
        assertTrue(depthOf(behind) < depthOf(front), "the revealed cube must be further away")
    }

    @Test
    fun `a lone cube is picked at its centre from any camera`() {
        // Every position in the grid, so a coordinate mix-up — say reading `z` where `y`
        // belongs — cannot hide behind a symmetric case.
        val projection = projection()

        for (c in size3.coords().toList()) {
            val lone = VoxelGrid.build(size3) { x, y, z ->
                if (Coord(x, y, z) == c) Palette.RED else Palette.EMPTY
            }
            assertEquals(
                c,
                projection.pick(lone, projection.centreOf(c)),
                "a lone cube at $c must be picked at its own centre",
            )
        }
    }

    @Test
    fun `orbiting never picks a coordinate outside the grid`() {
        // Guards the coordinate conversion in `pick` against an off-by-one, which would
        // otherwise only show up as a tooltip reading (0, 0, 3) on a 3x3x3 level.
        val grid = VoxelGrid.filled(size3, Palette.BLUE)
        val projection = projection()
        val bounds = size3.bounds

        var yaw = -3f
        while (yaw < 3f) {
            projection.yaw = yaw
            var y = -300f
            while (y <= 300f) {
                var x = -300f
                while (x <= 300f) {
                    val hit = projection.pick(grid, Point(x, y))
                    if (hit != null) {
                        assertTrue(
                            hit in bounds,
                            "at yaw=$yaw point=($x, $y) picked $hit, which is off the grid",
                        )
                    }
                    x += 7f
                }
                y += 7f
            }
            yaw += 0.25f
        }
    }

    @Test
    fun `picking follows the camera`() {
        val grid = VoxelGrid.build(size3) { _, y, _ -> if (y == 1) Palette.RED else Palette.EMPTY }
        val projection = projection()

        val fromAbove = assertNotNull(projection.pick(grid, projection.centreOf(Coord(0, 1, 0))))
        assertEquals(Coord(0, 1, 0), fromAbove, "the only filled layer")

        // Orbit past the point where the top face stops being visible and the same pick
        // must still name the voxel that is actually drawn there.
        projection.pitch = Projection.MIN_PITCH
        val fromBelow = projection.pick(grid, projection.project(0.5f, 0.5f, 0.5f))
        assertNotNull(fromBelow, "still picks the layer from a shallow angle")
    }

    @Test
    fun `every cube on the near side of a solid grid is reachable by some pointer`() {
        // The mirror of the hole test: whatever the camera can see must be pickable. A quad
        // test with the wrong winding would pass the centre-picks tests and fail here, on
        // whichever faces happen to wind the other way.
        val grid = VoxelGrid.filled(size3, Palette.BLUE)
        val projection = projection()

        val found = mutableSetOf<Coord>()
        var y = -200f
        while (y <= 200f) {
            var x = -200f
            while (x <= 200f) {
                projection.pick(grid, Point(x, y))?.let(found::add)
                x += 2f
            }
            y += 2f
        }

        // Everything strictly behind the front shell is invisible by construction, so the
        // requirement is on the cubes that have at least one face the camera can see and
        // that no other cube covers. The near corner of each axis pair qualifies; the
        // centre of a solid 3³ is covered by six neighbours and is legitimately never named.
        val visible = grid.allCoords().filter { c ->
            projection.visibleFaces(c.x.toFloat(), c.y.toFloat(), c.z.toFloat())
                .any { (_, quad) ->
                    // The face centre, as the renderer would draw it.
                    Point(
                        (quad.a.x + quad.c.x) / 2f,
                        (quad.a.y + quad.c.y) / 2f,
                    ).let { centre -> grid.allCoords().none { other ->
                        other != c && projection.depth(
                            other.x + 0.5f, other.y + 0.5f, other.z + 0.5f,
                        ) > projection.depth(c.x + 0.5f, c.y + 0.5f, c.z + 0.5f) &&
                            quad.contains(centre)
                    } }
                }
        }.toSet()

        val missing = visible - found
        assertTrue(
            missing.isEmpty(),
            "these cubes are drawn but never picked: ${missing.sortedBy { it.toString() }}",
        )
    }
}