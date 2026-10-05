package fr.godox.replikube.app

import fr.godox.replikube.app.render.Projection
import fr.godox.replikube.app.render.Rect
import fr.godox.replikube.app.ui.OrbitState
import fr.godox.replikube.core.GridSize
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shared camera.
 *
 * The requirement these pin is that the two stacked viewports show one shape from one
 * viewpoint: rotating either pane rotates both. That is only true if both read the *same*
 * mutable state, which is a property of this class rather than of either viewport — so it
 * is tested here, where it can be stated directly.
 *
 * The other thing worth pinning is the clamping. An unbounded pitch reaches the poles, where
 * the projection degenerates: the top face collapses to a line and the grid becomes
 * unreadable. An unbounded zoom reaches a scale where a 9³ grid is a single pixel or a
 * screen-filling smear. Both are unrecoverable without a reset, so the limits are enforced
 * at the point of mutation rather than trusted to callers.
 */
class OrbitStateTest {

    private val size = GridSize(5, 5, 5)

    @Test
    fun `both viewports read one camera, so a drag in one moves the other`() {
        val camera = OrbitState()
        val targetView = camera.projection(size)
        val solutionView = camera.projection(size)

        // What a drag in the target pane would do.
        camera.orbit(deltaX = 0.4f, deltaY = 0.15f)

        val targetAfter = camera.projection(size)
        val solutionAfter = camera.projection(size)
        assertEquals(targetAfter.yaw, solutionAfter.yaw, "the two panes must not drift apart")
        assertEquals(targetAfter.pitch, solutionAfter.pitch)
        assertEquals(targetAfter.zoom, solutionAfter.zoom)

        assertEquals(
            targetView.yaw + 0.4f,
            targetAfter.yaw,
            "the drag must actually have moved the shared camera",
        )
        assertNotEquals(targetView.pitch, targetAfter.pitch, "the drag must also tilt")
    }

    @Test
    fun `orbiting changes the projection, not just the recorded angle`() {
        // The bug this class exists to prevent: a `Projection` whose angles changed while
        // its basis stayed as built, so the camera reported a new angle and drew the old
        // picture. Checking the angles alone would not catch it.
        val camera = OrbitState()
        val before = camera.projection(size)
        camera.orbit(0.9f, 0.3f)
        val after = camera.projection(size)

        // Off the origin, deliberately. `Projection` works in coordinates centred on the
        // grid, so (0, 0, 0) is the camera's fixed point and is the one place an orbit
        // cannot move anything — asserting on it would pass for a frozen camera too.
        for ((x, y, z) in listOf(Triple(1f, 0f, 0f), Triple(0f, 2f, 0f), Triple(-1f, -1f, 2f))) {
            assertNotEquals(
                before.project(x, y, z),
                after.project(x, y, z),
                "projecting ($x, $y, $z) should move after an orbit",
            )
        }

        // And the fixed point really is the origin, which is what makes the above a
        // meaningful choice of points. Compared by component rather than with `==`: the
        // y axis is negated on the way out, so the pivot comes back as `-0.0`, and a
        // data class holding `Float`s compares -0.0 and 0.0 as different.
        val pivot = after.project(0f, 0f, 0f)
        assertTrue(abs(pivot.x) < 1e-4f && abs(pivot.y) < 1e-4f, "the grid centre is the pivot")
    }

    @Test
    fun `pitch is clamped away from the poles`() {
        val camera = OrbitState()

        camera.orbit(0f, 10f)
        assertEquals(Projection.MAX_PITCH, camera.pitch, "dragging down must not pass overhead")

        camera.orbit(0f, -20f)
        assertEquals(Projection.MIN_PITCH, camera.pitch, "nor edge-on")

        // At both limits the top face is still a quad with area, not a degenerate line.
        for (limit in listOf(Projection.MIN_PITCH, Projection.MAX_PITCH)) {
            val p = Projection(size, Projection.DEFAULT_YAW, limit, 40f)
            val top = p.visibleFaces(0f, 0f, 0f).first { it.first == fr.godox.replikube.app.render.Face.TOP }
            val quad = top.second
            val area = (quad.a.x - quad.c.x) * (quad.b.y - quad.d.y) -
                (quad.a.y - quad.c.y) * (quad.b.x - quad.d.x)
            assertTrue(
                kotlin.math.abs(area) > 1f,
                "the top face collapsed to a line at pitch $limit",
            )
        }
    }

    @Test
    fun `zoom is clamped to a range the renderer can draw`() {
        val camera = OrbitState()

        camera.zoomBy(1_000f)
        assertEquals(Projection.MIN_ZOOM, camera.zoom, "scrolling out must stop at the far limit")

        camera.zoomBy(-1_000f)
        assertEquals(Projection.MAX_ZOOM, camera.zoom, "and in at the near limit")
    }

    @Test
    fun `zoomBy moves in the direction the scroll does`() {
        val camera = OrbitState(zoom = 40f)
        val start = camera.zoom

        camera.zoomBy(1f)
        assertTrue(camera.zoom < start, "scrolling away should zoom out")

        camera.zoomBy(-2f)
        assertTrue(camera.zoom > start, "scrolling towards should zoom in")
    }

    @Test
    fun `fitting refits the zoom but keeps the angle`() {
        val camera = OrbitState()
        camera.orbit(1.1f, -0.4f)
        val (yaw, pitch) = camera.yaw to camera.pitch

        camera.fit(size, Rect(0f, 0f, 300f, 200f))

        assertEquals(yaw, camera.yaw, "refitting must not throw away the player's viewpoint")
        assertEquals(pitch, camera.pitch)
        assertTrue(camera.zoom > Projection.MIN_ZOOM, "a 300x200 pane should fit something")
    }

    @Test
    fun `fitting the same pane twice is idempotent`() {
        // The viewport refits whenever its size changes. If the fit were not idempotent, a
        // window resize would compound and the grid would creep.
        val camera = OrbitState()
        val viewport = Rect(0f, 0f, 420f, 260f)

        camera.fit(size, viewport)
        val once = camera.zoom
        camera.fit(size, viewport)
        assertEquals(once, camera.zoom, "refitting must not move the zoom")
    }

    @Test
    fun `yaw is not clamped`() {
        // Wrapping would be a nicety, but clamping would be a bug: dragging in a circle
        // would stop dead at the limit. Yaw may be any value; only the derived projection
        // has to stay well defined, which periodicity guarantees.
        val camera = OrbitState()
        camera.orbit(deltaX = 100f, deltaY = 0f)

        assertEquals(Projection.DEFAULT_YAW + 100f, camera.yaw)
        assertEquals(
            camera.projection(size).yaw,
            camera.yaw,
            "the projection must follow the camera even far from the origin",
        )
    }
}