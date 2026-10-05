# AGENT.md — `:core`

The rules of the game. Grids, diffs, stars, level metadata, logging. Pure Kotlin plus
kotlinx.serialization — no UI, no compiler, no coroutines.

## What lives here

| file | what it is |
|---|---|
| `Coord.kt` | `Coord`, `GridSize`, `CoordinateBounds`, iteration and neighbour helpers |
| `VoxelGrid.kt` | an immutable, fully-populated grid of colour ids, and `GridSize.indexOf` |
| `Level.kt` | `Level` (metadata + target matrix + reference solution) and `LoadedLevel` |
| `TargetMatrix.kt` | matrix ↔ grid conversion, the on-disk target format |
| `Verifier.kt` | `Diff`, `WrongVoxel`, `Verifier` — what "solved" means |
| `Score.kt` | `Stars`, `LevelProgress`, `Progress` |
| `log/Log.kt` | the project's only logger |
| `render/AsciiRenderer.kt` | shapes as text, for authoring and for terminals |

## The two things to understand first

**1. A grid is fully populated. `EMPTY` is a value, not an absence.**

Every coordinate in `GridSize` always has an id, and `EMPTY` (0) is a legal value. That
keeps the renderer, the diff, and the level matrix free of null handling. `get` on an
out-of-bounds coordinate returns `EMPTY` rather than throwing, which is what lets the
verifier walk two grids without a bounds dance.

**2. Voxel order is defined exactly once, in `GridSize.indexOf`.**

Row-major, **x fastest, then y, then z**. `TargetMatrix` reuses this order rather than
defining its own, so the on-disk level format and the in-memory grid cannot be transposed
relative to each other. If you add anything that iterates voxels, use `indexOf` or
`allCoords()` — never a hand-rolled loop.

## `null` → `EMPTY` happens here, once

```kotlin
fun Replikube.asVoxelProgram(): VoxelProgram =
    VoxelProgram { x, y, z -> block(x, y, z) ?: Palette.EMPTY }
```

This is the only place a player's `null` becomes a colour id. Everything downstream deals
in ints. A second fold anywhere else is a bug.

## Level targets are data

`Level.target` is a flat, row-major list of palette ids — `size.voxelCount` of them. See
`TargetMatrix` for the format and the validation.

This is the module's most consequential design decision, so it is worth understanding
rather than just respecting. Targets used to be defined as "whatever the reference
solution compiles to", which meant opening a level required a working Kotlin compiler
inside the packaged app. Every way that could fail — a jlink runtime without
`jdk.compiler`, a stripped `kotlin-compiler-embeddable`, a bytecode target newer than the
bundled JVM — took all twenty levels down at once, with an error naming neither the level
nor the cause.

`Level.init` validates that `target.size == size.voxelCount`, and `targetGrid()` checks
every id is in `0..Palette.MAX`. Both fail loudly. A short matrix would otherwise leave
part of the grid undefined, and an out-of-range id would render as a colour that does not
exist.

`Level.reference` is a Kotlin body stored as text — what the Reveal button shows once a
level is beaten. It is never executed to build the target, and the game compares the
player's grid against `target`, never against `reference`.

## Dependency direction

`:core` depends on `:dsl` (`api(project(":dsl"))`), never the reverse. `:core` owns the
rules and `Palette` is part of the game's vocabulary, so the arrow points that way. This is
what lets `:scripting` compile player code against a classpath containing `:dsl` alone.

## Logging

`Log` is the only logger in the project, and it is deliberately not a framework.

- **Always on.** No configuration, no levels, no init call. If something only breaks when
  packaged, the log has to already be there.
- **File plus a stderr mirror.** Two sinks because the interesting case is a user launching
  a packaged binary, where nobody is reading stdout.
- **Never throws.** A logging failure must not become the failure being diagnosed, so
  every failure path degrades to less logging.
- **Truncates at 512 KB**, so a runaway loop cannot fill a disk.
- **Path order:** `REPLIKUBE_LOG` → `$XDG_STATE_HOME/replikube/replikube.log` (or
  `~/.local/state/...`) → `$java.io.tmpdir/replikube/replikube.log`.

Use `Log.path(label, value)` for anything filesystem- or jar-shaped, and `Log.environment()`
at startup. `Log.path` resolves `URL`s properly — `File` as-is, `file:` via `toURI()`,
`jar:` via `JarURLConnection.jarFileURL` — and reports one of `exists`, `MISSING`, `null`,
or `not a filesystem path (jar URL)`. This exists because handing `file:/x.jar` to `File`
parses it as a *relative* path, which made every real jar in the packaged app report
`MISSING` while sitting on disk. **A log that lies is worse than no log.**

`Log.paths(label, mapOf(...))` logs a group at once — `Main.kt` uses it for the module jars
at startup, because a class loaded from the wrong place is a failure three layers down.
Nothing in this module is `open`; the one test seam in the project lives in `:levels`.

## Tests

`./gradlew :core:test` — 21 tests, fast, no Gradle gymnastics. Grid semantics, bounds,
the ASCII renderer, `indexOf` round-trips.

`:levels` and `:app` both build on these types heavily; a change here shows up as failures
there first, which is the point of this module having no dependencies to soften the blow.
