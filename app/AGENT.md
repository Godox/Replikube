# AGENT.md — `:app`

Compose Desktop UI, the game state machine, the renderer, and local progress. The only
module with UI dependencies, and the only one whose failures need a window to see.

## Layout

```
app/src/main/kotlin/fr/godox/replikube/app/
  Main.kt              entry point, startup/exit logging, module locations
  GameModel.kt         the state machine — every rule lives here
                        SolutionTemplates: the per-level editor starter
  progress/ProgressStore.kt   XDG save file
  render/
    Projection.kt      camera → screen, pure geometry
    PaletteColors.kt   colour id → Color
    Picking.kt         ray-facing voxel pick, Point/Rect/Quad
    Cutaway.kt         Cutaway(x, y, z) — the highest visible layer per axis
  ui/
    GameScreen.kt      the whole screen
    LevelPicker.kt     the level list
    VoxelViewport.kt   the Canvas painter; drawScene() is the drawable
    OrbitState.kt      shared camera, Compose snapshot state
    CutawayBar.kt      the three X/Y/Z sliders
    CodeEditor.kt      the text field, gutter, line-error highlight
    SyntaxHighlight.kt the VisualTransformation and its pure lexer
    ResultPanel.kt     solved / diff / diagnostic
    Readout.kt         the pinned coordinate + palette name status line
    AppTheme.kt        colors
```

## `GameModel` is deliberately not Compose state

It owns every rule — what counts as a solve, what earns three stars, when a stale result
may be shown, when Reveal is allowed — and the UI calls `openLevel` and `run` without ever
touching the runner or the store. That split keeps the Compose layer replaceable and makes
the state machine testable without a window, which is why `GameModelTest` can be fast and
thorough.

`GameState` is one immutable object because Compose wants a single observable snapshot:
mutable fields held separately would let a recomposition read half an update and draw the
new grid beside the old level title.

Compilation runs off the main thread and is cancellable. A player who hits Run twice, or
clicks a different level mid-compile, must not have the first result land on the second
level.

## Rendering

- **The camera is an orthonormal basis, not a pile of sine factors.** `view` points from
  the grid towards the camera, `right` is horizontal and perpendicular, `up = right × view`,
  and a screen position is two dot products. The usual `cos(30°)`-per-axis alternative has
  to re-derive orthonormality by hand, and getting it slightly wrong stretches the grid in
  a way that is hard to notice — which is exactly what happened: the first implementation
  drew every grid four times too narrow.
- **Painter's algorithm, sorted ascending** by depth, where larger depth is nearer.
  Correct for axis-aligned unit cubes with no interpenetration, which is all we render, so
  there is no depth buffer.
- **A face is visible exactly when its outward normal has a positive component along
  `view`.** That makes visibility a derived property of the camera rather than a hard-coded
  "top plus two sides", which is what makes free orbit correct at any angle.
- **`Projection` is plain geometry with no Compose dependency**, so it is unit-tested
  directly. `drawScene()` takes it as a parameter for the same reason.
- **The camera is Compose snapshot state** (`mutableFloatStateOf`), not a mutable
  `Projection` in a `remember`. A plain object mutated from a pointer handler does not
  invalidate the draw phase, which is how the viewport once orbited into a frozen picture —
  the angles really did change, and every assertion about them passed.
- **Two viewports, one camera**, stacked in a `Column`. Stacked because the two are
  compared along `y`: matching layers land on the same scanline in both. Shared because
  with two cameras every glance between panes would need an angle correction.
- **Both panes are solid, full-palette cubes.** A translucent ghost is a worse picture of
  the shape than a filled one, and a voxel puzzle whose subject is faint is not a puzzle.
  Mismatched voxels keep their true colour and get a red outline; recolouring them would
  stop the pane being a faithful picture of the code that ran.
- **The cutaway removes layers, it does not ghost them.** `Cutaway(x, y, z)` counts whole
  layers hidden from the **high** end of each axis (`0` hides nothing, `Cutaway.Off` is the
  default); `hides(coord, size)` is the single predicate. Translucency in front of opaque
  cubes does not reveal depth; it makes the shape harder to read. Removing layers gives a
  half-space, which is what "look inside" means. Counting from the high end is what makes
  the slider mean the same thing on a 3³ and a 9³ level, and it is also the near side for
  the default camera, so the interior is revealed rather than buried.
  Picking honours the cutaway too, so hover never highlights a cube that is not drawn — the
  filter has to be applied in `drawVoxels`, in `pick`, *and* again before the hover fill,
  because those are three separate call sites.

## Editor

`BasicTextField` with a monospace font, a line-number gutter, and a template prefilled.
Errors show inline with the player's own line number and the gutter highlights it. The
gutter's line height must equal the text's `lineHeight` or it drifts away from the code.

**Ctrl+Enter runs**, via `onPreviewKeyEvent` on the field — not `onKeyEvent`, so the key is
consumed before the field inserts a newline. The shortcut lives on `CodeEditor` rather than
on the screen because it means "run what is in *this* field". Worth flagging: `ResultPanel`
advertised this shortcut in a help string for the whole life of the project with **no key
handler anywhere in the module**. An advertised shortcut that silently does nothing is worse
than not offering it, and no test could have caught it — there was no assertion to fail. If
you add a shortcut to a help string, the handler has to exist in the same change.

**Syntax highlighting is a `VisualTransformation` over a pure `highlight(code)`.** Not a
Kotlin parser: the compiler API fails on every half-typed line, and the player is
half-typing every line they ever write. The identity `OffsetMapping` is correct only
because `highlight` is byte-identical to its input — that is a claim, so it is pinned by a
test rather than left to inspection.

Palette constants are painted in their own colour, each lifted toward white by the *least*
amount clearing WCAG 4.5:1 against the editor background, hue preserved (bisection, 32
steps). A palette colour is a rendering choice and does not have to survive a contrast
check, but *text* does — and `INDIGO` painted in indigo on a dark background is unreadable.
The lift is the minimum that fixes that, so the colour still looks like itself.

## Progress

One JSON file at `~/.local/share/replikube/progress.json` (XDG data home), written
atomically. A per-player file in the repo would put a save file under version control for
no benefit. This is the only persistent write in the project; everything else goes to
`/tmp`.

`saveFailed` is a state field rather than an exception, because a disk that is full must
not take the game down.

## Tests

`./gradlew :app:test` — 108 tests, the largest suite. The renderer is the one part of this
project a unit test cannot check for you, so two suites render real pixels through
`ImageComposeScene` with no window:

```bash
./gradlew :app:test --tests '*RenderTest*' --rerun-tasks -i
open app/build/renders/
```

`ViewportRenderTest` renders each level's target; `GameScreenRenderTest` renders the whole
screen. Both print an ASCII silhouette of every frame, because a PNG cannot be diffed in a
terminal. The screenshots in the README are copies of these renders.

**Fixtures must come from real data.** Both render suites, and `GameModelTest`, once passed
against a hand-built stand-in grid while the level they actually opened was 27 solid voxels
in three colours — the stand-in was *almost* right, which is what made it convincing. Read
the real target from the shipped matrix. A fixture resembling the real thing is the most
dangerous kind of unprobed assertion there is.

You cannot screenshot the running window on this machine (the GNOME portal denies it). The
headless renders are the substitute, and they are better anyway — they diff.

