package fr.godox.replikube.core

import fr.godox.replikube.dsl.Palette
import kotlinx.serialization.Serializable

/**
 * The difference between what a level asks for and what the player produced.
 *
 * The diff is the game's feedback channel, so it is structured rather than a boolean:
 * a player can be told *which* voxels are missing, wrong or extra.
 */
@Serializable
data class Diff(
    /** Voxels the target has filled but the result left empty. */
    val missing: List<Coord> = emptyList(),
    /** Filled voxels present in both, but with a different colour. */
    val wrongColor: List<WrongVoxel> = emptyList(),
    /** Filled voxels the result added that the target does not have. */
    val extra: List<Coord> = emptyList(),
) {
    /** Total number of voxels that must change to reach the target. */
    val errorCount: Int get() = missing.size + wrongColor.size + extra.size

    /** Whether the result matches the target exactly. */
    val isSolved: Boolean get() = errorCount == 0

    /** Coordinates that are correct in both grids. */
    fun correctCoords(target: VoxelGrid, result: VoxelGrid): List<Coord> =
        target.allCoords().filter { result[it] == target[it] }
}

/** A voxel that is filled on both sides but in the wrong colour. */
@Serializable
data class WrongVoxel(val coord: Coord, val expected: Int, val actual: Int)

/**
 * Compares a player's [result] against the level's [target].
 *
 * Size mismatches are reported as [SizeMismatch] rather than silently comparing
 * overlapping regions: a solution that renders a 2x2x2 grid in a 3x3x3 level is wrong
 * in a way that deserves its own message.
 */
object Verifier {
    fun diff(target: VoxelGrid, result: VoxelGrid): Result {
        if (target.size != result.size) return Result.SizeMismatch(target.size, result.size)

        val missing = mutableListOf<Coord>()
        val wrongColor = mutableListOf<WrongVoxel>()
        val extra = mutableListOf<Coord>()

        for (c in target.allCoords()) {
            val want = target[c]
            val got = result[c]
            when {
                want != Palette.EMPTY && got == Palette.EMPTY -> missing += c
                want == Palette.EMPTY && got != Palette.EMPTY -> extra += c
                want != got -> wrongColor += WrongVoxel(c, want, got)
            }
        }
        return Result.Graded(Diff(missing, wrongColor, extra))
    }

    sealed interface Result {
        data class Graded(val diff: Diff) : Result

        data class SizeMismatch(val expected: GridSize, val actual: GridSize) : Result
    }
}
