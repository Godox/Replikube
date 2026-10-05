package fr.godox.replikube.scripting

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelProgram
import fr.godox.replikube.core.asVoxelProgram
import fr.godox.replikube.core.log.Log
import fr.godox.replikube.core.render
import fr.godox.replikube.dsl.Palette
import fr.godox.replikube.dsl.Replikube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.CoreEnvironmentDeprecation
import org.jetbrains.kotlin.cli.FrontendConfigurationKeys
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.config.JvmTarget
import org.jetbrains.kotlin.config.Services
import java.io.File
import java.nio.file.Files

/**
 * Compiles and runs player code in-process, driving the embedded Kotlin compiler.
 *
 * ### What the player writes
 *
 * The body of `block`, and nothing else — see [SolutionCompiler]. The signature is
 * generated, so a player cannot get it wrong, and the body returns `Int?` so `null` can
 * mean "no cube".
 *
 * ### Why not `.kts` and `BasicKotlinScriptEngine`
 *
 * The design assumed `BasicKotlinScriptEngine` from `kotlin-scripting-jvm`. As of Kotlin
 * 2.4.20 that class no longer ships, nor does the `kotlinx.scripting.experimental`
 * facade — only the low-level `ScriptEvaluator` interface remains, with no public way to
 * build one. Rather than bind to internal compiler plumbing, we call [K2JVMCompiler]
 * directly.
 *
 * ### Safety posture
 *
 * In-process, so a solution can loop forever, exhaust memory or call `System.exit`.
 * Compilation and execution run on a daemon thread; a timeout abandons the run but
 * cannot stop a thread that is already spinning. Acceptable for an offline single-player
 * puzzle game, and [ScriptRunner] exists so a forked-JVM implementation can replace this
 * without touching game code.
 *
 * ### Threading
 *
 * Safe to call concurrently: each run compiles into its own output directory and loads
 * its own classloader. The compiler itself is instantiated per run — it is not cheap,
 * but a game runs one solution at a time.
 */
