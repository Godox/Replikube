package fr.godox.replikube.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.godox.replikube.app.render.Projection
import fr.godox.replikube.app.render.Rect
import fr.godox.replikube.core.GridSize

/**
 * The camera, shared by both viewports.
 *
 * ### Why one camera and not two
 *
 * The player compares their solution against the target by looking back and forth between
 * the two panes. If those panes had independent cameras, the comparison would be between
 * two different viewpoints, which is work rather than reading — every glance would need a
 * correction for the angle. Sharing the camera makes the panes one object seen twice:
 * rotate either pane and you have rotated the shape you are reasoning about, not a picture
 * of it.
 *
 * ### Why this is snapshot state and not a mutable `Projection`
 *
 * `Projection` is designed to be mutated in place — it holds a private `Basis` rebuilt by
 * its `yaw` and `pitch` setters. If a viewport held a `Projection` in `remember` and
 * mutated it from a pointer handler, the draw phase would never be invalidated: Compose has
 * no way to know a plain field changed, so the canvas would keep showing the pixels of the
 * previous frame while the object behind them had moved. That is the same class of bug as
 * mutating a `var` the composable never reads, and it is why the viewport used to orbit
 * into a frozen picture.
 *
 * So the angles live here as snapshot state, are read during composition, and a fresh
 * [Projection] is derived from them. The mutation is therefore observed, and because both
 * viewports read the same state, both redraw.
 */
@Stable
class OrbitState(
    yaw: Float = Projection.DEFAULT_YAW,
    pitch: Float = Projection.DEFAULT_PITCH,
    zoom: Float = Projection.DEFAULT_ZOOM,
) {
    /** Rotation about the vertical axis, in radians. Unbounded; wrapping is a UI concern. */
    var yaw by mutableFloatStateOf(yaw)

    /** Tilt away from straight overhead, in radians. Clamped: see `Projection.pitch`. */
    var pitch by mutableFloatStateOf(clampPitch(pitch))
        private set

    /** Pixels per voxel edge, shared so both panes are at the same scale. */
    var zoom by mutableFloatStateOf(clampZoom(zoom))
        private set

    /** The camera at these angles, for rendering or hit-testing. */
    fun projection(size: GridSize): Projection = Projection(size, yaw, pitch, zoom)

    /**
     * Turns the camera by a drag of [deltaX], [deltaY] pixels.
     *
     * Vertical drags pull the camera *down* the grid's screen: dragging down raises the
     * pitch towards overhead, which is the direction the shape appears to turn under the
     * cursor.
     */
    fun orbit(deltaX: Float, deltaY: Float) {
        yaw += deltaX
        pitch = clampPitch(pitch + deltaY)
    }

    /** Zooms by [delta] scroll units, staying inside the renderer's limits. */
    fun zoomBy(delta: Float) {
        zoom = clampZoom(zoom - delta * ZOOM_SCALE)
    }

    /**
     * Refits the zoom so the grid fills [viewport] again.
     *
     * Measures at the current angle rather than resetting the camera: the player's chosen
     * viewpoint is worth keeping, and only the scale was lost.
     */
    fun fit(size: GridSize, viewport: Rect) {
        zoom = clampZoom(projection(size).fitZoom(viewport))
    }

    private companion object {
        const val ZOOM_SCALE = 0.6f

        fun clampPitch(value: Float) = value.coerceIn(Projection.MIN_PITCH, Projection.MAX_PITCH)

        fun clampZoom(value: Float) = value.coerceIn(Projection.MIN_ZOOM, Projection.MAX_ZOOM)
    }
}

/** A remembered [OrbitState], so recomposition does not throw away the camera. */
@Composable
fun rememberOrbitState(): OrbitState = remember { OrbitState() }