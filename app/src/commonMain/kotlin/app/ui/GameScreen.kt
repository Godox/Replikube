package fr.godox.replikube.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fr.godox.replikube.app.GameModel
import fr.godox.replikube.app.GameState
import fr.godox.replikube.app.RunOutcome
import fr.godox.replikube.app.render.Cutaway
import fr.godox.replikube.core.Coord
import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.dsl.Palette

/**
 * The play screen: level list, two viewports, editor, feedback.
 *
 * Four columns, fixed. The layout never reflows as levels get harder — a player who has
 * learned where the Run button is should not have to look for it again at level 20 — so
 * the editor keeps a constant width and the viewports take whatever is left.
 */
@Composable
fun GameScreen(model: GameModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()

    // One camera for both panes, created here so it survives recomposition. Rotating
    // either viewport rotates both, which is what makes the two pictures comparable.
    val camera = rememberOrbitState()

    // Which pane the pointer is in, and what it is over. Held here rather than inside
    // `Pane` so the readout can live in the footer, outside both viewports.
    var hovered by remember { mutableStateOf<Coord?>(null) }
    var hoverPane by remember { mutableStateOf(PaneId.TARGET) }

    // Shared for the same reason the camera is: the player compares the panes, and two
    // shapes cut open differently are two different pictures rather than one shape twice.
    // Resets per level, since a cut of two layers on a 5³ means nothing on a 9³.
    var cutaway by remember(state.currentLevelId) { mutableStateOf(Cutaway.Off) }

    // Open the first unsolved level once, on first composition. Reading progress here
    // rather than in the model keeps the model free of "which level did I start on"
    // decisions, which are a UI concern.
    LaunchedEffect(Unit) {
        val firstUnsolved = state.levels.firstOrNull { !state.progress[it.id].solved }
        model.start((firstUnsolved ?: state.levels.firstOrNull())?.id ?: return@LaunchedEffect)
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background)
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LevelPicker(
            levels = state.levels,
            starsByLevel = remember(state.progress) {
                state.progress.levels.mapValues { (_, p) -> p.stars }
            },
            currentLevelId = state.currentLevelId,
            onSelect = model::start,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Header(state, model)

            // Target above, player's solution below. Stacked rather than side by side
            // because the two are compared vertically: matching layers line up across a
            // horizontal gap only by accident, whereas here the same `y` is on the same
            // scanline in both panes and the eye can check one against the other directly.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val size = state.level?.level?.size
                when {
                    state.levelError != null -> Message(state.levelError!!, AppColors.error)
                    size == null -> Loading()
                    else -> {
                        Pane(
                            caption = "Target",
                            grid = state.target ?: emptyGrid(size),
                            size = size,
                            camera = camera,
                            cutaway = cutaway,
                            modifier = Modifier.weight(1f),
                            onHover = {
                                hovered = it
                                hoverPane = PaneId.TARGET
                            },
                        )
                        Pane(
                            caption = "Your solution",
                            grid = state.result ?: emptyGrid(size),
                            size = size,
                            camera = camera,
                            cutaway = cutaway,
                            modifier = Modifier.weight(1f),
                            marked = state.errorCoords,
                            // Leaving the pane clears the readout, so the coordinates do not
                            // linger after the pointer has moved to the editor.
                            onHover = {
                                hovered = it
                                hoverPane = PaneId.SOLUTION
                            },
                        )
                    }
                }
                // Below the panes rather than beside them: the cut is a property of *both*
                // pictures at once, and a control that sat next to one pane would look like
                // it belonged to that pane alone.
                size?.let {
                    CutawayBar(
                        size = it,
                        cutaway = cutaway,
                        onCutawayChange = { cutaway = it },
                    )
                }
            }
            Footer(
                state = state,
                model = model,
                readout = {
                    VoxelReadout(
                        coord = hovered,
                        color = hovered?.let { c ->
                            when (hoverPane) {
                                PaneId.TARGET -> state.target?.get(c) ?: Palette.EMPTY
                                PaneId.SOLUTION -> state.result?.get(c) ?: Palette.EMPTY
                            }
                        } ?: Palette.EMPTY,
                        modifier = Modifier.weight(1f),
                    )
                },
            )
        }

        Column(
            modifier = Modifier
                .width(EDITOR_PANE_WIDTH.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CodeEditor(
                code = state.code,
                onCodeChange = model::setCode,
                diagnostic = (state.outcome as? RunOutcome.Failed)?.diagnostic,
                onRun = { model.run() },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            ResultPanel(
                outcome = state.outcome,
                par = state.level?.level?.par ?: 0,
            )
        }
    }
}

