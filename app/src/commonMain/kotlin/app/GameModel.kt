package fr.godox.replikube.app

import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.Diff
import fr.godox.replikube.core.Level
import fr.godox.replikube.core.LoadedLevel
import fr.godox.replikube.core.Progress
import fr.godox.replikube.core.log.Log
import fr.godox.replikube.core.Stars
import fr.godox.replikube.core.Verifier
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.levels.LevelCatalog
import fr.godox.replikube.app.progress.ProgressStore
import fr.godox.replikube.scripting.Diagnostic
import fr.godox.replikube.scripting.RunResult
import fr.godox.replikube.scripting.ScriptRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the result panel is currently showing. */
sealed interface RunOutcome {
    /** Never run yet. */
    data object Idle : RunOutcome

    /** Compiling or evaluating; player code is on a background thread. */
    data object Running : RunOutcome

    /** Compiled and graded, but not an exact match. */
    data class Unsolved(val diff: Diff) : RunOutcome

    /** An exact match, with the stars earned. */
    data class Solved(val stars: Stars) : RunOutcome

    /** A compile, contract, timeout or runtime failure. */
    data class Failed(val diagnostic: Diagnostic) : RunOutcome
}

/**
 * Everything the UI renders, in one immutable snapshot.
 *
 * Compose wants a single observable state object so a recomposition cannot read half of an
 * update. Holding mutable fields separately would let the viewport show the new grid beside
 * the old level title.
 */
data class GameState(
    /** Levels whose metadata is known, in curriculum order. */
    val levels: List<Level> = emptyList(),
    val currentLevelId: String? = null,
    /** Null while the level's reference solution is still compiling. */
    val level: LoadedLevel? = null,
    val levelLoading: Boolean = false,
    val levelError: String? = null,
    val code: String = "",
    val outcome: RunOutcome = RunOutcome.Idle,
    /** What the player's code produced, or null before the first run. */
    val result: VoxelGrid? = null,
    val hintVisible: Boolean = false,
    val progress: Progress = Progress(),
    val saveFailed: Boolean = false,
) {
    /** The shape this level asks for. */
    val target: VoxelGrid? get() = level?.target

    /**
     * Voxels to outline in the player's pane.
     *
     * Wrong colour and extra cubes, both of which are visible mistakes the player can act
     * on. Missing cubes are deliberately excluded: the target is drawn in full in the pane
     * above, so the hole is already visible, and outlining the gap here as well would say
     * the same thing twice.
     */
    val errorCoords: Set<Coord>
        get() = when (val outcome = outcome) {
            is RunOutcome.Unsolved ->
                (outcome.diff.wrongColor.map { it.coord } + outcome.diff.extra).toSet()
            else -> emptySet()
        }

    val isSolved: Boolean get() = outcome is RunOutcome.Solved

    /** Stars already earned on this level, whether or not it is being played now. */
    val bestStars: Stars
        get() = currentLevelId?.let { progress[it].stars } ?: Stars.NONE

    /** Whether the reference solution may be shown. */
    val canRevealReference: Boolean get() = isSolved

    /** Non-blank lines of [code], which is what stars are scored against. */
    val codeLineCount: Int get() = code.lines().count { it.isNotBlank() }
}

/**
 * Owns game state and every action that can change it.
 *
 * The UI calls [openLevel] and [run]; it never touches the runner or the store. That split
 * is what keeps the Compose layer free to be replaced, and it means the state machine —
 * which is where the actual rules live — is testable without a window.
 *
 * Compilation runs off the main thread and is cancellable: a player who hits Run twice, or
 * clicks a different level mid-compile, must not have the first result land on the second
 * level.
 */
