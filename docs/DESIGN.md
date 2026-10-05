# replikube — design & implementation plan

A puzzle game where you reproduce a 3D voxel shape **by writing Kotlin code**.
You are shown a target cube, you write the **body** of `fun block(x, y, z): Int?`
returning `null` for empty, and the game runs your code, renders the cube you produced
under the target from the same camera, and tells you exactly which voxels are wrong.

Status: playable. `:core`, `:scripting`, `:levels` and `:app` are green. No commits exist in
this repo yet.

---

## 1. Vision

**One sentence.** Learn Kotlin by making voxels appear.

**Core loop**

1. A level shows a target voxel shape (and, optionally, an idle animation).
2. The player writes code in a Kotlin editor pane.
3. They press **Run**. The code is compiled and executed against the level's grid.
4. The result is rendered in 3D under the target, on one shared camera, with a per-voxel
   diff and a score.
5. Iterate. On a perfect match: celebration, stars, next level unlocked.

The feedback loop *is* the game — `replikube` is a REPL where the output is a cube.

**Why this is a good game, not just a toy**

- Failures are informative, not punishing: the diff says *which* voxel is wrong.
- The difficulty curve is the Kotlin learning curve, disguised.
- Levels are tiny, self-contained programs — great to share, great to replay.

**Target audience.** Kotlin-curious developers, CS students, and anyone who likes
Wordle-sized puzzle loops. Assumption: comfortable with a code editor, new to Kotlin is fine.

---

## 2. Scope

### v1 (what we build)

- Compose Desktop app, offline, single-player, local only.
- One contract: `fun block(x: Int, y: Int, z: Int): Int`, used for **all** levels.
- ~20 handcrafted levels forming a curriculum.
- Isometric 3D viewport (no game engine) + plain multiline code editor.
- Per-voxel diff, star rating, hints, progress saved to a local file.

### Non-goals for v1 (explicitly out)

| Not now | Why |
|---|---|
| Sandboxed/out-of-process execution | In-process is enough for an offline single-player game; the `ScriptRunner` boundary keeps the door open (see §5). |
| Web / multiplayer front end | Would force `ScriptRunner` onto JS; JVM-only keeps iteration fast. |
| User-authored levels + sharing | Requires an authoring UI and trust model. Levels are reference scripts on disk instead. |
| Syntax highlighting / autocomplete | Plain text editor for v1. Revisit only if players complain. |
| Level editor | Levels are `.kt` files plus a `.json` header in the repo. Fine at 20 levels; revisit at 100. |

### v2+

Process isolation, daily puzzle, solution sharing codes, a web (KMP) front end,
leaderboards, level editor, hint system with partial solutions.

---

## 3. The contract

### Player code

A player solution is a Kotlin script containing exactly one entry point:

```kotlin
fun block(x: Int, y: Int, z: Int): Int = when {
    y == 1 -> RED
    y == 0 && abs(x) + abs(z) <= 1 -> YELLOW
    else -> GREEN
}
```

Rules:

- **Required:** `fun block(x: Int, y: Int, z: Int): Int`, exactly this signature.
- **Return value:** an `Int` in `0..16`. `0` = empty. `1..16` = a palette colour.
  Out of range is a runtime error naming the offending coordinate.
- **Allowed:** top-level `val` / `var`, additional top-level `fun`s, recursion,
  `when` / `if` / loops inside helpers, imports from `:dsl`.
- **Forbidden:** the `:core` and `:app` packages are not on the script classpath at all.
  (Enforced by classpath construction, not by a check. See §5.)

### Coordinates

The grid is centred on the origin. For a level of size `(sx, sy, sz)`:

- `x` ∈ `[-sx/2, (sx-1)/2]`, likewise for `z`
- `y` ∈ `[-sy/2, (sy-1)/2]`  (so size 3 → `-1..1`, size 4 → `-2..1`)

Negative `y` is the bottom layer, matching intuition and `Grid.N`.

### Palette (fixed, global)

Stable indices are required — levels and shared solutions depend on them.

| id | name | id | name | id | name | id | name |
|----|------|----|------|----|------|----|------|
| 0 | `EMPTY` | 5 | `BLACK` | 9 | `LIME` | 13 | `INDIGO` |
| 1 | `WHITE` | 6 | `RED` | 10 | `GREEN` | 14 | `PURPLE` |
| 2 | `LIGHT_GRAY` | 7 | `ORANGE` | 11 | `CYAN` | 15 | `MAGENTA` |
| 3 | `GRAY` | 8 | `YELLOW` | 12 | `BLUE` | 16 | `BROWN` |
| 4 | `DARK_GRAY` | | | | | | |

Exposed in `:dsl` as top-level `Int` constants, so `RED` == `6`.

