package fr.godox.replikube.scripting

/**
 * A player-facing failure, positioned well enough to highlight in the editor.
 *
 * Every failure mode a player can hit — a typo, a wrong `block` signature, an infinite
 * loop, a colour that isn't in the palette — becomes one of these. The UI renders
 * [message] and highlights [line]; [snippet] is the offending source line for context.
 */
data class Diagnostic(
    val kind: Kind,
    val message: String,
    /** 1-based line in the *player's* source, or `null` when not attributable. */
    val line: Int? = null,
    /** The player's source line at [line]. */
    val snippet: String? = null,
    /** Extra context shown under the message (a stack trace, a hint). */
    val detail: String? = null,
) {
    enum class Kind {
        /** The solution did not compile. */
        COMPILE_ERROR,

        /** Compiled, but violated the contract: missing `block`, bad colour id. */
        CONTRACT_ERROR,

        /** Threw while running. */
        RUNTIME_ERROR,

        /** Ran longer than the allowed budget. */
        TIMEOUT,

        /** The compiler or classloader failed in a way we did not anticipate. */
        INTERNAL_ERROR,
    }

    /** Single-line form for logs and test assertions. */
    override fun toString(): String = buildString {
        append(kind.name)
        if (line != null) append(" at line ").append(line)
        append(": ").append(message)
    }
}

/**
 * Outcome of running a solution: either the grid it produced, or a reason there isn't one.
 *
 * Evaluation happens inside [ScriptRunner.run] rather than being handed back as a lazy
 * program, so that runtime failures surface as a [Diagnostic] on the same path as compile
 * failures and under the same timeout. A caller that received a raw program would have to
 * reimplement exception-to-diagnostic mapping and the time budget.
 */
sealed interface RunResult {
    /** [grid] holds one colour id per voxel of the level, as produced by the solution. */
    data class Success(val grid: fr.godox.replikube.core.VoxelGrid) : RunResult

    data class Failure(val diagnostic: Diagnostic) : RunResult
}

/**
 * The sandbox boundary.
 *
 * Everything above this interface deals in [RunResult] and never sees a compiler, a
 * classloader, or a `kotlin.jvm.internal` type. `:core`, `:levels` and `:app` depend
 * on this and nothing deeper, which is what lets `InProcessScriptRunner` be swapped for a
 * forked-JVM implementation without touching game code.
 *
 * Implementations must be safe to call from any thread and must not assume a previous
 * run left state behind.
 */
interface ScriptRunner {
    /**
     * Compiles [source], then evaluates it over every voxel of a grid of [size].
     *
     * @param timeoutMillis wall-clock budget for compilation *and* execution combined
     */
    suspend fun run(
        source: String,
        size: fr.godox.replikube.core.GridSize,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): RunResult

    companion object {
        /** Generous enough for a cold Kotlin compile on a slow machine, short enough to feel responsive. */
        const val DEFAULT_TIMEOUT_MILLIS = 10_000L
    }
}