@Composable
private fun Header(state: GameState, model: GameModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            val level = state.level?.level
            Label(
                text = level?.title ?: "Replikube",
                style = AppText.title,
                color = AppColors.foreground,
                fontWeight = FontWeight.Bold,
            )
            Label(
                text = level?.let {
                    "${it.size.x}×${it.size.y}×${it.size.z}  ·  par ${it.par} lines  ·  " +
                        "${state.codeLineCount} lines"
                } ?: "Reproduce the shape by writing Kotlin.",
                style = AppText.caption,
            )
        }
        Button("Run", primary = true, enabled = state.level != null && state.outcome != RunOutcome.Running) {
            model.run()
        }
        Button("Reset") { model.resetCode() }
        if (state.canRevealReference) {
            Button("Reveal") { model.revealReference() }
        }
        Button(if (state.hintVisible) "Hide hint" else "Hint") { model.toggleHint() }
    }

    if (state.hintVisible) {
        val hint = state.level?.level?.hint
        Label(
            text = hint.orEmpty().ifEmpty { "No hint for this level." },
            style = AppText.body,
            color = AppColors.muted,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(AppColors.surface, RoundedCornerShape(4.dp))
                .padding(10.dp),
        )
    }
}

@Composable
private fun Footer(state: GameState, model: GameModel, readout: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(
            text = "★ ${state.progress.totalStars}",
            style = AppText.title,
            color = AppColors.star,
        )
        Label(
            text = "${state.progress.solvedIds.size}/${state.levels.size} solved",
            style = AppText.caption,
        )
        Label(
            text = "Drag to rotate · scroll to zoom",
            style = AppText.caption,
            color = AppColors.faint,
        )
        readout()
        if (state.saveFailed) {
            Label("Progress could not be saved.", style = AppText.caption, color = AppColors.error)
        }
    }
}

/**
 * One labelled viewport, filling half the vertical space.
 *
 * The caption is not decoration: with two panes of near-identical cubes on screen, "which
 * one am I looking at" is otherwise a question the player has to answer by remembering
 * which was on top.
 */
@Composable
private fun Pane(
    caption: String,
    grid: VoxelGrid,
    size: GridSize,
    camera: OrbitState,
    cutaway: Cutaway,
    modifier: Modifier = Modifier,
    marked: Set<Coord> = emptySet(),
    onHover: (Coord?) -> Unit = {},
) {
    Column(
        // The weight comes from the caller, which is inside the enclosing `Column`: weight
        // is a `ColumnScope` extension, so a top-level composable cannot apply it itself.
        modifier = modifier.fillMaxWidth(),
    ) {
        Label(
            text = caption,
            style = AppText.caption,
            color = AppColors.muted,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        VoxelViewport(
            grid = grid,
            size = size,
            camera = camera,
            marked = marked,
            cutaway = cutaway,
            onHover = onHover,
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.background, RoundedCornerShape(6.dp))
                .border(1.dp, AppColors.divider, RoundedCornerShape(6.dp)),
        )
    }
}

@Composable
private fun Button(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> AppColors.surface
        primary -> AppColors.accent
        else -> AppColors.surface
    }
    Label(
        text = label,
        style = AppText.body,
        color = when {
            !enabled -> AppColors.faint
            primary -> AppColors.background
            else -> AppColors.foreground
        },
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .border(1.dp, if (primary) AppColors.accent else AppColors.divider, RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun Loading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spinner()
        Label("Building the level…", style = AppText.caption, modifier = Modifier.padding(top = 10.dp))
    }
}

/**
 * A three-dot pulse, rather than Material's spinner.
 *
 * Waiting for a level means waiting on a ~1 s Kotlin compile. A determinate spinner would
 * be a lie — the compile time is not knowable in advance — and three dots that pulse read
 * as "working" without claiming a percentage that does not exist.
 */
@Composable
private fun Spinner() {
    val transition = rememberInfiniteTransition()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 200, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(9.dp)
                    .background(AppColors.accent.copy(alpha = alpha), RoundedCornerShape(5.dp)),
            )
        }
    }
}

@Composable
private fun Message(text: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, AppColors.divider, RoundedCornerShape(6.dp))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(text, style = AppText.body, color = color)
    }
}

/**
 * A blank grid of [size].
 *
 * The player's pane shows one before the first run rather than being absent, so that the
 * camera, the zoom and the layout are all where the player expects them when their first
 * result appears. A pane that appears only on success is a pane that moves.
 */
private fun emptyGrid(size: GridSize): VoxelGrid = VoxelGrid.empty(size)

/** Which of the two stacked viewports the pointer was last in. */
private enum class PaneId { TARGET, SOLUTION }

private const val EDITOR_PANE_WIDTH = 460
