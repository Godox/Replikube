package fr.godox.replikube.app.render

import fr.godox.replikube.core.GridSize
import kotlin.math.cos
import kotlin.math.sin

/**
 * A point on screen, in pixels, y growing downwards.
 *
 * Deliberately not Compose's `Offset`: this is pure geometry with no UI dependency, so it
 * can be unit-tested without a toolkit and reused by the renderers and the tests.
 */
data class Point(val x: Float, val y: Float) {
    operator fun plus(other: Point) = Point(x + other.x, y + other.y)
    operator fun times(scalar: Float) = Point(x * scalar, y * scalar)
}

/** A screen-space axis-aligned rectangle. */
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** A face as four screen points, wound consistently so a renderer can fill it directly. */
data class Quad(val a: Point, val b: Point, val c: Point, val d: Point)

/** The faces of a cube this game ever draws. There is no bottom face; it is never visible. */
enum class Face { TOP, MAX_X, MIN_X, MAX_Z, MIN_Z }

/**
 * Turns voxel coordinates into screen points.
 *
 * The camera is an orthonormal basis rather than a pile of sine factors. Given a yaw about
 * the vertical axis and a tilt away from overhead, the basis is:
 *
 * ```
 * view  = direction from the grid towards the camera
 * right = horizontal, perpendicular to view
 * up    = right × view
 * ```
 *
 * Anything else — including the usual "multiply each axis by cos(30°)" shortcut — has to
 * re-derive orthonormality by hand, and getting it slightly wrong stretches the grid
 * horizontally or vertically in a way that is hard to notice and easy to ship. Building
 * the basis and dotting it is both shorter and correct by construction: `right`, `up` and
 * `view` are mutually perpendicular and unit length, so a unit step along a world axis is
 * exactly as long on screen as it should be.
 *
 * Pitch is clamped well short of the poles: a grid viewed exactly edge-on is unreadable,
 * and near the poles the top face degenerates to a line.
 */
