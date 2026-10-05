package fr.godox.replikube.app

import fr.godox.replikube.app.render.Face
import fr.godox.replikube.app.render.Point
import fr.godox.replikube.app.render.Projection
import fr.godox.replikube.app.render.Rect
import fr.godox.replikube.core.GridSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The isometric projection.
 *
 * These are the invariants that make painter's-algorithm rendering correct. If the basis
 * is not orthonormal, cubes come out sheared; if depth is not monotonic along the view
 * direction, the draw order is wrong and voxels occlude their neighbours.
 */
class ProjectionTest {

    private val size = GridSize(5, 5, 5)

    private fun length(p: Point) =
        kotlin.math.hypot(p.x.toDouble(), p.y.toDouble()).toFloat()

    @Test
    fun `the projection is linear, so a shear in the basis would show up here`() {
        val p = Projection(size, yaw = 0.6f, pitch = 0.7f, zoom = 40f)
        for (i in 1..4) {
            val ax = i.toFloat(); val ay = -i.toFloat(); val az = 2f * i
            val bx = -1f; val by = i.toFloat(); val bz = i.toFloat()

            val pa = p.project(ax, ay, az)
            val pb = p.project(bx, by, bz)
            val sum = p.project(ax + bx, ay + by, az + bz)

            assertEquals(pa.x + pb.x, sum.x, 0.001f)
            assertEquals(pa.y + pb.y, sum.y, 0.001f)
        }
    }

    @Test
    fun `no world axis is foreshortened past the camera distance`() {
        // Orthonormality means every axis vector has unit length in 3D, so its projection
        // is `zoom` times the cosine of its angle to the view — never more than `zoom`.
        // Exceeding it would mean the basis was scaled by accident.
        val p = Projection(size, zoom = 40f)
        for (unit in listOf(p.unitX, p.unitY, p.unitZ)) {
            assertTrue(length(unit) <= 40.001f, "axis longer than zoom: ${length(unit)}")
            assertTrue(length(unit) > 1f, "axis collapsed: ${length(unit)}")
        }
    }

    @Test
    fun `at the canonical isometric camera all three axes project equally`() {
        // View along the (1,1,1) diagonal: the one orientation where equal foreshortening
        // on all three axes is exactly right. Worth pinning because it is the case a
        // hand-rolled cos(30°) factor is usually tuned against.
        val pitch = acos(1.0 / sqrt(3.0)).toFloat()
        val p = Projection(size, yaw = PI.toFloat() / 4f, pitch = pitch, zoom = 40f)
        val lx = length(p.unitX)
        assertTrue(abs(lx - length(p.unitY)) < 0.01f, "x=${lx} y=${length(p.unitY)}")
        assertTrue(abs(lx - length(p.unitZ)) < 0.01f, "x=${lx} z=${length(p.unitZ)}")
    }

    @Test
    fun `zoom scales the projection linearly`() {
        val a = Projection(size, zoom = 10f)
        val b = Projection(size, zoom = 20f)
        assertEquals(2f, b.unitX.x / a.unitX.x, 0.001f)
        assertEquals(2f, b.unitY.y / a.unitY.y, 0.001f)
        assertEquals(2f, b.bounds().width / a.bounds().width, 0.001f)
    }

    @Test
    fun `a higher voxel draws higher up the screen`() {
        val p = Projection(size)
        val low = p.project(0f, -1f, 0f)
        val high = p.project(0f, 1f, 0f)
        assertTrue(high.y < low.y, "higher y should be further up the screen: $high vs $low")
    }

    @Test
    fun `depth increases towards the camera`() {
        // The camera sits along +view at yaw 0.6, so moving along +x and +z comes nearer
        // and moving along +y... also comes nearer, since pitch is less than 90°.
        val p = Projection(size, yaw = 0.6f, pitch = 0.7f)
        val origin = p.depth(0f, 0f, 0f)
        assertTrue(p.depth(1f, 0f, 0f) > origin, "+x should be nearer")
        assertTrue(p.depth(0f, 0f, 1f) > origin, "+z should be nearer")
        assertTrue(p.depth(0f, 1f, 0f) > origin, "+y should be nearer")
    }

    @Test
    fun `depth orders a column correctly`() {
        // Every voxel in one column shares the same x and z, so depth must be strictly
        // increasing in y — otherwise a cube can be drawn behind the one above it.
        val p = Projection(size)
        val depths = (-2..2).map { p.depth(0f, it.toFloat(), 0f) }
        assertTrue(depths.zipWithNext().all { (a, b) -> b > a }, "depth not monotonic in y: $depths")
    }

    @Test
    fun `the top face is always visible and the bottom never is`() {
        for (yaw in listOf(-2.5f, -1f, 0f, 0.6f, 2f)) {
            for (pitch in listOf(0.25f, 0.7f, 1.4f)) {
                val faces = Projection(size, yaw, pitch).visibleFaces(0f, 0f, 0f).map { it.first }
                assertTrue(Face.TOP in faces, "no top face at yaw=$yaw pitch=$pitch")
                assertEquals(3, faces.size, "expected three visible faces at yaw=$yaw pitch=$pitch")
                assertEquals(3, faces.toSet().size, "duplicate faces at yaw=$yaw pitch=$pitch")
            }
        }
    }

