# AGENT.md — `:levels`

The campaign: 20 handcrafted levels, as data. This module reads them, validates them, and
hands out target grids. It has no compiler dependency, and that is the whole design.

## One file per level

```
levels/src/commonMain/resources/levels/
  hello-layers.json
  checkerboard.json
  ...
```

Each file is the whole level: metadata, the target matrix, and the reference solution as a
string. There is no second resource, so there is no filename to reference and nothing to go
missing from a classpath.

### Why those files are *also* compiled into Kotlin source

The JSON above is the source of truth. At build time `GenerateLevelData` turns all of it
into one generated Kotlin file, `EMBEDDED_LEVEL_JSON`, which becomes a string constant.

That is duplication, and it is deliberate: **reading a resource at runtime is not portable.**
`Class.getResource` does not exist on wasmJs, so a resource-based catalogue forces either a
per-target loader or a `expect fun` whose two implementations differ in kind — a filesystem
read on the JVM, a `fetch` plus a coroutine in a browser. The latter would make `metadata`
and `resourceIds()` **suspend** and asynchronous, which is a much worse catalogue than the
one it saves, and it would push an await into the middle of the game's startup for 23 KB of
text that was known at compile time.

With the data embedded, `metadata`, `load` and `resourceIds()` are plain synchronous calls
that behave *identically* on both targets, and the `file:`/`jar:` URL branch that existed
only to survive packaging is simply gone.

The cost is a drift guard, and the guards are split by what they need:

- **`commonTest`** — `LevelCatalogTest`, over the embedded data. Runs on both targets, so it
  proves the catalogue behaves the same in a browser as on the desktop.
- **`jvmTest`** — `ReferenceSolutionsTest`, `EmbeddedLevelsTest`, `GenerateTargetsTest`. These
  need a compiler, or a filesystem to compare against, and there is no equivalent of either
  in a browser, so they are not stubbed for wasm.

`EmbeddedLevelsTest` is the one that matters: it asserts the embedded string is byte-equal to
what is on disk. Generated string literals use `json.trimEnd()`, **not** `trimIndent()` —
`trimIndent` strips the files' two-space indent and quietly changes the content, so the guard
would fail on a file that is actually correct.

The generator is a task **class** (`GenerateLevelData`), not a `doLast` in the build script:
a `doLast` captures the script object, which breaks the configuration cache.

```json
{
  "id": "hello-layers",
  "title": "Three Layers",
  "difficulty": 1,
  "par": 5,
  "hint": "y is -1 at the bottom, 1 at the top. Try a when on y.",
  "size": { "x": 3, "y": 3, "z": 3 },
  "target": [8,8,8,6,6,6,10,10,10, 8,8,8,6,6,6,10,10,10, 8,8,8,6,6,6,10,10,10],
  "reference": "return when (y) {\n    1 -> RED\n    0 -> YELLOW\n    else -> GREEN\n}"
}
```

`target` holds `size.voxelCount` palette ids, `0` meaning empty, with **x varying fastest,
then y, then z** — the same order as `GridSize.indexOf`. `reference` is a Kotlin body, shown
by Reveal once the level is beaten; it is never executed to build the target.

## Why the target is a matrix and not a compiled program

This is the module's most consequential decision, so it is worth understanding rather than
just respecting.

Targets used to be defined as "whatever the reference solution compiles to". That had one
large advantage — a level could not be unsolvable, because its target *was* its own
solution's output. It also meant that **opening a level required a working Kotlin compiler
inside the packaged app**. Every way that can fail took the whole campaign down at once,
and none of them named a level or a cause:

- a jlink runtime without `jdk.compiler`
- `kotlin-compiler-embeddable` stripped by a packaging step
- a bytecode target newer than the bundled JVM
- no writable temp directory

So the target is stored, and the guarantee is now a test instead of a property:
`LevelCatalogTest` compiles all twenty reference solutions and asserts each reproduces its
stored matrix **exactly**. That is a weaker guarantee in principle and a stronger one in
practice — it fails on the machine that authored the level, not in front of a player.

`:scripting` is a **test** dependency here and nothing else. `implementation` on it would
mean the compiler is back on the level-loading path, which is the bug this change removed.