### The `:dsl` API surface

Deliberately tiny — this is the entire vocabulary a player must learn:

```kotlin
package replikube.dsl

// palette (0..16)
val EMPTY = 0; val WHITE = 1; /* ... */ val BROWN = 16

// level dimensions, injected per level by the generated wrapper
val SIZE_X: Int; val SIZE_Y: Int; val SIZE_Z: Int

// geometry helpers (thin, obvious wrappers over stdlib)
fun abs(n: Int): Int                     // = kotlin.math.abs
fun dist(x: Int, y: Int, z: Int): Int     // euclidean, squared (no float)
fun manhattan(x: Int, y: Int, z: Int): Int
fun chebyshev(x: Int, y: Int, z: Int): Int  // max(abs(x), abs(y), abs(z))
fun inSphere(x: Int, y: Int, z: Int, r: Int): Boolean
fun parity(n: Int): Int                  // n and -n map to the same parity
```

Rule of thumb: if a helper makes a level's *idea* less clear, it does not belong here.

### Difficulty within a strict contract

Loops are still reachable, because helpers and top-level state are allowed:

```kotlin
private val r = 3
private fun shell(d: Int) = d >= r - 1 && d < r

fun block(x: Int, y: Int, z: Int): Int =
    if (shell(chebyshev(x, y, z))) BLUE else EMPTY
```

So the curve is: literals → conditionals → parity → geometry → lookup `val`s →
helper functions → recursion → stateful code. One contract for the whole game;
the difficulty lives in the problem, never in the API. **This is a fixed decision
— no second entry point, not even for late levels.**

---

## 4. Levels are stored matrices

A level's target is **data**: a flat, row-major list of palette ids, living in the
level's own JSON next to the metadata. Loading a level is a parse.

Each level file also carries its **reference solution** — a Kotlin body, stored as a
string — which is what the Reveal button shows once a level is beaten. That solution
is never executed to produce the target.

### Why not "the target is the compiled reference solution"

That was the original design, and it had one large advantage: a level could not be
unsolvable, because its target *was* whatever its own solution produced. It also meant
opening a level required a working Kotlin compiler inside the packaged app. Every
failure mode of that arrangement took the whole campaign down at once, and none of
them named a level or a cause:

| Failure | Symptom |
|---|---|
| jlink runtime without `jdk.compiler` | every level fails to open |
| `kotlin-compiler-embeddable` stripped by a packaging step | every level fails to open |
| Bytecode target newer than the bundled JVM | `UnsupportedClassVersionError: class file version 65.0, this version … only recognizes up to 61.0` |
| No writable temp dir | compile cannot even start |

The trade is a second representation that *could* disagree. That is now enforced by a
test instead of by construction: `:levels` compiles all twenty reference solutions and
asserts each produces its stored matrix exactly. The guarantee survives; the runtime
compiler dependency does not.

### Layout

```
levels/src/main/resources/levels/
  hello-layers.json       # metadata, target matrix, reference solution
  checkerboard.json
  diamond.json
```

One file per level. There is no filename to reference and no second resource to go
missing from a classpath.

### Metadata schema

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

`par` = expected line count, used for star thresholds, not a hard limit.

`target` holds `size.voxelCount` ids: `0` is `EMPTY`. **x varies fastest, then y,
then z** — exactly `GridSize.indexOf`, so there is one definition of voxel order in
the project rather than two, and the matrix cannot be silently transposed. Flat
rather than nested `[z][y][x]`: nesting would cost two extra bracket pairs per slab
and row for nothing `indexOf` does not already guarantee.

Cost of the switch: the campaign's data grew from 8.2 KB to 23 KB, in exchange for
level loading becoming a parse that takes ~6 ms and cannot fail environmentally.

### Validation (build-time, via `:levels` tests)

Every level in `resources/` must satisfy: `id` matches filename and is kebab-case,
`difficulty` values are `1..N` with no gaps, the grid is small enough to read on
screen, the matrix length matches the declared size, and the matrix is non-empty and
uses only palette ids. Separately, **every reference solution must compile and
produce its stored matrix exactly.**

That last test is the one that matters. It replaces "a shape cannot drift from its
level" — previously true by construction — with "a shape cannot drift undetected",
enforced at `./gradlew test` on the machine that authored it.

After editing a reference solution, regenerate the matrices:

```
REPLIKUBE_REGENERATE=true ./gradlew :levels:test --tests '*GenerateTargetsTest*'
```

That rewrites the `target` field in place. It is an environment variable rather than
a `-D` flag because Gradle does not forward `-D` into the forked test JVM, so
`System.getProperty` would read null and the generator would appear to run while
doing nothing.

