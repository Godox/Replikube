package fr.godox.replikube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import fr.godox.replikube.app.RunOutcome
import fr.godox.replikube.core.Diff
import fr.godox.replikube.core.Stars
import fr.godox.replikube.scripting.Diagnostic

/**
 * The feedback panel: what happened, and if it was a failure, exactly where and why.
 *
 * Feedback is the whole game. A puzzle game that cannot say *how close* the player is
 * turns into a guessing game, so an unsolved attempt reports counts by category and a
 * failed attempt reports the message, the line and the offending source line itself.
 */
@Composable
fun ResultPanel(
    outcome: RunOutcome,
    par: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppColors.surface, RoundedCornerShape(6.dp))
            .border(1.dp, AppColors.divider, RoundedCornerShape(6.dp))
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (outcome) {
            RunOutcome.Idle -> Label(
                "Write block(x, y, z), then press Run. Ctrl+Enter also runs.",
                style = AppText.body,
                color = AppColors.muted,
            )

            RunOutcome.Running -> Label(
                "Compiling…",
                style = AppText.title,
                color = AppColors.accent,
            )

            is RunOutcome.Failed -> DiagnosticBody(outcome.diagnostic)

            is RunOutcome.Unsolved -> DiffBody(outcome.diff)

            is RunOutcome.Solved -> SolvedBody(outcome.stars, par)
        }
    }
}

@Composable
private fun SolvedBody(stars: Stars, par: Int) {
    Label("Solved.", style = AppText.title, color = AppColors.success)
    Label(
        text = "★".repeat(stars.count) + "☆".repeat(Stars.GOLD.count - stars.count),
        style = AppText.title,
        color = AppColors.star,
    )
    Label("$par lines was par.", style = AppText.caption)
}

@Composable
private fun DiffBody(diff: Diff) {
    val close = diff.errorCount <= 2
    Label(
        text = if (close) {
            "So close — ${diff.errorCount} voxel${plural(diff.errorCount)} off."
        } else {
            "${diff.errorCount} voxels to go."
        },
        style = AppText.title,
        color = if (close) AppColors.near else AppColors.extra,
    )
    // Counts, not coordinates: a 9³ level can have hundreds of wrong voxels, and a list of
    // them is noise. The viewport already shows *where*, in colour.
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Count("missing", diff.missing.size, AppColors.muted)
        Count("wrong colour", diff.wrongColor.size, AppColors.wrong)
        Count("extra", diff.extra.size, AppColors.extra)
    }
}

@Composable
private fun DiagnosticBody(diagnostic: Diagnostic) {
    Label(
        text = diagnostic.kind.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
        style = AppText.title,
        color = AppColors.error,
    )
    Label(diagnostic.message, style = AppText.body)
    diagnostic.snippet?.let { snippet ->
        Label(
            text = snippet.trim().ifEmpty { "(blank line)" },
            style = AppText.code,
            color = AppColors.muted,
            modifier = Modifier
                .background(AppColors.editor, RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    diagnostic.detail?.takeIf { it.isNotBlank() }?.let { detail ->
        Label(
            text = detail.trim().lineSequence().take(3).joinToString(" / "),
            style = AppText.caption,
            color = AppColors.faint,
        )
    }
}

@Composable
private fun Count(label: String, n: Int, color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Label("$n", style = AppText.title, color = color)
        Label(label, style = AppText.caption)
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"
