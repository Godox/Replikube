package fr.godox.replikube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import fr.godox.replikube.core.Coord
import fr.godox.replikube.dsl.Palette

/**
 * What the pointer is pointing at.
 *
 * The coordinates alone answer "where", but a voxel puzzle is mostly a question about
 * colour: the shape is easy to see and the mistake is nearly always a wrong constant or an
 * off-by-one in a condition. Showing the palette name next to the number means a player
 * comparing this readout against their code can read off the mismatch directly — "it says
 * EMPTY, my code returns GREEN" — without counting through the palette table.
 *
 * ### Why this is not a tooltip
 *
 * A Compose tooltip floats over the pointer and fades in, which is the right behaviour for
 * transient chrome but a poor fit here. The voxel being hovered does not change until the
 * player moves the mouse, so the readout is stable, and a stable fact does not want to be
 * hidden behind a delay or disappear when the pointer is still. It is pinned to the
 * status line instead, where it stays for as long as it is true.
 *
 * @param coord the voxel under the pointer, or null when the pointer is not over one.
 * @param color the colour that voxel holds. A hovered voxel is never empty by
 *   construction, but the type does not promise that, so it is passed separately.
 */
@Composable
fun VoxelReadout(coord: Coord?, color: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(AppColors.surface, RoundedCornerShape(4.dp))
            .border(1.dp, AppColors.divider, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        if (coord == null) {
            Label(
                text = "Hover a cube to read its coordinates",
                style = AppText.caption,
                color = AppColors.faint,
            )
        } else {
            // Monospace so the numbers line up as the pointer moves between cubes; a
            // proportional font makes the readout jitter, which reads as noise.
            Label(
                text = "$coord   ${Palette.nameOf(color)}",
                style = AppText.code.copy(fontFamily = FontFamily.Monospace),
                color = AppColors.foreground,
            )
        }
    }
}