The level list is discovered by enumerating the resource directory (handling both
`file:` and `jar:` classpath URLs) rather than from a hand-written index, so a new
level file cannot be silently forgotten. Note that enumeration is *discovery* order
(alphabetical), not curriculum order — for "the first level" use `LevelCatalog.ids`.

---

## 5. Execution model

### Pipeline

```
source text
   │
   ├─ wrap     SolutionCompiler emits a .kt file: header + object Player { …player… }
   │           + class Solution : Replikube delegating to Player.block
   │           player line numbers are recorded so diagnostics map back to the editor
   │
   ├─ compile  K2JVMCompiler from kotlin-compiler-embeddable, driven directly
   │           classpath = [:dsl + stdlib] only
   │           → Diagnostic (kind, message, player line, snippet) on failure
   │
   ├─ load     Solution is instantiated from an in-memory class map, so the
   │           program outlives the temporary output directory
   │
   ├─ evaluate one call per voxel over the whole grid, inside the compile+run budget
   │           → VoxelGrid back
   │
   ├─ validate every cell in 0..16, else a diagnostic naming the coordinate
   │
   └─ diff     vs target grid → Diff { missing, wrongColour, extra, exact }
```

### Why not `.kts`

The original plan was Kotlin script files plus `BasicKotlinScriptEngine`. On Kotlin
2.4.20 that is not available: the class is gone, and so is the
`kotlinx.scripting.experimental` facade. The only thing left is the low-level
`ScriptEvaluator`, which has no public constructor.

`:scripting` therefore drives `K2JVMCompiler` itself, which turns out to be simpler
than the workaround it replaced: there is no "generated footer" to smuggle code past
the script engine's top-level-only model, because the wrapper is an ordinary Kotlin
file the compiler is asked to compile normally.

### Why the player's code goes in an `object Player`

```kotlin
object Level { const val sizeX = 3; /* … */ }

object Player {
    // player's source, verbatim
    fun block(x: Int, y: Int, z: Int): Int = …
}

class Solution : Replikube {
    override fun block(x: Int, y: Int, z: Int): Int = Player.block(x, y, z)
}
```

A member `block` inside `class Solution : Replikube` would shadow the interface's own
`block` and fail to compile. Nesting the player's code one level down keeps the
player-facing contract exactly as specified — a *top-level* `block`, with top-level
helpers and `val`s beside it — while giving the runtime something implementable.

### The `ScriptRunner` boundary

Chosen now, isolated later. `ScriptRunner` is the *only* thing that knows how player
code runs:

```kotlin
interface ScriptRunner {
    suspend fun run(source: String, size: GridSize, timeoutMillis: Long): RunResult
}

sealed interface RunResult {
    data class Success(val grid: VoxelGrid) : RunResult
    data class Failure(val diagnostic: Diagnostic) : RunResult   // compile, contract, runtime, timeout
}

class InProcessScriptRunner : ScriptRunner   // v1
class ForkedProcessScriptRunner : ScriptRunner  // v2 — hard-kills while(true)
```

`run` evaluates the grid rather than handing back a lazy program. That keeps three
things in one place: the timeout budget covers compilation *and* execution, a
runtime exception becomes a `Diagnostic` on the same path as a compile error, and no
caller has to reimplement exception-to-diagnostic mapping.

`:core`, `:levels` and `:app` depend only on the interface. Swapping in process
isolation later touches one file plus the build. **This is the whole point of the
boundary — do not leak `K2JVMCompiler` past `ScriptRunner`.**

### v1 safety posture

Honest about what in-process means, for an offline single-player game:

- Script classpath contains `:dsl` plus the Kotlin stdlib — no game internals
  reachable, verified by a test that tries to name one.
- `kotlin-reflect` is deliberately not on the script classpath.
- Player code can: loop forever, allocate until OOM, block a thread. A watchdog
  abandons the worker thread at the timeout, but cannot stop it. Accepted for v1;
  it is a puzzle game, the stakes are one crashed session.
- Mitigations in v1: watchdog timeout (10 s budget for compile *and* run), results
  always surfaced to the UI as a `Diagnostic`, and never running player code on the
  UI thread.
- The v2 `ForkedProcessScriptRunner` fixes all of it properly.

### Compile caching

`K2JVMCompiler` takes roughly a second to compile a trivial solution. Level
reference solutions are compiled on first open and cached for the session
(`LevelCatalog`), so a level costs one compile, not one per visit. Caching player
code by `sha256(source + size)` is left until the one-second wait is felt in play.

---

## 6. Architecture