## Authoring and regenerating

Write the `reference` first, then let the tooling fill in `target`:

```bash
REPLIKUBE_REGENERATE=true ./gradlew :levels:test --tests '*GenerateTargetsTest*'
```

That rewrites the `target` field in place and prints each level's solid count.

**It is an environment variable, not `-D`.** Gradle does not forward `-D` flags into the
forked test JVM, so `System.getProperty` would read `null` and the generator would appear
to run while doing nothing — a silent no-op is worse than a loud failure.

After editing a reference solution, re-run it. `LevelCatalogTest` will tell you if you
forgot, and names the coordinates that differ.

## `LevelCatalog`

```kotlin
val levels: List<Level>          // metadata, curriculum order, parsed lazily
val ids: List<String>            // levels.map { it.id }
fun metadata(id: String): Level?
suspend fun load(id: String): Result<LoadedLevel>   // matrix → grid, cached
fun invalidate(id: String)                          // drops the cache; tests
```

`levels`, `load`, and `metadata` are `open` **purely so `:app` can substitute a catalogue
that fails**. The only failure worth simulating is a level file whose matrix does not match
its declared size, and the only honest way to test that is to serve a `load` that fails —
a real level file on disk cannot be made malformed for one test, and dropping a broken file
on the classpath to break it would be worse. Treat those three as the seam and nothing
more.

`load` is a parse of data already in memory: no compiler, no temp directory, no code
generation. That is the point.

## Discovery, and the ordering trap

`resourceIds()` derives the id list from the embedded data rather than reading a
hand-written index, so a new level cannot be silently forgotten.

Two traps here:

- **`resourceIds()` is discovery order, not curriculum order.** It is sorted alphabetically,
  so it gives you `arch, carpet, cathedral, ...`. Curriculum order comes from
  `sortedBy { difficulty }` in `ids`. Asking for "the first level" via
  `resourceIds().first()` silently returns `arch` (7×7×7) while the screen you are driving
  opens `hello-layers` (3×3×3) — and it fails on an assertion with nothing pointing at the
  cause. Use `LevelCatalog().ids`.

The two traps this section used to have — `file:` versus `jar:` URLs, and the one-shot
`Enumeration` from `getResources()` — are gone with the resource loading they described.
They were the cost of reading a directory that no longer exists at runtime, and that cost
was paid at startup on every launch.

## Tests

`./gradlew :levels:allTests` — both targets. The `jvmTest` half takes ~30 s, because one of
its tests compiles twenty real solutions. That is the price of the guarantee, paid once per
build rather than once per launch.

The split is by need, not convenience:

- **`commonTest`** — `LevelCatalogTest`, over the embedded data. Runs in a real browser as
  well as a JVM, which is the point: it is what makes "the catalogue behaves identically on
  both targets" a tested claim rather than an intention.
- **`jvmTest`** — `ReferenceSolutionsTest` needs the Kotlin compiler;
  `GenerateTargetsTest` needs to write files; `EmbeddedLevelsTest` needs to read the real
  JSON off disk to compare against the embedded copy. None of that exists in a browser and
  none of it is worth stubbing, because a stubbed reference-solution test asserts nothing.

The tests worth knowing about:

- **`every reference solution still produces exactly its stored target`** — the load-bearing
  one. Compiles all twenty and diffs each against its matrix, naming the coordinates that
  differ so you can tell an intentional shape change from a transposed matrix.
- **`every level's target matrix matches its declared size`** — asserts all 20 survived
  deserialisation. A level that fails to parse is silently dropped from the catalogue, so
  without this a broken matrix shows up as a campaign quietly one level shorter.
- **`showEveryLevel`** — prints every target as ASCII. The fastest way to see the campaign
  without launching the game:
  `./gradlew :levels:jvmTest --tests '*showEveryLevel*' --rerun-tasks -i`

`GenerateTargetsTest` skips itself unless the env var is set, so `./gradlew build` never
rewrites tracked source files as a side effect of running tests.

The `:levels` → `:scripting` dependency is `jvmTestImplementation` only, and must never
become `implementation`: that would put the Kotlin compiler back on the level-loading path,
which is the bug the stored targets exist to remove.