class GameModel(
    private val catalog: LevelCatalog,
    private val runner: ScriptRunner,
    private val progressStore: ProgressStore,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    /**
     * Builds a model with the pieces each target has to choose for itself.
     *
     * ### Why this exists instead of constructor defaults
     *
     * The parameters used to default to `LevelCatalog()`, `InProcessScriptRunner()` and
     * `ProgressStore()`, and that worked only because this module was JVM-only:
     * `InProcessScriptRunner` embeds the Kotlin compiler and cannot exist in a browser, and
     * `ProgressStore` defaulted to a `java.nio` path.
     *
     * There is no default that is right on both targets, so there is no default. A factory
     * named for the decision it encapsulates keeps that visible at the composition root --
     * where the platform *is* known -- instead of hiding it behind a signature that used to
     * imply every target could supply its own.
     *
     * [runner] is required even though both targets do have one, because which runner to use
     * is the central open question of the wasm port (an embedded compiler cannot run in a
     * browser, so the wasm build must send the generated source to a compiler service). Making
     * it a parameter means answering that question once, in `main`, rather than discovering it
     * through a missing class at link time.
     */
    class Factory(
        private val catalog: LevelCatalog,
        private val progressStore: ProgressStore,
    ) {
        /**
         * Builds the model, asking the platform for a [ScriptRunner].
         *
         * The `expect` here is a single function returning an interface. It is the one place
         * where "which compiler" is decided, and it has to be one function: a wasm build
         * cannot have a default argument that constructs a JVM-only class, because the
         * compiler resolves defaults on every target whether or not they are used.
         */
        fun create(): GameModel =
            GameModel(
                catalog = catalog,
                runner = platformScriptRunner(),
                progressStore = progressStore,
            )
    }

    private val _state = MutableStateFlow(GameState(levels = catalog.levels))
    val state: StateFlow<GameState> = _state.asStateFlow()

    private var runJob: Job? = null
    private var loadJob: Job? = null

    init {
        val loaded = progressStore.load()
        _state.value = _state.value.copy(progress = loaded)
    }

    /** The starter code for [level], or the player's last working attempt at it. */
    fun starterCode(level: Level): String =
        _state.value.progress[level.id].lastSolution?.takeIf { it.isNotBlank() }
            ?: SolutionTemplates.forLevel(level)

    /**
     * Switches to [levelId], turning its stored matrix into the target grid.
     *
     * A pending level load is cancelled first. Grids are cached by [LevelCatalog], so returning
     * to a level is instant. Nothing is compiled here — the target is data.
     */
    fun openLevel(levelId: String) {
        loadJob?.cancel()
        runJob?.cancel()
        val metadata = catalog.metadata(levelId)
        if (metadata == null) {
            // The catalog has already logged which ids it does know, so this line says
            // exactly what was asked for and what was on offer.
            Log.e(TAG, "openLevel('$levelId') -> unknown id; nothing was opened")
            return
        }
        Log.i(TAG, "openLevel('$levelId') -> \"${metadata.title}\" (${metadata.size})")

        val restored = _state.value.progress[levelId].lastSolution
        _state.value = _state.value.copy(
            currentLevelId = levelId,
            level = null,
            levelLoading = true,
            levelError = null,
            code = restored ?: SolutionTemplates.forLevel(metadata),
            outcome = RunOutcome.Idle,
            result = null,
            hintVisible = false,
        )

        loadJob = scope.launch {
            // Result.getOrNull rather than an is-success branch: `Result.Failure` is
            // internal, so it cannot be named in an `is` check from outside its module.
            val outcome = catalog.load(levelId)
            val loaded = outcome.getOrNull()
            if (loaded != null) {
                Log.i(TAG, "openLevel('$levelId') -> level is on screen")
            } else {
                val cause = outcome.exceptionOrNull()
                Log.e(TAG, "openLevel('$levelId') -> could not read the target matrix", cause)
                Log.trace(TAG, cause ?: IllegalStateException("no exception recorded"))
            }
            _state.value = if (loaded != null) {
                _state.value.copy(level = loaded, levelLoading = false)
            } else {
                _state.value.copy(
                    levelLoading = false,
                    // Says "shape data", not "reference solution": the target is now stored as
                    // a matrix, so blaming the solution would point the player at the wrong file.
                    // The log carries the real cause.
                    levelError = "Could not read this level's shape data.",
                )
            }
        }
    }

    /**
     * Compiles and runs the current code, then grades it against the target.
     *
     * Cancels any run already in flight: the player gets the answer to the code they can
     * currently see, not to an earlier edit.
     */
    fun run() {
        val level = _state.value.level ?: return
        val code = _state.value.code

        runJob?.cancel()
        _state.value = _state.value.copy(
            outcome = RunOutcome.Running,
            levelError = null,
            progress = progressStore.recordAttempt(_state.value.progress, level.id),
        )

        runJob = scope.launch {
            // Counted before compiling: compiling is a fixed cost, and the difference
            // between par and not is decided by the player's lines, not the clock.
            val lines = _state.value.codeLineCount

            // `runner.run` is a suspend function that already moves itself off the caller's
            // thread where it needs to: the JVM implementation compiles on a worker thread it
            // owns, and the remote one awaits a network request. So there is deliberately no
            // `withContext(Dispatchers.IO)` around it here.
            //
            // That call did exist, and it was wrong in both directions. `Dispatchers.IO` does
            // not exist on wasmJs, and on the JVM it was redundant with the runner's own
            // thread management -- so it bought nothing and cost a portability seam.
            when (val outcome = runner.run(code, level.level.size)) {
                is RunResult.Failure -> _state.value = _state.value.copy(
                    outcome = RunOutcome.Failed(outcome.diagnostic),
                    result = null,
                )

                is RunResult.Success -> grade(level, outcome.grid, code, lines)
            }
        }
    }

    private fun grade(level: LoadedLevel, result: VoxelGrid, code: String, lines: Int) {
        when (val verified = level.verify(result)) {
            is Verifier.Result.SizeMismatch -> _state.value = _state.value.copy(
                outcome = RunOutcome.Failed(
                    Diagnostic(
                        Diagnostic.Kind.CONTRACT_ERROR,
                        "That solution rendered a ${verified.actual.x}×${verified.actual.y}×${verified.actual.z} grid, " +
                            "but this level is ${verified.expected.x}×${verified.expected.y}×${verified.expected.z}. " +
                            "Use Level.sizeX, Level.sizeY and Level.sizeZ.",
                    ),
                ),
                result = result,
            )

            is Verifier.Result.Graded -> {
                val diff = verified.diff
                if (diff.isSolved) {
                    val stars = Stars.forLines(lines, level.level.par)
                    val progress = progressStore.recordSolve(_state.value.progress, level.id, stars, code)
                    persist(progress)
                    _state.value = _state.value.copy(
                        outcome = RunOutcome.Solved(stars),
                        result = result,
                        progress = progress,
                    )
                } else {
                    _state.value = _state.value.copy(
                        outcome = RunOutcome.Unsolved(diff),
                        result = result,
                    )
                }
            }
        }
    }

    /** Replaces the editor contents. */
    fun setCode(code: String) {
        _state.value = _state.value.copy(code = code)
    }

    /** Toggles the level's single free hint. */
    fun toggleHint() {
        _state.value = _state.value.copy(hintVisible = !_state.value.hintVisible)
    }

    /** Puts the reference solution into the editor, for a level the player has already beaten. */
    fun revealReference() {
        val level = _state.value.level ?: return
        if (!_state.value.canRevealReference) return
        _state.value = _state.value.copy(code = level.reference)
    }

    /** Clears the editor back to the starter for the current level. */
    fun resetCode() {
        val metadata = _state.value.currentLevelId?.let { catalog.metadata(it) } ?: return
        _state.value = _state.value.copy(code = SolutionTemplates.forLevel(metadata))
    }

    /** Starts a fresh run at [levelId]. */
    fun start(levelId: String) = openLevel(levelId)

    private fun persist(progress: Progress) {
        if (!progressStore.save(progress)) {
            _state.value = _state.value.copy(saveFailed = true)
        }
    }
}