```
                    ┌───────────┐
                    │  :app     │  Compose Desktop — UI only
                    └─────┬─────┘
              ┌───────────┼────────────┐
              ▼           ▼            ▼
        ┌──────────┐ ┌───────────┐ ┌────────┐
        │ :core    │ │:scripting │ │:levels │
        │ model    │ │ ScriptRunner│ catalog │
        │ rules    │ │ impl       │ │ + JSON │
        │ verify   │ └─────┬─────┘ └───┬────┘
        └────┬─────┘       │           │
             └─────────────┴───────────┘
                       ▼
                  ┌─────────┐
                  │  :dsl   │  player-facing API. Zero deps.
                  └─────────┘
```

`:dsl` is a leaf that nothing depends on but player code, and that depends on
nothing. That is what makes "the player can only see this" true by construction.

### Module responsibilities

| module | responsibility | notes |
|---|---|---|
| `:core` | `Coord`, `GridSize`, `VoxelGrid`, `Level`, `Verifier`, `Diff`, `Score`, `AsciiRenderer` | pure Kotlin + kotlinx.serialization; 100% unit-tested |
| `:dsl` | player API surface | **zero dependencies**, incl. no stdlib-only surprises |
| `:scripting` | `ScriptRunner`, in-process impl, `Diagnostic` | the sandbox boundary |
| `:levels` | level resources, `LevelCatalog`, build-time validation | depends on `:core` only; `:scripting` is a **test** dependency, used to prove each stored matrix still matches its reference solution |
| `:app` | Compose UI, view models, progress persistence | the only module with UI deps |

### Source layout

```
:core/src/main/kotlin/fr/godox/replikube/core/
  Coord.kt VoxelGrid.kt Level.kt TargetMatrix.kt Verifier.kt Score.kt
  render/AsciiRenderer.kt
:core/src/test/kotlin/...            # pure unit tests, no Gradle gymnastics
:dsl/src/main/kotlin/fr/godox/replikube/dsl/
  Palette.kt Geometry.kt Replikube.kt
:scripting/src/main/kotlin/fr/godox/replikube/scripting/
  ScriptRunner.kt SolutionCompiler.kt InProcessScriptRunner.kt
:levels/src/main/resources/levels/*.json    # metadata + target matrix + reference
:levels/src/main/kotlin/fr/godox/replikube/levels/LevelCatalog.kt
:app/src/main/kotlin/fr/godox/replikube/app/
  Main.kt GameModel.kt
  progress/ProgressStore.kt
  render/Projection.kt render/PaletteColors.kt render/Cutaway.kt
  ui/AppTheme.kt ui/GameScreen.kt ui/LevelPicker.kt ui/VoxelViewport.kt
  ui/CodeEditor.kt ui/ResultPanel.kt ui/CutawayBar.kt ui/SyntaxHighlight.kt
:app/src/test/kotlin/fr/godox/replikube/app/
  ProjectionTest.kt GameModelTest.kt ProgressStoreTest.kt CutawayTest.kt
  SyntaxHighlightTest.kt
  ViewportRenderTest.kt GameScreenRenderTest.kt   # headless PNG renders, no window
```

`GameModel` is deliberately not Compose state. It owns every rule — what counts as a solve,
what earns three stars, when a stale result may be shown — and runs compilation off the
main thread, so the whole state machine is testable without a window and the UI layer stays
thin enough to be replaced.

### Decisions and their reasons

| Decision | Reason |
|---|---|
| One contract: `fun block(x,y,z): Int`, all levels | one rule, one doc, one error message; difficulty lives in the problem |
| Levels authored as reference `.kt` solutions | provably solvable, reviewable in a diff, single dialect |
| Targets compiled lazily and cached | a shape is *defined* by its reference solution, so it cannot drift from its level; precomputing would mean two sources of truth |
| Player code nested in `object Player` | keeps the top-level `block` contract *and* gives the runtime a `Replikube` |
| `K2JVMCompiler` driven directly, not `.kts` | `.kts` is gone on Kotlin 2.4.20; this also removed the footer workaround |
| `run` evaluates and returns the grid | one timeout, one exception path, one place to map errors |
| `ScriptRunner` interface, in-process now | safe to ship offline; isolation is a later drop-in |
| Compose Desktop + isometric painter | no engine dependency, deterministic, cache already warm |
| Camera as an orthonormal basis | correct by construction; the `cos(30°)` shortcut is not |
| `:dsl` dependency-free | enforces "player sees only this" by construction |
| No Material in the UI | `org.jetbrains.compose.material3` is published only to 1.9.0 and `ui-tooling-preview` not at all; the chrome is a title, a caption, a code block and a message, which `BasicText` + `background` + `border` covers |
| Headless render tests | a renderer is the one kind of code where "the tests pass" says nothing; `ImageComposeScene` puts real pixels in a PNG |

---

## 7. 3D rendering

