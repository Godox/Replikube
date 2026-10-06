package fr.godox.replikube.scripting

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.core.coords
import fr.godox.replikube.dsl.Palette
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The wire format of the compiler service, and everything that can be decided without a network.
 *
 * ### Why this file is `commonMain` and not `wasmJsMain`
 *
 * Because none of it needs a browser. The shapes below are decoded from a string, the harness is
 * assembled from a string, and a response is turned into a [RunResult] from a string -- so all of
 * it is exercised by `RemoteProtocolTest` on the JVM, with recorded response bodies, in
 * milliseconds, and with no dependency on a third-party service staying up.
 *
 * Only the HTTP call is wasm-only. That split is the difference between a protocol that is
 * tested and a protocol that is merely exercised by hand once.
 *
 * ### The service
 *
 * `api.kotlinlang.org/api/<kotlin-version>/compiler/run` is the backend behind
 * `play.kotlinlang.org`: it takes a set of source files, compiles them, runs `main`, and returns
 * the diagnostics and the program's output. It is what a browser build has instead of an
 * embedded compiler, because there is no JVM here to embed one in.
 *
 * The request and response shapes below were read off the live service rather than guessed, and
 * the details that matter are the ones a reasonable guess would get wrong:
 *
 * - `errors` is a **map keyed by file name**, so a failure in the shipped `:dsl` sources is
 *   distinguishable from a failure in the player's code. That distinction decides whether the
 *   player is told they made a mistake.
 * - Diagnostics carry `severity`, and **warnings are included**. A solution with an unused
 *   variable comes back with `severity: WARNING` entries; treating "any entry in `errors`" as a
 *   failure would refuse to run code that compiled and ran perfectly. Only `ERROR` fails.
 * - `interval.start.line` is **0-based**. [GeneratedSource.toPlayerLine] expects the 1-based
 *   number the compiler prints in a message, hence the `+ 1`.
 * - A program that overruns the service's own watchdog returns **HTTP 200**, no `exception`, and
 *   the message in `text` wrapped in `<errStream>`. There is no status code to detect.
 * - `text` wraps stdout in `<outStream>`/`</outStream>`.
 */
@Serializable
data class CompilerRunRequest(
    /** Compiler arguments. Empty: the service compiles a fixed project layout. */
    val args: String = "",
    val files: List<SourceFile>,
    /** Share handle. Empty, because nothing here is published. */
    val publicId: String = "",
    /** Build the JVM variant. `Solution` is a JVM class and the stdlib is the JVM one. */
    val confType: String = CONF_TYPE_JVM,
) {
    companion object {
        const val CONF_TYPE_JVM: String = "java"
    }
}

/** One source file in a [CompilerRunRequest]. [name] must end in `.kt`. */
@Serializable
data class SourceFile(val name: String, val text: String)

/** What the service returns. Every field has a default, because a service is a stranger. */
@Serializable
data class CompilerRunResponse(
    /**
     * Diagnostics by file name. Present for files with none too, as an empty list.
     *
     * `null`-tolerant on read: a missing key is treated as "no diagnostics", not as a parse
     * failure, because a response that omits the field entirely is still a response.
     */
    val errors: Map<String, List<CompilerDiagnostic>> = emptyMap(),
    /** The exception the program threw, if any. A clean exit leaves this `null`. */
    val exception: RemoteException? = null,
    /** stdout and stderr, wrapped in `<outStream>`/`</outStream>` or `<errStream>`/`<errStream>`. */
    val text: String = "",
)

/** One compiler message. */
@Serializable
data class CompilerDiagnostic(
    /** Where in the file. `null` for a whole-file message such as "too many errors". */
    val interval: SourceInterval? = null,
    val message: String,
    /** `ERROR` or `WARNING`. Anything else is treated as non-fatal; see [isError]. */
    val severity: String,
    /** The enclosing declaration's name, e.g. `Solution`. Diagnostic context only. */
    val className: String? = null,
) {
    /**
     * Whether this message means the build failed.
     *
     * Only `ERROR`, and by name. Warnings are returned for ordinary, correct solutions -- an
     * unused variable in the editor's starter text, for instance -- and this project does not
     * ask players to be free of warnings, so failing on one would reject working code for a
     * cosmetic reason. An unrecognised severity is also not fatal: a new severity is more likely
     * to be a warning than a reason to refuse to show the player a correct result.
     */
    val isError: Boolean get() = severity == SEVERITY_ERROR

    companion object {
        const val SEVERITY_ERROR: String = "ERROR"
        const val SEVERITY_WARNING: String = "WARNING"
    }
}

/** A half-open source range, [line] 0-based and [ch] a character offset within it. */
@Serializable
data class SourceInterval(val start: SourcePosition, val end: SourcePosition)

