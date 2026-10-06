package fr.godox.replikube.core

import fr.godox.replikube.dsl.Palette
import fr.godox.replikube.dsl.Replikube
import kotlinx.serialization.Serializable

/**
 * A level's metadata, as authored in `levels/src/main/resources/levels/<id>.json`.
 *
 * ### The target is data, not a compiled program
 *
 * [target] is the shape itself: a flat row-major matrix of palette ids (see [TargetMatrix]).
 * It used to be derived by compiling [reference] at load time, which made opening a level
 * depend on the Kotlin compiler and turned any packaging or bytecode mismatch into a total
 * loss of the campaign. A matrix is a parse, and a parse fails in one comprehensible way.
 *
 * [reference] is kept as text — never executed to produce the target — because the Reveal
 * button shows it once a level is beaten. It is documentation, and it is allowed to be wrong:
 * the game compares the player's grid against [target], never against [reference].
 */
@Serializable
data class Level(
    val id: String,
    val title: String,
    val difficulty: Int,
    val par: Int,
    val hint: String,
    val size: GridSize,
    /** The shape, as palette ids in row-major order. See [TargetMatrix]. */
    val target: List<Int>,
    /** The body of a Kotlin solution, shown by Reveal after the level is beaten. */
    val reference: String,
) {
    init {
        require(id.isNotBlank()) { "Level id must not be blank" }
        require(par > 0) { "Level $id must have a positive par" }
        require(target.size == size.voxelCount) {
            "Level $id declares size $size (${size.voxelCount} cells) but its target has ${target.size}"
        }
    }

    /** [target] as a grid. Cheap enough to call per level load, and checked by [init]. */
    fun targetGrid(): VoxelGrid = TargetMatrix.toGrid(size, target)
}

/**
 * A level paired with its target grid.
 *
 * Nothing here is compiled any more: [target] is the level's own matrix, turned into a grid.
 *
 * @property target what the player must reproduce
 * @property reference a Kotlin solution, shown only after the level is beaten
 */
data class LoadedLevel(
    val level: Level,
    val target: VoxelGrid,
    val reference: String,
) {
    val id: String get() = level.id

    /** Renders [result] against this level's target. */
    fun verify(result: VoxelGrid): Verifier.Result = Verifier.diff(target, result)
}

/**
 * A compiled solution: a callable over coordinates.
 *
 * This is the one type `:core` and `:app` know about — it hides whether the code came
 * from `:scripting`'s compiler, a cached class, or a test double.
 *
 * Returns an `Int`, not an `Int?`: a `:dsl` [Replikube] may answer `null` for "leave
 * this voxel empty", and that is a *player-facing* convenience. Folding it into
 * [Palette.EMPTY] here is the single place it happens, so nothing above `:core` has to
 * know `null` existed.
 */
fun interface VoxelProgram {
    fun block(x: Int, y: Int, z: Int): Int
}

/** A [VoxelProgram] backed by a `:dsl` [Replikube], mapping `null` to [Palette.EMPTY]. */
fun Replikube.asVoxelProgram(): VoxelProgram = VoxelProgram { x, y, z -> block(x, y, z) ?: Palette.EMPTY }

/** Renders [VoxelProgram] over a whole grid. */
fun VoxelProgram.render(size: GridSize): VoxelGrid = VoxelGrid.build(size) { x, y, z -> block(x, y, z) }

/** Renders [Replikube] over a whole grid. */
fun Replikube.render(size: GridSize): VoxelGrid = asVoxelProgram().render(size)