No LWJGL/JOGL. A `Canvas` painter with a hand-rolled projection:

- Isometric camera, orbit + zoom via pointer drag / scroll, double-click to re-fit.
- **The camera is an orthonormal basis, not a pile of sine factors.** Given a yaw about the
  vertical axis and a pitch away from overhead, `view` points from the grid towards the
  camera, `right` is horizontal and perpendicular to `view`, and `up = right × view`. Screen
  position is two dot products. The usual alternative — scaling each axis by `cos(30°)` —
  has to re-derive orthonormality by hand, and getting it slightly wrong stretches the grid
  in a way that is hard to notice. That is not hypothetical: the first implementation was
  missing exactly that factor and drew every grid four times too narrow.
- Painter's-algorithm depth sort. Correct for axis-aligned unit cubes with no
  interpenetration — which is all we ever render, so there is no depth buffer and no
  overdraw subtlety. Depth is the component along `view`; **larger is nearer**, so the sort
  is ascending.
- A face is visible exactly when its outward normal has a positive component along `view`.
  That makes which faces are visible a *derived* property of the camera rather than a
  hard-coded "top, plus two sides", which is what makes free orbit correct at any angle.
- Faces get simple shading: the top face is brighter, the sides darker. Pitch is clamped
  well short of the poles, where the top face degenerates to a line.
- The level volume is drawn as a full wireframe cage, so the player always knows where
  they are in the space.
- **Two viewports, one camera.** The target is drawn above and the player's result below,
  in a `Column`, both reading a single `OrbitState`. Stacked rather than side by side
  because the two are compared along `y`: matching layers land on the same scanline in both
  panes, so the eye checks one against the other directly, whereas across a horizontal gap
  they line up only by accident. Sharing the camera is what makes the comparison free —
  with two cameras, every glance between the panes would need a correction for the angle.
  The camera is Compose snapshot state (`mutableFloatStateOf`) rather than a mutable
  `Projection`: a plain object mutated from a pointer handler does not invalidate the draw
  phase, which is how the viewport once orbited into a frozen picture.
- **Both panes are solid, full-palette cubes.** A translucent ghost is a worse picture of
  the shape than a filled one, and a voxel puzzle whose subject is faint is not a puzzle.
  The earlier design — target as ghost, solid only after a solve — split the difference in
  the wrong direction: the player was asked to reason about a picture that was deliberately
  harder to read, and the two panes could not be compared like for like.
- Diff on a failed attempt: wrong-colour and extra voxels get a red outline and keep their
  true palette colour. Outlining rather than recolouring is deliberate — tinting a cube
  would make the pane stop being a faithful picture of the code that ran, and the player
  needs to see their own colour to debug it. Missing voxels get no marker, because the
  target pane above already shows the hole.
- Hover picks a voxel by ray-facing: the visible face quads of every non-empty voxel are
  tested against the pointer and the nearest wins. Picking a hole therefore returns the
  cube *behind* it rather than nothing, which is what the player means by pointing there.
  The hovered cube is filled white and its coordinates are pinned to the status line with
  the palette name — a stable fact, so it does not want to be a tooltip that fades in.

- **Cutaway is a value, and it hides rather than ghosts.** `Cutaway(x, y, z)` names the
  highest *visible* layer on each axis; everything above is removed. It is plain data with
  no Compose dependency, and it exists because translucency in front of opaque cubes does
  not reveal depth — you cannot see into a solid shape by making its front faces faint, you
  can only make the shape harder to read. Removing layers gives a half-space, which is what
  "look inside" actually means, and it composes: `hollow-frame` needs it.
- **Picking honours the cutaway.** Otherwise hover highlights cubes that are not drawn, and
  the coordinate readout lies about what is on screen. This is a real bug class: the filter
  has to be applied in `drawVoxels`, in `pick`, *and* again before the hover fill, because
  those are three separate call sites.
- **The cutaway control is a bar under both panes**, not a per-pane control: it is a
  property of the two pictures together, exactly like the camera, and it shares the
  cutaway for the same reason. Reset per level via `remember(state.currentLevelId)`.
- **Ctrl+scroll was rejected for cutaway.** It would need a camera-relative axis, whose
  meaning changes as the player orbits — so the same gesture would cut a different axis
  depending on view angle. Three explicit X/Y/Z sliders say what they do.

Performance is a non-issue at ≤ 4096 boxes for a painter that repaints on state change;
`Projection` is plain geometry with no Compose dependency, so it is unit-tested directly.

## 8. Editor

Plain `BasicTextField` with a monospace font, a line-number gutter, and a template
prefilled. Early levels get a near-complete skeleton so the first thing a player does is
edit rather than type; later ones get a bare function so the shape of the contract stays
visible but the first idea is theirs.

