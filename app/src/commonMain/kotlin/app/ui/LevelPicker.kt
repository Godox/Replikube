package fr.godox.replikube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.godox.replikube.core.Level
import fr.godox.replikube.core.Stars

/**
 * The campaign list: one row per level, in curriculum order, with stars.
 *
 * Deliberately not gated. Hiding level 5 until level 4 is solved makes a player who has
 * lost their save file replay the first hour before they can start playing again, and it
 * hides the shape of the curriculum — which is itself worth seeing: the titles read as a
 * syllabus, from the first `when` on `y` up to the capstone.
 */
@Composable
fun LevelPicker(
    levels: List<Level>,
    starsByLevel: Map<String, Stars>,
    currentLevelId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier
            .width(PICKER_WIDTH.dp)
            .background(AppColors.surface)
            .border(1.dp, AppColors.divider, RoundedCornerShape(6.dp)),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(levels, key = { it.id }) { level ->
            LevelRow(
                level = level,
                stars = starsByLevel[level.id] ?: Stars.NONE,
                selected = level.id == currentLevelId,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun LevelRow(
    level: Level,
    stars: Stars,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) SELECTED_BG else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable { onSelect(level.id) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label(
            text = level.difficulty.toString(),
            style = AppText.gutter,
            color = if (stars == Stars.NONE) AppColors.faint else AppColors.accent,
        )
        Column(Modifier.weight(1f)) {
            Label(
                text = level.title,
                style = AppText.body,
                color = if (selected) AppColors.accent else AppColors.foreground,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Label(
                text = "${level.size.x}×${level.size.y}×${level.size.z}",
                style = AppText.caption,
            )
        }
        Label(
            text = "★".repeat(stars.count),
            style = AppText.body,
            color = AppColors.star,
        )
    }
}

private const val PICKER_WIDTH = 210
private val SELECTED_BG = Color(0x1F6EA8FE)