/**
 * A position in a source file.
 *
 * [line] is **0-based**, which is the service's convention and the opposite of [Diagnostic.line].
 * The conversion happens once, in [RemoteResponse.toRunResult], so the mistake has one place to
 * live.
 */
@Serializable
data class SourcePosition(val line: Int, val ch: Int)

/** An exception the evaluated program threw. */
@Serializable
data class RemoteException(
    val message: String? = null,
    /** e.g. `java.lang.IllegalStateException`. */
    val fullName: String? = null,
    val stackTrace: List<RemoteStackFrame> = emptyList(),
)

/** One frame of a [RemoteException]. */
@Serializable
data class RemoteStackFrame(
    val className: String? = null,
    val methodName: String? = null,
    val fileName: String? = null,
    /** `-1` and `-2` mean "unknown" and "native", as they do in a JVM trace. */
    val lineNumber: Int? = null,
)

/**
 * The decoder. Lenient, because the alternative is a player staring at "Unexpected JSON token"
 * instead of a result.
 *
 * `ignoreUnknownKeys` is the important one: the service is free to add a field, and a game
 * pinned to an exact schema breaks on a Tuesday because of it. Unknown keys are dropped.
 */
internal val RemoteJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/**
 * The prefix the harness prints its answer on.
 *
 * A marker rather than assuming the whole of stdout is the payload, because player code can
 * `println` and a `println("hello")` in a solution must not corrupt the result. Deliberately
 * unlikely to be produced by accident: it is not a Kotlin expression, and the harness is the only
 * thing in the program that prints it.
 */
const val GRID_MARKER: String = "REPLIKUBE-GRID:"

/** File name the harness is sent under. Also the key its diagnostics are reported against. */
const val HARNESS_FILE_NAME: String = "Solution.kt"

/**
 * A runnable program: the files to post, and the line mapping needed to report a compile error
 * against the player's own source.
 *
 * Assembled in [build] and otherwise inert, so a test can assert the harness's *shape* -- that
 * the player's code sits at [GeneratedSource.playerLineOffset], that the driver comes after it --
 * without a compiler anywhere.
 */
class RemoteProgram private constructor(
    val files: List<SourceFile>,
    val generated: GeneratedSource,
) {
    companion object {
        /**
         * Wrap [playerCode] for a grid of [size], with a `main` that prints the filled grid.
         *
         * The wrapper is [SolutionCompiler]'s, unchanged, and the driver is appended after it.
         * Appending rather than interleaving is what keeps [GeneratedSource.playerLineOffset]
         * honest: every line the player owns sits at the same offset as in the local runner, so
         * one mapping function serves both, and a player who learns "the error is on line 3 of my
         * code" is not learning it twice.
         *
         * The `:dsl` sources are sent first and the harness last. Order does not affect
         * compilation, but it does affect the log when a request fails to build, and "the third
         * file is the player's" is easier to reason about than "file 3 of 4".
         */
        fun build(playerCode: String, size: GridSize): RemoteProgram {
            val generated = SolutionCompiler.generate(playerCode, size)
            val dslFiles = DSL_SOURCE.entries
                .sortedBy { it.key }
                .map { (name, text) -> SourceFile(name, text) }
            return RemoteProgram(
                files = dslFiles + SourceFile(HARNESS_FILE_NAME, generated.text + DRIVER),
                generated = generated,
            )
        }
    }
}

/**
 * The `main` that turns a solution into a grid. Appended after [SolutionCompiler]'s wrapper.
 *
 * The loop order is **x fastest, then y, then z**, because that is `GridSize.indexOf` -- the one
 * definition of the grid's memory layout, which the parsed result is read back in. A different
 * order here would not fail loudly; it would transpose a level's shape, which is the worst
 * possible failure for this feature.
 *
 * The fill happens server-side and the answer comes back in stdout, because there is no other
 * channel out of the service. So the grid is printed as one comma-separated run on a single
 * line: 729 voxels of the largest level is under 2.5 KB, well inside what the service returns
 * unclipped, and one line cannot be split by the player's own output.
 *
 * A top-level `const` rather than an interpolated value so that the literal stays greppable: this
 * text is also what [RemoteProgramTest] asserts the marker appears in, and a `$GRID_MARKER` that
 * resolves at runtime would make that assertion read as a coincidence.
 */
private val DRIVER: String = """

fun main() {
    val solution = Solution()
    val out = StringBuilder()
    for (z in Level.minZ..Level.maxZ) {
        for (y in Level.minY..Level.maxY) {
            for (x in Level.minX..Level.maxX) {
                // `?: 0` folds null into EMPTY on the server, so the wire format carries only
                // Ints and nothing downstream ever learns that `null` existed -- the same fold
                // `asVoxelProgram` does on the local path.
                out.append(solution.block(x, y, z) ?: 0).append(',')
            }
        }
    }
    println("$GRID_MARKER" + out)
}
"""