    @Test
    fun `the visible sides are the ones the camera is on`() {
        val facingPositiveX = Projection(size, yaw = 0.6f).visibleFaces(0f, 0f, 0f).map { it.first }
        assertTrue(Face.MAX_X in facingPositiveX, "camera is at +x, so +x should be visible")
        assertTrue(Face.MAX_Z in facingPositiveX, "camera is at +z, so +z should be visible")

        val fromBehind = Projection(size, yaw = 0.6f + PI.toFloat()).visibleFaces(0f, 0f, 0f).map { it.first }
        assertTrue(Face.MIN_X in fromBehind, "camera is now at -x")
        assertTrue(Face.MIN_Z in fromBehind, "camera is now at -z")
    }

    @Test
    fun `a face quad has four distinct points`() {
        val p = Projection(size)
        for ((face, quad) in p.visibleFaces(0f, 0f, 0f)) {
            val points = listOf(quad.a, quad.b, quad.c, quad.d)
            assertEquals(4, points.toSet().size, "degenerate quad for $face: $quad")
        }
    }

    @Test
    fun `the top face sits above the cube's lower corner`() {
        val p = Projection(size, zoom = 40f)
        val (face, quad) = p.visibleFaces(0f, 0f, 0f).first { it.first == Face.TOP }
        val base = p.project(0f, 0f, 0f)
        assertTrue(quad.d.y < base.y || quad.a.y < base.y, "top face should be above the base: $quad vs $base")
    }

    @Test
    fun `fitZoom fills the viewport and is idempotent`() {
        val viewport = Rect(0f, 0f, 800f, 600f)
        val p = Projection(size)
        p.zoom = p.fitZoom(viewport)
        val fitted = p.bounds()
        assertTrue(fitted.width <= viewport.width, "too wide: ${fitted.width}")
        assertTrue(fitted.height <= viewport.height, "too tall: ${fitted.height}")

        // Fitting again must not shrink or grow the view: the player asked to re-centre,
        // not to zoom out a little more each time.
        val again = p.fitZoom(viewport)
        assertTrue(abs(again - p.zoom) < 0.5f, "re-fitting changed zoom: ${p.zoom} -> $again")
    }

    @Test
    fun `a larger grid fits at a smaller zoom`() {
        val viewport = Rect(0f, 0f, 800f, 600f)
        val small = Projection(GridSize(3, 3, 3)).fitZoom(viewport)
        val large = Projection(GridSize(9, 9, 9)).fitZoom(viewport)
        assertTrue(large < small, "9³ should fit smaller than 3³: $large vs $small")
    }

    @Test
    fun `pitch is clamped away from the poles`() {
        val p = Projection(size)
        p.pitch = 10f
        assertEquals(Projection.MAX_PITCH, p.pitch)
        p.pitch = -10f
        assertEquals(Projection.MIN_PITCH, p.pitch)
    }

    @Test
    fun `moving the camera actually moves the projection`() {
        // The basis has to be recomputed when yaw or pitch is assigned rather than
        // captured at construction. The viewport orbits by dragging, so a stale basis is
        // a frozen viewport whose properties still report the angles the player dragged
        // to — nothing crashes, nothing logs, and every assertion about angles passes.
        val p = Projection(size, zoom = 40f)
        val start = p.project(1f, 0f, 0f)

        p.yaw += 1f
        val yawed = p.project(1f, 0f, 0f)
        assertTrue(start != yawed, "yaw did not move the projection: $start")

        p.pitch += 0.5f
        val pitched = p.project(1f, 0f, 0f)
        assertTrue(yawed != pitched, "pitch did not move the projection: $yawed")
    }

    @Test
    fun `orbiting changes which faces are visible`() {
        // Same stale-basis failure, seen from the other end: face visibility is derived
        // from the view direction, so a frozen basis freezes it too.
        val p = Projection(size)
        val before = p.visibleFaces(0f, 0f, 0f).map { it.first }.toSet()
        p.yaw += PI.toFloat() / 2f
        val after = p.visibleFaces(0f, 0f, 0f).map { it.first }.toSet()
        assertTrue(before != after, "a quarter turn should swap a side face: $before -> $after")
    }

    @Test
    fun `the three projected axes stay consistent at every camera`() {
        // In an orthonormal basis the screen length of a unit step along a world axis is
        // `zoom * sin(angle to the view)`, so the three squared lengths always sum to
        // `3 - |view|^2 = 2` times `zoom^2`. That is exact, camera-independent, and it is
        // what a sheared or partially-rescaled basis cannot fake.
        val zoom = 40f
        for (yaw in listOf(-2.5f, 0f, 0.6f, 2.5f)) {
            for (pitch in listOf(0.25f, 0.7f, 1.4f)) {
                val p = Projection(size, yaw = yaw, pitch = pitch, zoom = zoom)
                val sum = listOf(p.unitX, p.unitY, p.unitZ).sumOf { length(it).let { l -> l * l }.toDouble() }
                assertEquals(2.0 * zoom * zoom, sum, 2.0 * zoom * zoom * 1e-4, "yaw=$yaw pitch=$pitch")
            }
        }
    }

    @Test
    fun `a degenerate viewport falls back to the minimum zoom instead of dividing by zero`() {
        val p = Projection(size)
        assertEquals(Projection.MIN_ZOOM, p.fitZoom(Rect(0f, 0f, 10f, 10f)))
    }
}