```kotlin
fun block(x: Int, y: Int, z: Int): Int = when (y) {
    1 -> RED
    0 -> YELLOW
    else -> GREEN
}
```

Non-negotiable while it stays plain: **Ctrl+Enter runs**, the error is shown inline with
its line number, and the gutter highlights the offending line. The gutter's line height
must equal the text's `lineHeight` or it drifts away from the code.

### Syntax highlighting

A `VisualTransformation` over a pure `highlight(code): AnnotatedString`. Not a Kotlin
parser: the compiler API fails on every half-typed line, and the player is half-typing
every line they ever write. A small hand-written lexer, biased towards colouring
something rather than nothing.

The identity `OffsetMapping` is correct only because `highlight` is byte-identical to its
input — that is a claim, so it is pinned by a test rather than left to inspection.

Palette constants are painted in their own colour, each lifted toward white by the
*least* amount that clears WCAG **4.5:1** against the editor background, hue preserved
(bisection, 32 steps). A palette colour is a rendering choice — it does not have to
survive a contrast check — but *text* does, and a name like `INDIGO` painted in indigo on
a dark background is unreadable. The lift is the minimum that fixes that, so the colour
still looks like itself.

## 9. Scoring

- Solved = exact match (structure and colour).
- Stars: 3 = at or under `par` lines, 2 = within 2× par, 1 = solved.
- Attempt counter per level, shown as a small trophy, never as punishment.
- Progress (`solved`, `stars`, `attempts`, `lastSolution`) serialized to
  `~/.local/share/replikube/progress.json` via kotlinx.serialization.
- Hints: one free hint per level from metadata; further hints unlockable later.

## 10. Level curriculum (v1, ~20)

Ordered so each level introduces exactly one idea:

| # | id | introduces |
|---|---|---|
| 1 | `hello-layers` | constants, `when` on `y` |
| 2 | `single-voxel` | absolute value, one special voxel |
| 3 | `frame` | `if` + geometry helper |
| 4 | `checkerboard` | parity |
| 5 | `diamond` | manhattan distance |
| 6 | `sphere` | euclidean distance, `inSphere` |
| 7 | `corner-cube` | bounds checks on all three axes |
| 8 | `stairs` | top-level `val` for reuse |
| 9 | `nested-rings` | helper `fun` |
| 10 | `plaid` | two parities combined |
| 11 | `pyramid` | distance from a corner |
| 12 | `checker-sphere` | composition of two ideas |
| 13 | `carpet` | recursion |
| 14 | `two-colour-split` | multiple named regions |
| 15 | `hollow-frame` | shell vs solid |
| 16 | `mirrored` | `abs` symmetry |
| 17 | `rotational` | rotation around an axis |
| 18 | `spiral` | state / accumulation |
| 19 | `arch` | composition + exclusions |
| 20 | `cathedral` | capstone |

`maze` was renamed to `carpet` while authoring: a genuine maze needs a mutable
buffer and a search, which teaches state and graph traversal at once — two ideas in
the level that exists to teach one. A Sierpinski carpet needs only a recursive
predicate, and still isolates recursion. It is the honest way to spend the slot.

## 11. Milestones

| # | Milestone | Done when |
|---|---|---|
| 0 | **Spike** | throwaway `main()`: drive `K2JVMCompiler` on a source string, read back a grid, print an ASCII diff |
| 1 | **Core** | `:core` + `:dsl` + `:scripting`, unit-tested, no UI |
| 2 | **Terminal play** | ASCII renderer (three Y slices, side by side) — all 20 levels authored and tuned |
| 3 | **Compose UI** | viewport, editor, level picker, progress persistence |
| 4 | **Juice** | hints, stars, animations, polish |

Milestone 2 is deliberately not skipped: authoring 20 levels through an ASCII
renderer is fast, and levels are the actual content of the game.

## 12. Task list

Done:

- [x] **M0** Toolchain pinned via `org.gradle.java.installations.paths` in
      `gradle.properties`. The `.kts` spike was abandoned — `BasicKotlinScriptEngine`
      does not exist on Kotlin 2.4.20 — and replaced by driving `K2JVMCompiler`
      directly, which removed the generated-footer workaround outright.
- [x] **M1** `:utils` → `:core`; `Coord`, `GridSize`, `VoxelGrid`, `Level`, `Verifier`, `Score`.
- [x] **M1** `:dsl` with palette constants + geometry helpers; zero dependencies. The
      palette indices are a **file format** — they appear in level metadata, shared
      solutions and saved progress — so they are pinned by test, along with the invariant
      that the hand-written name table cannot drift from the constants.
- [x] **M1** `ScriptRunner` + `InProcessScriptRunner` + `Diagnostic` mapping, proven by
      11 end-to-end tests that compile real Kotlin.