/**
 * Turn a service response into a [RunResult].
 *
 * The order of the checks is the order of what a player is most likely to have done wrong:
 * their code failed to compile, then their code threw, then their code ran too long, and only
 * then does the answer get parsed. A response can be wrong in more than one way at once -- the
 * service reports the compiler errors of a program that never ran -- and reporting the compile
 * error is the one that can be acted on.
 *
 * Every branch produces a [Diagnostic] rather than throwing. The wasm entry point has no other
 * way to report a problem, and an exception crossing back into Compose would take down the game
 * to tell the player something a sentence would have told them.
 */
fun CompilerRunResponse.toRunResult(size: GridSize, program: RemoteProgram): RunResult {
    // 1. Did it compile? Only the harness's file is the player's; a DSL file failing is ours.
    val harnessErrors = errors.filterKeys { it == HARNESS_FILE_NAME }
        .values.flatten().filter { it.isError }
    if (harnessErrors.isNotEmpty()) return compileFailure(harnessErrors, program)

    val dslErrors = errors.filterKeys { it != HARNESS_FILE_NAME }
        .values.flatten().filter { it.isError }
    if (dslErrors.isNotEmpty()) return dslFailure(dslErrors)

    // 2. Did it throw?
    exception?.let { return runtimeFailure(it, program) }

    // 3. Did the service give up on it?
    val kind = StreamKind.of(text)
    val payload = kind.unwrap(text)
    if (kind == StreamKind.STDERR) return watchdogFailure(payload)

    // 4. Parse the answer.
    val line = payload.lineSequence().firstOrNull { it.startsWith(GRID_MARKER) }
        ?: return RunResult.Failure(
            Diagnostic(
                kind = Diagnostic.Kind.INTERNAL_ERROR,
                message = "The compiler service did not return a result.",
                detail = "Expected a line beginning with $GRID_MARKER. It returned " +
                    "${payload.length} characters of output: " + payload.take(200),
            ),
        )

    return parseGrid(line.removePrefix(GRID_MARKER), size)
}

/** Which of the service's two stream wrappers a payload arrived in. */
private enum class StreamKind(val tag: String) {
    /** Normal completion: the program wrote to stdout. */
    STDOUT("outStream"),

    /** The service's watchdog stopped evaluation, or the program wrote to stderr. */
    STDERR("errStream"),
    ;

    /** The content between the tags, or [text] unchanged when it carries no tags at all. */
    fun unwrap(text: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        return if (text.startsWith(open) && text.endsWith(close)) {
            text.substring(open.length, text.length - close.length)
        } else {
            text
        }
    }

    companion object {
        /**
         * Classify [text], tolerating the empty string.
         *
         * A compile error leaves `text` empty rather than absent, and that is not a stream: there
         * is no program output to speak of. Classified as [STDOUT] so the caller reaches the
         * "no result marker" diagnostic, which is the true statement, instead of blaming stderr.
         */
        fun of(text: String): StreamKind = if (text.startsWith("<errStream>")) STDERR else STDOUT
    }
}

/** The compiler failed on the player's code. */
private fun compileFailure(
    errors: List<CompilerDiagnostic>,
    program: RemoteProgram,
): RunResult.Failure {
    val first = errors.first()
    // 0-based from the service, 1-based here: the one conversion, done once.
    val playerLine = first.interval?.let { program.generated.toPlayerLine(it.start.line + 1) }
    return RunResult.Failure(
        Diagnostic(
            kind = Diagnostic.Kind.COMPILE_ERROR,
            message = first.message,
            line = playerLine,
            snippet = playerLine?.let { program.generated.playerLines.getOrNull(it - 1) },
            // `null` line, non-null generated line: the player wrote an unbalanced bracket, so
            // the compiler blamed the wrapper. This says so in as many words, because the
            // alternative is an error on a line of generated source the player cannot see.
            detail = wrapperHint(playerLine, first.interval?.start?.line),
        ),
    )
}

/** The shipped `:dsl` sources did not compile. Not the player's fault, and not fixable by them. */
private fun dslFailure(errors: List<CompilerDiagnostic>): RunResult.Failure = RunResult.Failure(
    Diagnostic(
        kind = Diagnostic.Kind.INTERNAL_ERROR,
        message = "replikube's own player API failed to compile.",
        detail = errors.joinToString("\n") { d ->
            val where = d.fileLabel()
            if (where != null) "$where: ${d.message}" else d.message
        },
    ),
)

