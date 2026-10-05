# AGENT.md — replikube

Working notes for anyone (human or agent) changing this repository. Per-module detail
lives in each module's own `AGENT.md`; this file is the map.

## What the project is

A puzzle game. Each level shows a 3D voxel shape; the player writes Kotlin that decides
the colour of every voxel, presses Run, and the code is compiled and executed at runtime.
The game grades the result voxel by voxel and reports what is missing, wrong-coloured, or
extra — not pass/fail.

The player's job is to write **the body of a function**. Not the signature:

```kotlin
when (y) {
    1 -> RED
    0 -> YELLOW
    else -> null       // null = no cube
}
```

## Build and run

```bash
export JAVA_HOME=/home/bob/.jdks/jbrsdk_jcef-21.0.7   # or any JDK 21
./gradlew :app:run

./gradlew test                    # all modules
./gradlew build                   # compile + test
./gradlew :app:packageAppImage
app/build/compose/binaries/main/app/replikube/bin/replikube
```

`packageAppImage` runs `jpackage --type app-image` and produces a **directory**, not a
squashfs `.AppImage` — a real `.AppImage` needs `appimagetool` or `linuxdeploy`, which are
not on every machine. The directory is complete and self-contained: its own JBR, its own
jars.

Set `REPLIKUBE_LOG=/tmp/replikube.log` to capture the log. Without it, logging goes to
`$XDG_STATE_HOME/replikube/replikube.log` (or `~/.local/state/...`), then the temp
directory. Always on, mirrored to stderr, truncated at 512 KB, never throws.

## Module map

Five modules. The dependency direction is the architecture, not an accident.

```
                        ┌───────────┐
                        │   :app    │  Compose Desktop — UI only
                        └─────┬─────┘
              ┌───────────────┼───────────────┐
              ▼               ▼               ▼
        ┌──────────┐   ┌────────────┐   ┌──────────┐
        │ :levels  │   │ :scripting │   │  :core   │
        │ catalog  │   │  runner    │   │  rules   │
        │  + JSON  │   └──────┬─────┘   └────┬─────┘
        └──────────┘          └──────────────┘
                                   ▼
                             ┌─────────┐
                             │  :dsl   │  player-facing API. Zero deps.
                             └─────────┘
```

One edge is deliberately invisible above: `:levels` depends on `:scripting` in
`testImplementation` only. That edge is the drift guard (it compiles all 20 reference
solutions and checks them against their stored matrices), and it must never become
`implementation` — that would put the Kotlin compiler back on the level-loading path, which
is the bug the stored targets exist to remove.

| module | AGENT.md | one line |
|---|---|---|
| `:dsl` | [dsl/AGENT.md](dsl/AGENT.md) | the only vocabulary a player can see. Zero dependencies. |
| `:core` | [core/AGENT.md](core/AGENT.md) | the rules: grids, diffs, stars, level metadata. Pure. |
| `:scripting` | [scripting/AGENT.md](scripting/AGENT.md) | the sandbox boundary. Compiles and runs a body. |
| `:levels` | [levels/AGENT.md](levels/AGENT.md) | the 20-level campaign, as data. |
| `:app` | [app/AGENT.md](app/AGENT.md) | Compose UI, the state machine, local progress. |

`:dsl` is a leaf that nothing depends on but player code, and that depends on nothing.
That is what makes "the player can only see this" true by construction rather than by a
check. `:core` owns the rules, so `:core` → `:dsl` and never the reverse.

## The one rule that shapes the module graph

**Player code is compiled against `:dsl` and the Kotlin stdlib. Nothing else.**

`:core`, `:scripting`, `:app` are absent from that classpath, so a solution cannot reach
game internals even by fully-qualified name. If you add a dependency to `:dsl`, you have
added it to the player's world. `dsl/build.gradle.kts` says this at the dependency block;
`:scripting`'s `playerClasspath()` is where it is enforced.

## Shared conventions

**Logging is `:core`'s `Log`.** Always on, no framework, no configuration. Use
`Log.i` / `Log.w` / `Log.e`, and `Log.path` for anything filesystem- or jar-shaped. A log
that lies is worse than no log, which is why `Log.path` resolves `URL`s properly rather
than handing `file:/x.jar` to `File` (that parses as a *relative* path and reports a real
jar as missing).

**Comments explain why, not what.** The codebase is comment-dense on purpose: most of the
volume is recorded reasoning about why an approach was chosen, what was rejected, and what
would break otherwise. Match that. If you find yourself writing a comment that restates
the line below it, delete it.

**Tests assert on real data.** The recurring lesson in this project is that a fixture
resembling the real thing is more dangerous than no fixture. `GameModelTest` once passed
against a hand-built 3×3×3 stand-in while the level it opened was 27 solid voxels in three
colours — the stand-in was *almost* right, which is what made it convincing. Read real
level matrices and real grids into tests.

**`./gradlew test` is the gate.** 180 tests. A change that cannot be tested here is
probably in the wrong module.

**`pkill -f 'binaries/main/app/replikube'` kills your own shell**, because the pattern
matches the shell's command line. Use `pgrep` with an explicit pid filter that excludes
`$$`.

## Things that will bite you

- **`LevelCatalog.resourceIds()` is discovery order (alphabetical), not curriculum
  order.** Curriculum order comes from `sortedBy { difficulty }` in `levels`. Asking
  `resourceIds().first()` for "the first level" silently gives you `arch` (7×7×7) while
  the screen you are driving opens `hello-layers` (3×3×3). Use `LevelCatalog().ids`.
- **`getResources()` returns a one-shot `Enumeration`.** Count it and then iterate it and
  you get nothing. `Collections.list(...)` immediately.
- **`JvmTarget.majorVersion` is the class file version** (61 for Java 17), not the feature
  number (17). Match on `toString()` if you need the feature number.
- **`compilerArguments` silently beats `CompilerConfiguration`.** `K2JVMCompiler.exec`
  copies arguments over the configuration wholesale. A constant set in both places, with
  the two disagreeing, compiles the argument's value.
- **A `/*` inside a KDoc opens a nested block comment** in Kotlin, which nests because
  `*/` in a glob like `levels/*.json` does not close what it opened. Compile error:
  "Unclosed comment", pointing at the end of the file.
- **`private const` inside a `companion object` is invisible to top-level functions in
  the same file.** Declare it at file level next to its reader.
- **Hand-computing character offsets in tests.** Use `indexOf` / `lastIndexOf`. This has
  gone wrong three times.
- **Do not screenshot the running window.** The GNOME portal denies it. Verify rendering
  with the headless `ImageComposeScene` tests instead; they write real PNGs and print ASCII
  silhouettes.

## Documentation

- [docs/DESIGN.md](docs/DESIGN.md) — the design and its history: vision, contract,
  execution model, rendering, editor, scoring, curriculum, milestones, and a numbered
  record of every bug the tests found. This is the real documentation.
- [README.md](README.md) — **currently an empty file.** Rendered screenshots live in
  `app/build/renders/` instead, produced by the headless render tests.