class InProcessScriptRunner(
    private val defaultTimeoutMillis: Long = ScriptRunner.DEFAULT_TIMEOUT_MILLIS,
    private val workDir: File = defaultWorkDir(),
) : ScriptRunner {

    override suspend fun run(source: String, size: GridSize, timeoutMillis: Long): RunResult =
        withContext(Dispatchers.IO) {
            val budget = timeoutMillis.coerceAtMost(defaultTimeoutMillis)
            // Recorded before the temp directory is made, because a packaged app can fail
            // *here* — an AppImage is mounted read-only over FUSE, and if the configured
            // work directory landed on that mount there is no output directory to compile
            // into and no error message that says so.
            Log.path("runner.workDir", workDir)
            Log.i(TAG, "run() start: ${source.length} chars, grid $size, budget ${budget}ms")

            val runDir = runCatching { Files.createTempDirectory(workDir.toPath(), "run-").toFile() }
                .getOrElse { e ->
                    Log.e(TAG, "could not create a run directory under ${workDir.absolutePath}", e)
                    return@withContext RunResult.Failure(
                        Diagnostic(
                            kind = Diagnostic.Kind.INTERNAL_ERROR,
                            message = "replikube could not create a place to compile your solution.",
                            detail = "${e::class.java.name}: ${e.message}",
                        ),
                    )
                }
            Log.i(TAG, "run() work directory: ${runDir.absolutePath}")

            try {
                executeWithBudget(source, size, budget, runDir)
            } finally {
                runDir.deleteRecursively()
            }
        }

    private fun executeWithBudget(
        source: String,
        size: GridSize,
        budget: Long,
        runDir: File,
    ): RunResult {
        var result: RunResult? = null
        val worker = Thread({
            result = try {
                build(source, size, runDir)
            } catch (t: Throwable) {
                // Logged here because this is the one failure path that reaches the player as a
                // bare "replikube could not run your solution" with no context. Every other path
                // names itself in the log before it fails; this one used to be the single blind
                // spot in the whole runner.
                Log.e(TAG, "run() threw out of build()", t)
                RunResult.Failure(internalDiagnostic(t, t.stackTraceToString()))
            }
        }, "replikube-solution").apply { isDaemon = true }

        worker.start()
        worker.join(budget)

        if (worker.isAlive) {
            return RunResult.Failure(
                Diagnostic(
                    kind = Diagnostic.Kind.TIMEOUT,
                    message = "Your solution took longer than ${budget}ms, so we stopped waiting.",
                    detail = "Look for a loop that never finishes.",
                ),
            )
        }
        return result ?: RunResult.Failure(
            Diagnostic(Diagnostic.Kind.INTERNAL_ERROR, "The solution produced no result."),
        )
    }

    /** Compile, load, then evaluate over the whole grid. Internal so tests can skip the thread wrapper. */
    internal fun build(source: String, size: GridSize, runDir: File): RunResult {
        if (source.isBlank()) {
            // Without this, an empty editor compiles to a block body with no `return`, and
            // the player gets "a return expression required" — which is technically true
            // and completely unhelpful.
            return RunResult.Failure(
                Diagnostic(
                    Diagnostic.Kind.CONTRACT_ERROR,
                    "There is nothing to run yet.",
                    detail = "Write the body of the solution: an expression of a colour, " +
                        "or `null` to leave a voxel empty.",
                ),
            )
        }

        val generated = SolutionCompiler.generate(source, size)
        val sourceFile = File(runDir, "Solution.kt")
        sourceFile.writeText(generated.text)

        val classesDir = File(runDir, "classes").apply { mkdirs() }
        val collector = CollectingMessageCollector(generated)
        val rootDisposable = Disposable { }

        Log.i(TAG, "compile() start: ${runDir.absolutePath}")

        val exit = try {
            newEnvironment(collector, rootDisposable)
            // Services.EMPTY rather than the project's service container: we compile no
            // compiler plugins, and resolving extensions through the project throws
            // "Extensions storage is not registered" in a standalone embeddable setup.
            K2JVMCompiler().exec(collector, Services.EMPTY, compilerArguments(sourceFile, classesDir))
        } catch (t: Throwable) {
            // The environment and the compiler are the two steps most likely to fail only
            // under packaging — a missing jpackage module or a sealed jar both throw here,
            // with a message that means nothing to a player but everything to whoever reads
            // the log. The generated source goes with it: a compiler error is often about a
            // wrapper line the player never sees.
            Log.e(TAG, "compile() threw before producing an exit code", t)
            Log.trace(TAG, t)
            Log.i(TAG, "generated source was:\n${generated.text}")
            throw t
        } finally {
            rootDisposable.dispose()
        }
        Log.i(TAG, "compile() finished with exit code $exit, errors=${collector.hasErrors()}")

        if (exit != ExitCode.OK || collector.hasErrors()) {
            Log.i(TAG, "compile() failed; collector said:\n${collector.dump()}")
            return RunResult.Failure(
                collector.toDiagnostic()
                    ?: Diagnostic(Diagnostic.Kind.COMPILE_ERROR, "Your solution did not compile."),
            )
        }

        // The compiler can exit OK and still emit no class files, and the usual cause is a
        // destination it could not write to. Counted explicitly so the log distinguishes
        // "nothing compiled" from "compiled the wrong thing".
        Log.path("compile() destination", classesDir)

        return when (val loaded = loadAndWrap(classesDir)) {
            is ProgramLoad.Rejected -> {
                Log.e(TAG, "load() rejected the compiled output: ${loaded.diagnostic}")
                RunResult.Failure(loaded.diagnostic)
            }

            is ProgramLoad.Loaded -> evaluate(loaded.program, size)
        }
    }

    /**
     * Call the solution once per voxel, turning anything it throws into a diagnostic.
     *
     * The player sees an error message instead of a stack trace escaping through the
     * renderer, and the whole fill shares the compile+run timeout from [run].
     */
    private fun evaluate(program: VoxelProgram, size: GridSize): RunResult = try {
        val started = System.currentTimeMillis()
        val grid = program.render(size)
        Log.i(TAG, "evaluate() ok: ${grid.solidCount} solid voxels in ${System.currentTimeMillis() - started}ms")
        RunResult.Success(grid)
    } catch (t: Throwable) {
        Log.e(TAG, "evaluate() threw", t)
        Log.trace(TAG, t)
        RunResult.Failure(internalDiagnostic(t, t.stackTraceToString()))
    }

    @OptIn(CompilerConfiguration.Internals::class, CoreEnvironmentDeprecation::class)
    @Suppress("DEPRECATION")
    private fun newEnvironment(collector: MessageCollector, parent: Disposable): KotlinCoreEnvironment =
        KotlinCoreEnvironment.createForProduction(
            parent,
            CompilerConfiguration().apply {
                put(CommonConfigurationKeys.MODULE_NAME, MODULE_NAME)
                // The collector is handed to exec(...) explicitly; putting it in the
                // configuration too is flagged as discouraged by the compiler API.
                put(CommonConfigurationKeys.USE_FIR, true)
                put(JVMConfigurationKeys.JVM_TARGET, playerTarget())

                // Required. `createForProduction` reads this unconditionally when it
                // configures the project environment, but only populates it via
                // ServiceLoader over `CompilerPluginRegistrar`. A plain application has
                // no compiler plugins on the classpath, so nothing registers the storage
                // and creation dies with "Extensions storage is not registered".
                // Seeding it with an empty storage is correct: we compile no plugins.
                @OptIn(ExperimentalCompilerApi::class)
                put(FrontendConfigurationKeys.EXTENSIONS_STORAGE, CompilerPluginRegistrar.ExtensionStorage())
            },
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )

    /** Load the compiled class and wrap it in a [VoxelProgram], validating the contract. */
    private fun loadAndWrap(classesDir: File): ProgramLoad {
        // Classes are read into memory rather than loaded straight from [classesDir]: the
        // program outlives this run, and by then the run's temp directory has already been
        // deleted. Anything the compiler left unloaded until first call would then fail to
        // resolve.
        val loader = ByteArrayClassLoader(javaClass.classLoader, readClasses(classesDir))
        val clazz = try {
            loader.loadClass(SOLUTION_CLASS_NAME)
        } catch (e: ClassNotFoundException) {
            return ProgramLoad.Rejected(
                Diagnostic(
                    Diagnostic.Kind.INTERNAL_ERROR,
                    "Compiled output did not contain $SOLUTION_CLASS_NAME.",
                    detail = e.message,
                ),
            )
        }

        val instance = try {
            clazz.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        } catch (e: ReflectiveOperationException) {
            return ProgramLoad.Rejected(
                Diagnostic(
                    Diagnostic.Kind.INTERNAL_ERROR,
                    "The compiled solution could not be instantiated.",
                    detail = e.message,
                ),
            )
        }

        if (instance !is Replikube) {
            return ProgramLoad.Rejected(
                Diagnostic(Diagnostic.Kind.INTERNAL_ERROR, "The compiled solution does not implement `Replikube`."),
            )
        }
        // `asVoxelProgram` folds `null` into `Palette.EMPTY`, so the validator below only
        // ever sees an `Int` and the rest of the game never learns that `null` existed.
        return ProgramLoad.Loaded(ValidatingProgram(instance.asVoxelProgram()))
    }

    private fun internalDiagnostic(t: Throwable, trace: String? = null): Diagnostic = when (t) {
        is InvalidColourException -> Diagnostic(
            kind = Diagnostic.Kind.CONTRACT_ERROR,
            message = t.message ?: "Your solution returned a value that is not a colour.",
        )

        is StackOverflowError -> Diagnostic(
            kind = Diagnostic.Kind.RUNTIME_ERROR,
            message = "Your solution recursed too deeply.",
            detail = "Check for a recursive function that never reaches its base case.",
        )

        is OutOfMemoryError -> Diagnostic(
            kind = Diagnostic.Kind.RUNTIME_ERROR,
            message = "Your solution ran out of memory.",
        )

        else -> Diagnostic(
            kind = Diagnostic.Kind.INTERNAL_ERROR,
            message = "replikube could not run your solution.",
            detail = listOfNotNull("${t::class.java.name}: ${t.message}", trace).joinToString("\n"),
        )
    }

    companion object {
        private const val MODULE_NAME = "replikube-solution"

        private fun defaultWorkDir(): File = run {
            val dir = Files.createTempDirectory("replikube").toFile()
            Log.i(TAG, "default work directory: ${dir.absolutePath}")
            dir
        }
    }
}