class Projection(
    private val size: GridSize,
    yaw: Float = DEFAULT_YAW,
    pitch: Float = DEFAULT_PITCH,
    zoom: Float = DEFAULT_ZOOM,
) {
    /**
     * The camera basis, recomputed whenever the camera moves.
     *
     * Held in one place rather than as a handful of `cos`/`sin` fields so that it cannot
     * go stale: [yaw] and [pitch] are mutable because the viewport orbits by dragging, and
     * a basis captured once at construction would leave the camera frozen while the
     * properties happily reported new angles.
     */
    private class Basis(yaw: Float, pitch: Float) {
        private val cosYaw = cos(yaw)
        private val sinYaw = sin(yaw)
        private val sinPitch = sin(pitch)

        /** Unit direction from the grid's centre towards the camera. */
        val view = Vec3(cosYaw * sinPitch, cos(pitch), sinYaw * sinPitch)

        /** Horizontal unit vector, perpendicular to [view]. */
        val right = Vec3(-sinYaw, 0f, cosYaw)

        /** Vertical unit vector, screen-up. The screen's y axis is the negation of this. */
        val up = right cross view

        // Screen-space direction of a one-voxel step along each world axis, before zoom.
        // A 9³ grid is 729 cubes and up to three faces each, so per-voxel vector maths is
        // the one thing worth keeping out of the draw loop.
        val axisX = Point(right.x, -up.x)
        val axisY = Point(right.y, -up.y)
        val axisZ = Point(right.z, -up.z)
    }

    /** Rotation about the vertical axis, in radians. */
    var yaw: Float = yaw
        set(value) {
            field = value
            basis = Basis(field, this.pitch)
        }

    /** Tilt away from straight overhead, in radians. Clamped on assignment. */
    var pitch: Float = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
        set(value) {
            field = value.coerceIn(MIN_PITCH, MAX_PITCH)
            basis = Basis(this.yaw, field)
        }

    /** Pixels per voxel edge. */
    var zoom: Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    // `this.` is deliberate: a bare `yaw` here would be the constructor parameter, not the
    // property, and would silently rebuild the initial camera if the two ever disagreed.
    private var basis = Basis(this.yaw, this.pitch)

    /** Screen offset of a one-voxel step along +x, at the current [zoom]. */
    val unitX: Point get() = basis.axisX * zoom

    /** Screen offset of a one-voxel step along +y, at the current [zoom]. */
    val unitY: Point get() = basis.axisY * zoom

    /** Screen offset of a one-voxel step along +z, at the current [zoom]. */
    val unitZ: Point get() = basis.axisZ * zoom

    /** Where the voxel-space point ([x], [y], [z]) projects, relative to the viewport centre. */
    fun project(x: Float, y: Float, z: Float): Point = projectAt(x, y, z, zoom)

    private fun projectAt(x: Float, y: Float, z: Float, zoom: Float): Point {
        val p = Vec3(x, y, z)
        // Screen y grows downwards, hence the negation of the up component.
        return Point(p dot basis.right, -(p dot basis.up)) * zoom
    }

    /** Absolute screen position of the voxel-space point ([x], [y], [z]). */
    fun at(origin: Point, x: Float, y: Float, z: Float): Point = origin + project(x, y, z)

    /**
     * How near ([x], [y], [z]) is to the camera. **Larger is nearer.**
     *
     * This is the component along the view direction, and the camera sits far away along
     * that same direction, so it is a serviceable depth key. Sorting *ascending* paints
     * far-to-near, which is exact for axis-aligned unit cubes on a lattice: two such cubes
     * never interpenetrate, so no depth buffer is needed.
     */
    fun depth(x: Float, y: Float, z: Float): Float = Vec3(x, y, z) dot basis.view

    /**
     * The visible faces of the cube whose lower corner is ([x], [y], [z]).
     *
     * Which three are visible depends on the camera, so this is derived rather than
     * hard-coded: a face is visible exactly when its outward normal has a positive
     * component along the view direction. Returned top-face-first, then the two sides,
     * each wound the same way round so a renderer can fill them without re-deriving the
     * camera.
     */
    fun visibleFaces(x: Float, y: Float, z: Float): List<Pair<Face, Quad>> {
        val o = project(x, y, z)
        val u = unitX
        val v = unitY
        val w = unitZ

        // `o` is the cube's *lower* corner, so every top-face vertex is one step up.
        val faces = mutableListOf<Pair<Face, Quad>>(
            Face.TOP to Quad(o + v, o + u + v, o + u + w + v, o + w + v),
        )
        faces += if (basis.view.x > 0f) {
            Face.MAX_X to Quad(o + u, o + u + v, o + u + w + v, o + u + w)
        } else {
            Face.MIN_X to Quad(o, o + w, o + w + v, o + v)
        }
        faces += if (basis.view.z > 0f) {
            Face.MAX_Z to Quad(o + w, o + w + v, o + u + w + v, o + u + w)
        } else {
            Face.MIN_Z to Quad(o, o + v, o + u + v, o + u)
        }
        return faces
    }

    /** Bounding box of the whole grid at this camera, relative to the viewport centre. */
    fun bounds(): Rect = boundsAtZoom(zoom)

    /**
     * The same box, at an arbitrary zoom.
     *
     * The zoom is a parameter rather than a constructor argument on purpose: the [zoom]
     * property clamps to [MIN_ZOOM] on the way in, so `Projection(size, zoom = 1f).bounds()`
     * would silently measure at [MIN_ZOOM] and `fitZoom` would be off by that factor.
     */
    private fun boundsAtZoom(zoom: Float): Rect {
        val b = size.bounds
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (x in listOf(b.minX.toFloat(), (b.maxX + 1).toFloat())) {
            for (y in listOf(b.minY.toFloat(), (b.maxY + 1).toFloat())) {
                for (z in listOf(b.minZ.toFloat(), (b.maxZ + 1).toFloat())) {
                    val p = projectAt(x, y, z, zoom)
                    minX = minOf(minX, p.x)
                    minY = minOf(minY, p.y)
                    maxX = maxOf(maxX, p.x)
                    maxY = maxOf(maxY, p.y)
                }
            }
        }
        return Rect(minX, minY, maxX, maxY)
    }

    /**
     * The [zoom] at which the grid fits inside [viewport] with [padding] to spare.
     *
     * Measured at [zoom] 1 and scaled up, so the answer does not depend on the current
     * zoom — re-fitting after the player has zoomed in must be idempotent, and a formula
     * that reads the live zoom is not.
     */
    fun fitZoom(viewport: Rect, padding: Float = 48f): Float {
        val availableX = viewport.width - 2 * padding
        val availableY = viewport.height - 2 * padding
        if (availableX <= 0f || availableY <= 0f) return MIN_ZOOM

        val atUnitZoom = boundsAtZoom(1f)
        if (atUnitZoom.width <= 0f || atUnitZoom.height <= 0f) return MIN_ZOOM

        return minOf(availableX / atUnitZoom.width, availableY / atUnitZoom.height)
            .coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    companion object {
        const val MIN_PITCH = 0.20f
        const val MAX_PITCH = 1.45f
        const val MIN_ZOOM = 8f
        const val MAX_ZOOM = 200f

        const val DEFAULT_YAW = 0.6f
        const val DEFAULT_PITCH = 0.7f
        const val DEFAULT_ZOOM = 40f
    }
}

/** A point in voxel space, for the dot products the camera basis needs. */
private data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun times(scalar: Float) = Vec3(x * scalar, y * scalar, z * scalar)
}

private infix fun Vec3.dot(other: Vec3) = x * other.x + y * other.y + z * other.z

private infix fun Vec3.cross(other: Vec3) = Vec3(
    y * other.z - z * other.y,
    z * other.x - x * other.z,
    x * other.y - y * other.x,
)