- [x] **M2** `AsciiRenderer` (three `y` slices side by side, plus a diff renderer).
- [x] **M2** `LevelCatalog` with build-time validation of all 20 levels.
- [x] **M2** The 20 levels authored and tuned against the ASCII renderer.
- [x] **M3** `GameModel` — the whole state machine, off the UI thread, behind an interface
      the UI only reads: 19 tests covering solves, diffs, stars, diagnostics, timeouts and
      save-file round-trips, with a stubbed runner so none of them compiles Kotlin.
- [x] **M3** `ProgressStore` — atomic XDG `progress.json`, corrupt files set aside rather
      than fatal, unknown keys tolerated.
- [x] **M3** `Projection` as pure geometry: an orthonormal camera basis rather than
      hand-tuned `cos(30°)` factors, so a shear is impossible by construction.
- [x] **M3** `VoxelViewport` — painter's algorithm, drag-orbit, scroll-zoom, hover picking
      with a coordinate readout, diff outlines. `Projection.pick` is tested separately from
      the painter, then the two are checked against each other: pick a voxel, render the
      frame with it hovered, and confirm the pixels actually changed. Without that last
      step, `pick` could be self-consistently wrong — agreeing with itself about which cube
      is nearest while the painter draws a different one.
- [x] **M3** `CodeEditor`, `ResultPanel`, `LevelPicker`, `GameScreen`. No Material: all
      chrome is `BasicText` + `background` + `border`. `org.jetbrains.compose.material3` is
      published only up to 1.9.0 and `ui-tooling-preview` not at all, so a Material
      dependency would have been a version pin on a UI that is a title, a caption, a code
      block and a message.
- [x] **M3** Headless render tests. `ImageComposeScene` renders every level's target and
      the whole screen to PNG with no window, which is the only way to review a renderer
      and the only way to catch a crash in a composable. It also dumps an ASCII silhouette,
      because a PNG cannot be diffed in a terminal and "the test passed" says nothing
      about whether the projection is right. This is what found the projection's missing
      `ISO_FACTOR` (a grid four times too narrow) and the viewport drawing *nothing* in the
      default view mode.

Still to do:

- [ ] **M4** Win animation.
- [ ] **M4** Compile caching by `sha256(source + size)`, deferred until the one-second
      compile is felt in play (see §5).

Settled along the way:

- **Reveal policy** (§13.2): the reference solution is revealed once a level is solved.
  The target needs no gating at all — it is drawn solid, above the player's pane, from the
  first frame. An earlier design drew it as a ghost and only ever solidified it after a
  solve, on the reasoning that a solid cube "reads as an answer being handed over". That
  was wrong twice over: it made the subject of the puzzle the faintest thing on screen, and
  it meant the two panes were not comparable like for like. Showing the answer in full and
  making the player produce it is the whole game.
- **Viewport rotation** (§13.1): free orbit by drag, shared between both panes,
  double-click to re-fit.
- **Level picker gating**: not gated. Hiding levels punishes a player who lost their save
  file and hides the shape of the curriculum, which is itself worth seeing.

Three bugs found by the tests rather than by looking, which is the argument for having them:

1. The camera basis was captured once in the constructor while `yaw` and `pitch` stayed
   mutable, so the viewport could be dragged but would not move — and every assertion about
   the angles still passed. Fixed by recomputing the basis in the setters; the regression
   test mutates the camera and checks the projection moved.
2. The projection was missing its isometric factor, drawing every grid four times too
   narrow. Found by rendering a level to a PNG and comparing it to the ASCII render of the
   same level, which had been right all along.
3. The default view mode drew no target at all, so the game opened on an empty box. Found
   by rendering the *whole screen* headlessly and noticing the viewport was blank.

Two more of the same kind, from the stacked-viewport work:

4. The camera was a mutable `Projection` held in `remember`, so dragging orbited the
   object but never invalidated the draw phase and the canvas kept showing the previous
   frame. Every assertion about the angles passed, because the angles really did change.
5. Hover picking was verified only against itself — 13 tests proving `pick` agrees with
   its own definition of "nearest face". A painter drawing a different cube would have
   left all 13 green. Fixed by a test that picks a voxel, renders it hovered, and counts
   the white pixels that appear.

And three that were my own test-authoring mistakes rather than product bugs, recorded
because the pattern is the lesson: an assertion about this geometry is worth nothing until
it has been probed. `when` on Kotlin 2.4.20 must be exhaustive even with no subject, so an
empty `when` is a compile error rather than "returns null"; `GridSize(4,2,5)` has no middle
layer; a cube's centre point can land exactly on a corner shared with three neighbours.
And `(0,0,0)` is the *fixed point* of every camera, since the projection is centred on the
grid — asserting that an orbit moves it proves nothing. Each was found by running the
probe, seeing it fail, and reading the geometry, rather than by adjusting the number until
it went green.