/** Log tag for everything in this file, so one `grep` isolates the compiler. */
private const val TAG = "ScriptRunner"

/**
 * Lowest bytecode version player code is ever compiled to.
 *
 * Matches the project's JVM toolchain, so a solution written on a new JDK still loads in an app
 * image whose bundled runtime is older. Declared at file level next to [playerTarget], which
 * reads it, rather than inside the companion where it would be invisible from top level.
 */
internal const val MIN_PLAYER_TARGET = 17

/** A compiled solution, or the reason it cannot be used. Internal to the compile+load step. */
private sealed interface ProgramLoad {
    data class Loaded(val program: VoxelProgram) : ProgramLoad
    data class Rejected(val diagnostic: Diagnostic) : ProgramLoad
}

/**
 * Thrown by [ValidatingProgram] when player code returns something that is not a colour.
 *
 * A dedicated type so [internalDiagnostic] can tell a palette violation (the player's
 * fault, worth explaining) apart from any other exception the solution throws.
 */
private class InvalidColourException(message: String) : IllegalArgumentException(message)

/** Rejects invalid palette ids where they are produced, so the error names the coordinate. */
private class ValidatingProgram(private val delegate: VoxelProgram) : VoxelProgram {
    override fun block(x: Int, y: Int, z: Int): Int {
        val color = delegate.block(x, y, z)
        if (!Palette.isValid(color)) {
            throw InvalidColourException(
                "block($x, $y, $z) returned $color, which is not a colour. " +
                    "Use EMPTY (0) or a colour in 1..${Palette.MAX}.",
            )
        }
        return color
    }
}

