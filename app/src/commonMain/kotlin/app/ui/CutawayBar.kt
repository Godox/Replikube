package fr.godox.replikube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import fr.godox.replikube.app.render.Cutaway
import fr.godox.replikube.core.GridSize
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The cutaway control: one slider per axis, plus a reset.
 *
 * ### Why the sliders are per-axis and not one "see inside" button
 *
 * A single button would have to guess which way to cut, and the guess would be wrong half
 * the time depending on which way the player has orbited the camera — the control would
 * appear to do nothing. Three sliders are unambiguous from any angle, and together they
 * cover every case a single button would: peel one face off, slice to a layer, or open a
 * corner by combining two.
 *
 * ### Why the cut is from the high end
 *
 * See [Cutaway]. The count is from `+` so that at the default camera — which looks at the
 * positive corner — dragging a slider away removes the *near* half and reveals the
 * interior. A control that removed the far half would look broken.
 *
 * ### Why the sliders snap to whole layers
 *
 * The cut is in voxels, not pixels. Dragging smoothly and rounding at the end would mean
 * the picture changes in jumps after the thumb has stopped, which reads as the app
 * disagreeing with the mouse. Snapping to the layer means the number under the thumb is
 * always the number of layers hidden.
 */
@Composable
fun CutawayBar(
    size: GridSize,
    cutaway: Cutaway,
    onCutawayChange: (Cutaway) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cut = cutaway.clampedTo(size)
    val shown = size.voxelCount - cut.keptCount(size)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, AppColors.divider, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("Cut", style = AppText.caption, color = AppColors.muted)

        CutSlider(
            label = "X",
            layers = cut.x,
            max = size.x - 1,
            onLayersChange = { onCutawayChange(cut.copy(x = it)) },
            modifier = Modifier.weight(1f),
        )
        CutSlider(
            label = "Y",
            layers = cut.y,
            max = size.y - 1,
            onLayersChange = { onCutawayChange(cut.copy(y = it)) },
            modifier = Modifier.weight(1f),
        )
        CutSlider(
            label = "Z",
            layers = cut.z,
            max = size.z - 1,
            onLayersChange = { onCutawayChange(cut.copy(z = it)) },
            modifier = Modifier.weight(1f),
        )

        Label(
            // Says what is hidden, not just that something is. "17 hidden" is the number
            // the player can check their diff against.
            text = "$shown/${size.voxelCount} hidden",
            style = AppText.caption,
            color = if (cut.isOff) AppColors.faint else AppColors.accent,
        )

        if (!cut.isOff) {
            Label(
                text = "Reset",
                style = AppText.caption,
                color = AppColors.muted,
                modifier = Modifier
                    .background(AppColors.editor, RoundedCornerShape(4.dp))
                    .clickable { onCutawayChange(Cutaway.Off) }
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * One axis's cutaway slider.
 *
 * Drag or click anywhere on the track; both go through the same position-to-layers
 * mapping, so clicking ahead of the thumb jumps there rather than nudging by one notch.
 */
@Composable
private fun CutSlider(
    label: String,
    layers: Int,
    max: Int,
    onLayersChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Just the width, not an IntSize: that is all the position mapping needs, and a wider
    // state type would make every use below unpack and repack it.
    var trackWidth by remember { mutableStateOf(0) }

    fun setFrom(offset: Offset) {
        if (trackWidth <= 0) return
        val fraction = (offset.x / trackWidth).coerceIn(0f, 1f)
        onLayersChange((fraction * max).roundToInt())
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Label(
            text = label,
            style = AppText.gutter,
            color = if (layers > 0) AppColors.accent else AppColors.faint,
        )
        Box(
            modifier = Modifier
                .padding(start = 5.dp)
                .weight(1f)
                .height(TRACK_HEIGHT.dp)
                .background(AppColors.editor, RoundedCornerShape(2.dp))
                .onSizeChanged { trackWidth = it.width }
                // Tap and drag are separate handlers rather than one, because
                // `detectDragGestures` only reports movement past the touch slop: a click
                // alone would leave the slider where it was.
                .pointerInput(max) {
                    detectTapGestures { setFrom(it) }
                }
                .pointerInput(max) {
                    detectDragGestures(
                        onDragStart = { setFrom(it) },
                        onDragEnd = {},
                        onDragCancel = {},
                    ) { change, _ ->
                        change.consume()
                        setFrom(change.position)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = if (max <= 0) 0f else layers.toFloat() / max
            if (fraction > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(AppColors.accent.copy(alpha = 0.55f)),
                )
            }
            Box(
                Modifier
                    .padding(start = max(1.toFloat(), fraction * trackWidth - THUMB_SIZE / 2).dp)
                    .size(THUMB_SIZE.dp)
                    .background(if (layers > 0) AppColors.accent else AppColors.muted, RoundedCornerShape(3.dp)),
            )
        }
        Label(
            text = layers.toString(),
            style = AppText.gutter,
            color = if (layers > 0) AppColors.accent else AppColors.faint,
            modifier = Modifier.padding(start = 5.dp),
        )
    }
}

private const val TRACK_HEIGHT = 6
private const val THUMB_SIZE = 12