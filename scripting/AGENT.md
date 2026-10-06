# AGENT.md — `:scripting`

The sandbox boundary. Compiles a player's function body, runs it over a grid, and reports
what happened as a `RunResult`.

## The interface is the point

```kotlin
interface ScriptRunner {
    suspend fun run(source: String, size: GridSize, timeoutMillis: Long = 10_000): RunResult
}
```

Everything above this interface deals in `RunResult` and never sees a compiler, a
classloader, or a `kotlin.jvm.internal` type. `:core`, `:levels` and `:app` depend on this
and nothing deeper — which is what lets `InProcessScriptRunner` be replaced by a
forked-JVM implementation without touching game code.

Evaluation happens *inside* `run` rather than returning a lazy program, so runtime failures
surface as a `Diagnostic` on the same path as compile failures, under the same timeout. A
caller handed a raw program would have to reimplement exception-to-diagnostic mapping and
the time budget.

`Diagnostic.Kind` covers everything a player can hit: `COMPILE_ERROR`, `CONTRACT_ERROR`,
`RUNTIME_ERROR`, `TIMEOUT`, `INTERNAL_ERROR`. Each carries a `line` in the **player's**
source where that is meaningful, plus the `snippet` for context.

## Pipeline

Two pipelines, one interface. The JVM is the local one:

```
player body
  → SolutionCompiler.generate()   wrap in a class, bake in Level constants, find the offset
  → compilerArguments()           K2JVMCompilerArguments, jvmTarget, classpath = :dsl only
  → K2JVMCompiler.exec()          to a temp directory
  → ByteArrayClassLoader          load the .class bytes
  → evaluate()                    call block() for every voxel
  → RunResult
```

The browser cannot run a compiler, so there is nothing to port — only somewhere else to run
one. `RemoteScriptRunner` posts the same generated wrapper to a compiler service:

```
player body
  → SolutionCompiler.generate()   the *same* wrapper, so the same line mapping
  → RemoteProgram.build()         append a main() that prints the grid; add :dsl as source
  → POST api.kotlinlang.org/.../compiler/run
  → toRunResult()                 severity, line base, stream tag, then the printed grid
  → RunResult
```

**What the two share is the part the player sees.** Same `Diagnostic.Kind` for the same
cause, and a compile error on the same line of their own source, because both call
`SolutionCompiler.generate` and its `playerLineOffset` is the same number. Everything else
differs: the remote sandbox is weaker, the timeout belongs to the service, and the build
needs a network.

### Why `RemoteProtocol` is in `commonMain`

None of it needs a browser. Decoding a response, assembling the harness, and turning one
into a `RunResult` are all string-in/string-out, so they are tested by
`RemoteProtocolTest` on the JVM against recorded response bodies, in milliseconds, with no
dependency on a third-party service being up. Only the HTTP call is `wasmJsMain`.

That split is the difference between a protocol that is tested and a protocol that was
exercised by hand once. The recorded bodies in that test are the ones the service actually
returned, and the details they pin down are the ones a reasonable guess gets wrong:

| detail | why it matters |
|---|---|
| `errors` is keyed by **file name** | the only thing separating "your code is wrong" from "our palette copy is wrong" |
| `severity` is present, warnings included | an unused variable in the starter text is a warning; treating any entry as fatal rejects working code |
| `interval.start.line` is **0-based** | `toPlayerLine` is 1-based, so the `+ 1` is the whole content of one test |
| a timeout is **HTTP 200** on `<errStream>` | no status code and no exception to detect |
| `text` wraps stdout in `<outStream>` | player `println` must not be mistaken for the answer |

## The sandbox, concretely

`playerClasspath()` returns **`:dsl` plus the Kotlin stdlib**. Nothing else. `:core`,
`:app`, and `:scripting` are absent, so a solution cannot reach game internals even by
fully-qualified name — that is enforced at compile time, not by convention. `noStdlib` and
`noReflect` are deliberately on so the classpath is exactly what `playerClasspath()` says.

**Remotely that enforcement is gone, and `:dsl`'s empty dependency list is the only thing
standing in.** The service has no classpath of ours to extend, so `:dsl` travels as source
text — embedded by the `generateDslSources` task, which is why the `.kt` files in `:dsl`
stay the single source of truth and `dsl/build.gradle.kts` says the zero-dependency rule now
has a second reason to hold.

## The bytecode target bug, and why the code looks defensive

`playerTarget()` is derived from the **running** JVM and capped at `MIN_PLAYER_TARGET`
(17, the project toolchain floor). It is not a constant, and it is set in **two** places
that must agree:

```kotlin
put(JVMConfigurationKeys.JVM_TARGET, playerTarget())   // in newEnvironment()
jvmTarget = playerTarget().toString()                  // in compilerArguments()
```

This was hardcoded `JVM_21`, and that broke two ways:

1. The packaged app bundled a 21 runtime so it looked healthy, while the test JVM was 17
   and 17 tests failed with `UnsupportedClassVersionError: class file version 65.0, this
   version ... only recognizes up to 61.0`. That message names neither the level nor the
   compiler, so it reads as a corrupt install.
2. Setting the value in only one place **silently did nothing** — `K2JVMCompiler.exec`
   copies `compilerArguments` over the `CompilerConfiguration` wholesale, so the arguments
   win. The configuration said one thing, the arguments another, and 21 was compiled.

If you add a compiler flag, put it in `compilerArguments` only. The `CompilerConfiguration`
is close to decorative, and duplicating a value across the two is how this bug survived.

`MIN_PLAYER_TARGET` is a top-level `internal const`, not inside a `companion object` — a
`private const` in a companion is invisible to top-level functions in the same file, and
two separate readers need this one.

## Line numbers

`SolutionCompiler` wraps the player's body in a generated class and locates it with the
sentinel `// <<< replikube:player-code >>>`. The index of that line is the offset between
generated line numbers and the player's, and every diagnostic is translated through
`toPlayerLine`.

A player's first line must be reported as line 1, not line 29. That is asserted. If you
change the generated header, that offset is what keeps working — do not remove the marker
and hope the diagnostics degrade gracefully.

## Timeouts and temp directories

`executeWithBudget` runs the build on a separate thread under a wall-clock budget, because
`K2JVMCompiler.exec` and a player's `while (true)` are both uninterruptible. The thread
is left to die as a daemon if it overruns; the budget is what the player experiences.

The work directory is `java.io.tmpdir` by default, overridable for tests. It is logged,
along with each classpath entry via `Log.path` — a joined classpath string is not a path,
so it goes through `Log.i` instead.

## Logging

Instrumented throughout with `Log.i` / `Log.e`, tagged `ScriptRunner`. This module is
where the packaged-app failures actually surface, so the logs here are the diagnostic tool
for "works under Gradle, fails when packaged": class file versions, classpath entries,
work directory existence, and the full generated source on a compile throw.

That last one is noisy — it logs every generated program. If that proves too much in
practice, gate it; do not delete it, because the generated source is the only way to
diagnose a wrapper that is subtly wrong.

## Tests

`./gradlew :scripting:test` — 25 tests, and they really compile Kotlin, so this module is
the slowest of the pure-JVM ones. The suite is the safety net for the whole contract:
`null` means empty, out-of-range colours are rejected by coordinate, a missing `else` is a
compile error, an infinite loop times out rather than hanging, player code cannot see game
internals, and the error line number is the player's own.

`SolutionCompilerTest` covers the wrapper generation and line mapping without invoking a
compiler, so it stays fast.