/** Gathers compiler messages and maps the first error back to a player-visible line. */
internal class CollectingMessageCollector(
    private val generated: GeneratedSource,
) : MessageCollector {

    private data class Message(
        val severity: CompilerMessageSeverity,
        val text: String,
        val location: CompilerMessageSourceLocation?,
    )

    private val messages = mutableListOf<Message>()
    private val errorsFound = mutableListOf<Message>()

    override fun clear() {
        messages.clear()
        errorsFound.clear()
    }

    override fun hasErrors(): Boolean = errorsFound.isNotEmpty()

    override fun report(
        severity: CompilerMessageSeverity,
        message: String,
        location: CompilerMessageSourceLocation?,
    ) {
        val entry = Message(severity, message, location)
        messages += entry
        if (severity.isError) errorsFound += entry
    }

    /** First error as a [Diagnostic], positioned in the player's source where possible. */
    fun toDiagnostic(): Diagnostic? {
        val error = errorsFound.firstOrNull() ?: return null
        val generatedLine = error.location?.line
        val playerLine = generatedLine?.let { generated.toPlayerLine(it) }
        return Diagnostic(
            kind = Diagnostic.Kind.COMPILE_ERROR,
            message = clean(error.text),
            line = playerLine,
            snippet = playerLine?.let { generated.playerLines.getOrNull(it - 1) },
            detail = wrapperHint(playerLine, generatedLine),
        )
    }

    /**
     * Every compiler message, formatted, for the log.
     *
     * [toDiagnostic] deliberately throws away everything but the first error, because the
     * player needs one clear sentence. The log needs the rest: when a *reference* solution
     * fails to compile, the only evidence is whatever the compiler said, and a single error
     * out of several is rarely the one that explains the failure.
     */
    fun dump(): String = if (messages.isEmpty()) {
        "<no compiler messages at all>"
    } else {
        messages.joinToString("\n") { m ->
            val at = m.location?.let { " (generated line ${it.line})" } ?: ""
            "${m.severity.name}$at: ${m.text.replace('\n', ' ')}"
        }
    }

    private fun wrapperHint(playerLine: Int?, generatedLine: Int?): String? = when {
        playerLine != null -> null
        generatedLine != null ->
            "Reported in generated line $generatedLine, which is part of the wrapper replikube " +
                "generates around your code. That usually means a bracket is unbalanced, so the " +
                "compiler lost track of which lines are yours."

        else -> null
    }

    /** Compiler messages arrive multi-line and prefixed with position noise. */
    private fun clean(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("warning:") }
            ?.removePrefix("error: ")
            ?: "Compilation failed."
}

/**
 * Compiler arguments.
 *
 * `noStdlib`/`noReflect` are on deliberately: the compile classpath is exactly `:dsl` and
 * the stdlib, which keeps "player code cannot see game internals" true at compile time
 * rather than merely enforced by convention.
 */
internal fun compilerArguments(sourceFile: File, classesDir: File): K2JVMCompilerArguments =
    K2JVMCompilerArguments().apply {
        freeArgs = listOf(sourceFile.absolutePath)
        destination = classesDir.absolutePath
        classpath = playerClasspath()
        noStdlib = true
        noReflect = true
        // Set here as well as in the `CompilerConfiguration`, because these arguments win:
        // `exec` copies them over the configuration wholesale. Leaving the constant at 21 in
        // only one of the two places is exactly how this bug survived — the configuration said
        // one thing, the arguments another, and the arguments were the ones that counted.
        jvmTarget = playerTarget().toString()
        reportOutputFiles = false
        suppressWarnings = true
    }