/** Editor starters, one per level id, with a shared fallback. */
object SolutionTemplates {

    /**
     * The starting point for [level].
     *
     * A *body*, not a declaration — the game supplies `block`'s signature. Early levels get
     * a near-complete answer so the first thing a player does is edit rather than type;
     * later ones get a bare `return null` so the contract is still visible but the first
     * idea is theirs.
     *
     * Tutorial starters deliberately *solve* their level. Reading a working answer and
     * changing it is the lowest-friction way into the game, and it is why the test harness
     * has to be careful about telling a player's attempt from a reference solution.
     */
    fun forLevel(level: Level): String = when (level.id) {
        "hello-layers" -> """
            |// One colour per layer of the grid.
            |return when (y) {
            |    1 -> RED
            |    0 -> YELLOW
            |    else -> GREEN
            |}
        """.trimMargin() + "\n"

        "single-voxel" -> """
            |// A single cube, right in the middle. Coordinates run -1..1.
            |return if (x == 0 && y == 0 && z == 0) MAGENTA else null
        """.trimMargin() + "\n"

        "checkerboard" -> """
            |// Every other voxel, on all three axes at once.
            |return if (parity(x + y + z) == 0) BLUE else null
        """.trimMargin() + "\n"

        else -> """
            |// Return a colour for each voxel, or null to leave it empty.
            |//
            |// x, y, z are centred on the origin: -${level.size.x / 2}..${(level.size.x - 1) / 2}
            |// and the same for y and z. The bottom layer is y = ${-level.size.y / 2}.
            |return null
        """.trimMargin() + "\n"
    }
}

/** Log tag for this file, so one `grep` isolates the model from the catalog's own lines. */
private const val TAG = "GameModel"