/** The program compiled and then threw. */
private fun runtimeFailure(
    thrown: RemoteException,
    program: RemoteProgram,
): RunResult.Failure {
    // The topmost frame in the harness file is the player's own line; frames deeper in are the
    // service's runtime and are noise in a result panel.
    val playerFrame = thrown.stackTrace.firstOrNull { it.fileName == HARNESS_FILE_NAME }
    val playerLine = playerFrame?.lineNumber
        ?.takeIf { it > 0 }
        ?.let { program.generated.toPlayerLine(it) }
    val where = playerFrame?.let { "${it.className}.${it.methodName}" }
    return RunResult.Failure(
        Diagnostic(
            kind = Diagnostic.Kind.RUNTIME_ERROR,
            message = thrown.message?.takeIf { it.isNotBlank() } ?: "Your solution threw an exception.",
            line = playerLine,
            snippet = playerLine?.let { program.generated.playerLines.getOrNull(it - 1) },
            detail = listOfNotNull(thrown.fullName, where).joinToString(" — ").ifEmpty { null },
        ),
    )
}

/**
 * The service stopped evaluating the program.
 *
 * The message is quoted verbatim rather than paraphrased, because this is the one timeout whose
 * exact budget the player cannot know: it is the service's, not the game's.
 */
private fun watchdogFailure(detail: String): RunResult.Failure = RunResult.Failure(
    Diagnostic(
        kind = Diagnostic.Kind.TIMEOUT,
        message = "Your solution took too long, so the compiler service stopped running it.",
        detail = detail.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: "The service reported no reason.",
    ),
)

/**
 * Read the printed grid.
 *
 * Failures here are a contract violation between this file and [RemoteProgram.driver], or player
 * code printing something that survived the marker check -- not something a player can fix. Both
 * are reported as [Diagnostic.Kind.CONTRACT_ERROR] because the player *is* responsible for the
 * value: it came out of their `block`.
 *
 * A cell count that does not match the grid is checked before anything is indexed, so a wrong
 * length is a message rather than an `IndexOutOfBoundsException`.
 */
private fun parseGrid(payload: String, size: GridSize): RunResult {
    val parts = payload.split(',')
    // The driver always leaves a trailing separator, so the last element is empty. Detected by
    // shape alone rather than by also requiring the count to match: an answer that is the wrong
    // length is exactly the case that has to produce a readable message, and folding the
    // separator check into the size check would report it one cell too many and name a
    // coordinate the player never produced.
    val cells = if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts

    if (cells.size != size.voxelCount) {
        return RunResult.Failure(
            Diagnostic(
                kind = Diagnostic.Kind.CONTRACT_ERROR,
                message = "Your solution returned ${cells.size} values for a grid of " +
                    "${size.voxelCount} voxels.",
                detail = "replikube calls block() once per voxel, so the numbers have to line " +
                    "up with the grid. This usually means a loop or an early return changed " +
                    "the shape of the answer.",
            ),
        )
    }

    val values = IntArray(cells.size)
    for (i in cells.indices) {
        val id = cells[i].trim().toIntOrNull()
        if (id == null || !Palette.isValid(id)) {
            // Naming the coordinate is the point: "not a colour" alone sends the player hunting
            // through the whole grid, and this is the message that saves them the trip.
            val c = size.coords().elementAt(i)
            return RunResult.Failure(
                Diagnostic(
                    kind = Diagnostic.Kind.CONTRACT_ERROR,
                    message = "block(${c.x}, ${c.y}, ${c.z}) returned " +
                        "${cells[i].trim()}, which is not a colour.",
                    detail = "Use null (or EMPTY, 0) to leave a voxel empty, or a colour in " +
                        "1..${Palette.MAX}.",
                ),
            )
        }
        values[i] = id
    }
    return RunResult.Success(VoxelGrid.of(size, values))
}

/** Where the diagnostic sits, for a message about a file the player did not write. */
private fun CompilerDiagnostic.fileLabel(): String? {
    val file = className?.takeIf { it.isNotBlank() } ?: return null
    val at = interval?.start?.line?.let { "line ${it + 1}" }
    return if (at != null) "$file ($at)" else file
}

/**
 * Explains a compile error that landed on the wrapper rather than the player's code.
 *
 * Mirrors the local runner's wording on purpose: the cause is the same on both targets, so a
 * player who has seen it once should not have to learn a second phrasing.
 */
private fun wrapperHint(playerLine: Int?, generatedLine: Int?): String? = when {
    playerLine != null -> null
    generatedLine != null ->
        "Reported in the code replikube generates around yours (line $generatedLine). That " +
            "usually means a bracket is unbalanced, so the compiler lost track of which lines " +
            "are yours."
    else -> null
}