/**
 * Bytecode version for player code: the *running* JVM's version, capped at the project's floor.
 * Never a hardcoded constant.
 *
 * The compiled class is loaded by `ByteArrayClassLoader` into this very JVM, so the only safe
 * target is "no newer than what is running". Pinning 21 is a latent packaging bug: an app image
 * bundling a JBR 17 emits class file 65, and every level then fails to load with
 * `UnsupportedClassVersionError` — a message naming neither the level nor the compiler, so it
 * reads as a corrupt install rather than a build mismatch.
 *
 * Top-level rather than a member because it is needed from two places that must agree:
 * [compilerArguments] and the compiler configuration. When they disagreed, the arguments won
 * and the configuration was decoration.
 *
 * Capped at [MIN_PLAYER_TARGET] rather than tracking the runtime upward, so a solution
 * compiled on a new JDK still loads in an app image with an older bundled runtime.
 */
internal fun playerTarget(): JvmTarget {
    val chosen = minOf(Runtime.version().feature(), MIN_PLAYER_TARGET)
    // Matched on the human feature number ("17"), not the constant name (`JVM_17` but also
    // `JVM_1_8`, so there is no uniform name to format) and not `majorVersion`, which is the
    // *class file* version — 61 for 17, not 17.
    return JvmTarget.entries.first { it.toString() == chosen.toString() }
}

/**
 * The classpath player code may see: `:dsl` plus the Kotlin stdlib. Nothing else.
 *
 * This is the sandbox. `:core`, `:app` and `:scripting` are absent, so a solution cannot
 * reach game internals even by fully-qualified name.
 */
private fun playerClasspath(): String {
    // Each entry is logged individually, with whether it exists as a file. In a packaged
    // app these are paths inside a read-only FUSE mount; if jpackage has laid the jars out
    // somewhere this resolves to a path that is not there, the compiler fails with
    // "unresolved reference" on the *stdlib* — which reads like a code bug and is not.
    val dsl = codeSourceOf(Replikube::class.java, "dsl")
    val stdlib = codeSourceOf(Unit::class.java, "kotlin-stdlib")
    val classpath = listOf(dsl, stdlib)
    classpath.forEach { Log.path("compile() classpath entry", it) }
    // Joined only after the individual entries are logged: a colon-separated list is not a
    // path, and asking "does this exist" of one produces a confident and meaningless "no".
    val joined = classpath.joinToString(File.pathSeparator)
    Log.i(TAG, "compile() classpath = $joined")
    return joined
}

/**
 * The location a class was loaded from, as a plain filesystem path.
 *
 * `protectionDomain.codeSource` is the only reliable handle on "where did this jar end up"
 * once a classloader has been rearranged by packaging. Null-safe: a class loaded from a
 * memory classloader has no code source, which is logged rather than turned into an NPE
 * three frames later inside the compiler.
 */
private fun codeSourceOf(type: Class<*>, label: String): File? {
    val location = runCatching { type.protectionDomain?.codeSource?.location }.getOrNull()
    if (location == null) {
        Log.e(TAG, "$label has no code source; it was loaded from memory or the boot loader")
        return null
    }
    // A `jar:file:/…!/x.jar` URL has to become a real path before the compiler can read it,
    // and URI decoding matters because an AppImage mount path can contain spaces.
    val path = runCatching { File(location.toURI()) }.getOrElse { e ->
        Log.e(TAG, "$label code source $location is not a file: URL", e)
        return null
    }
    return path.also { Log.i(TAG, "$label resolved to ${it.absolutePath}") }
}

/** Maps binary name (`replikube.generated.Player`) to class bytes read from the output dir. */
private fun readClasses(classesDir: File): Map<String, ByteArray> =
    classesDir.walkTopDown()
        .filter { it.isFile && it.extension == "class" }
        .associate { file ->
            file.relativeTo(classesDir).invariantSeparatorsPath.removeSuffix(".class")
                .replace('/', '.') to file.readBytes()
        }

/**
 * Serves compiled classes from memory, so nothing depends on the output directory surviving.
 *
 * Delegation to the parent is left alone: only classes the compiler produced for this run
 * are served here, and everything else (`:dsl`, the stdlib) resolves through the parent.
 */
private class ByteArrayClassLoader(
    parent: ClassLoader,
    private val classes: Map<String, ByteArray>,
) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        val bytes = classes[name] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }
}