### Bugs the tests found, from the packaging work

6. `Log.path` handed `file:/x.jar` strings straight to `File`, which parses them as
   *relative* paths. Every real jar in the packaged app was therefore reported `MISSING`
   while existing — a log that lies is worse than no log, because it sends the next hour
   of debugging in the wrong direction. Fixed by resolving `URL`s properly: `File` as-is,
   `file:` via `toURI()`, `jar:` via `JarURLConnection.jarFileURL`. States are now
   `exists` / `MISSING` / `null` / `not a filesystem path (jar URL)`.
7. `InProcessScriptRunner` hardcoded `JVM_TARGET = JvmTarget.JVM_21`, so player code was
   always Java 21 bytecode — class file 65 — regardless of the JVM that would load it. The
   packaged app happened to bundle a 21 runtime and was fine; the test JVM was 17 and 17
   tests failed with `UnsupportedClassVersionError`. The message names neither the level nor
   the compiler, so it reads as a corrupt install. Now derived from the *running* JVM and
   capped at the project's toolchain floor.
8. That fix was initially incomplete and silently so: `JVM_TARGET` was set in **two**
   places, and `K2JVMCompiler.exec` copies `compilerArguments` over the configuration
   wholesale, so the arguments won. One place said 17, the other said 21, and the code
   compiled 21. A constant duplicated in two places that must agree is worth a comment
   saying *which one wins*, not one saying what the value is.
9. `getResources()` returns a one-shot `Enumeration`. Counting it and then iterating it
   silently yields nothing, so level discovery "worked" and found zero levels.
10. Two different Kotlin API assumptions, both caught only by running them: `MainKt` is a
    *function* facade, not a class, so `protectionDomain` cannot be read off it; and `Level`
    has `title`, not `name`.
11. **`LevelCatalog.resourceIds()` is discovery order, not curriculum order.** It is sorted
    alphabetically, because that is what the classpath enumerates; curriculum order comes
    from `sortedBy { difficulty }` in `levels`. A test asking for "the first level" via
    `resourceIds()` got `arch` (7×7×7) while the screen it was driving opened
    `hello-layers` (3×3×3). It failed on an assertion with nothing in the message
    pointing at the cause. Documented on the function, because the two names invite each
    other's misuse.
12. `GameModelTest` and `GameScreenRenderTest` passed against a **hand-built stand-in grid**
    — a 3×3×3 red bottom layer, and later a three-colour tower — while the level they
    actually opened was 27 solid voxels in three colours. The tower was almost
    `hello-layers`: same size, same three colours, wrong assignment. Every assertion about
    solving was measuring a shape the game never shows. Both now read the real target from
    the shipped matrix. The general lesson is the one from the geometry tests above: an
    assertion is worth nothing until it is probed, and a *fixture* that resembles the real
    thing is the most dangerous kind of unprobed assertion there is.

## 13. Open questions

1. ~~**Rotation of the viewport**~~ — settled: free orbit by drag, double-click to re-fit.
2. ~~**Reveal policy**~~ — settled: see §12. The reference solution is revealed after a
   solve; the target is always drawn solid, in the pane above the player's.
3. **Level size ceiling** — 16³ is 4096 voxels; is there a level that genuinely
   needs 32³? That would force a rendering rethink (instancing, greedy meshing). The
   painter is O(voxels × faces) per frame with no caching, so ~8k cubes is where it stops
   being comfortable.
4. ~~**Persistence**~~ — settled: a single JSON file under XDG data home, written
   atomically. A per-player file in the repo would put a save file under version control
   for no benefit.
5. ~~**Should the editor show syntax highlighting?**~~ — settled, see §8: a
   `VisualTransformation` over a hand-written lexer. No text library needed.
6. **Should levels be built into a binary format instead of JSON?** The matrices are 23 KB
   of digits for the whole campaign, which is nothing. If it ever became 10 MB, a flat
   `Int8Array` resource would beat JSON — but the readable text is worth more than the
   bytes, and the failure mode of a binary format (silently transposed) is the one this
   format is specifically laid out to prevent.
7. **Can the app image be verified on a real FUSE mount?** `packageAppImage` runs
   `jpackage --type app-image` and produces a *directory*, not a squashfs `.AppImage` —
   `appimagetool` and `linuxdeploy` are not available here. The directory form was
   exercised with the whole tree `chmod -R a-w`, which reproduces the read-only condition
   but not the FUSE protocol itself. A genuine `.AppImage` round trip is still unverified.
