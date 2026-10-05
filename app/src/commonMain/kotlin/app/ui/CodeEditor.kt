package fr.godox.replikube.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import fr.godox.replikube.scripting.Diagnostic

/**
 * The code editor: a monospace field, a line-number gutter, syntax colouring, and the
 * failing line called out.
 *
 * ### Why the colouring is a `VisualTransformation`
 *
 * `BasicTextField` has no notion of styled spans, but it does have a hook for turning one
 * string into another before it is drawn — a `VisualTransformation` — which is the standard
 * way to colour text without giving up the field's own caret, selection and IME handling.
 * Building the styled text by hand and layering a transparent field over it would mean
 * reimplementing all three.
 *
 * The transformation is *lossless*: [highlight] emits the same characters it was given, so
 * the offsets in the result line up one-for-one with the plain text and
 * [OffsetMapping.Identity] is correct. That is not a detail — a transformation that shifted
 * the text by even one character would put the caret in the wrong place after every
 * keystroke, which is the classic way this technique goes wrong.
 *
 * ### Why Ctrl+Enter is handled here
 *
 * The shortcut belongs to the field, not to the screen: it is "run what is in *this* text
 * field", and only this composable knows what that is. It goes on `onPreviewKeyEvent`
 * rather than `onKeyEvent` so the key is consumed *before* the field inserts a newline —
 * `ResultPanel` tells the player this shortcut exists, and an advertised shortcut that
 * silently does nothing is worse than none.
 */
@Composable
fun CodeEditor(
    code: String,
    onCodeChange: (String) -> Unit,
    diagnostic: Diagnostic?,
    modifier: Modifier = Modifier,
    onRun: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val lineCount = remember(code) { code.lines().size.coerceAtLeast(1) }

    Box(
        modifier = modifier
            .border(1.dp, if (focused) AppColors.accent else AppColors.divider, RoundedCornerShape(6.dp))
            .background(AppColors.editor)
            .padding(vertical = GUTTER_PAD.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Gutter(lineCount = lineCount, errorLine = diagnostic?.line)
            BasicTextField(
                value = code,
                onValueChange = onCodeChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = TEXT_PAD.dp)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.isCtrlPressed && event.key == Key.Enter) {
                            onRun()
                            true
                        } else {
                            false
                        }
                    },
                textStyle = AppText.code.copy(color = AppColors.foreground),
                cursorBrush = SolidColor(AppColors.accent),
                // `remember`d on the text, so the transformation object is stable across
                // recompositions and Compose does not re-run the filter it just ran.
                visualTransformation = remember(code) {
                    VisualTransformation { text ->
                        TransformedText(highlight(text.text), OffsetMapping.Identity)
                    }
                },
            )
        }
    }
}

/**
 * The line-number gutter.
 *
 * Laid out as its own fixed-height column rather than measured against the text field.
 * The editor is monospace, so line height is constant and row *n* is exactly
 * [LINE_HEIGHT] * n below the top — no measurement, no shared layout, no scrolling the
 * two apart.
 */
@Composable
private fun Gutter(lineCount: Int, errorLine: Int?) {
    Column(
        modifier = Modifier
            .width(GUTTER_WIDTH.dp)
            .padding(horizontal = GUTTER_PAD.dp),
        horizontalAlignment = Alignment.End,
    ) {
        for (n in 1..lineCount) {
            val isError = n == errorLine
            Box(
                modifier = Modifier
                    .height(LINE_HEIGHT.dp)
                    .background(
                        color = if (isError) AppColors.error.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent,
                        shape = RoundedCornerShape(3.dp),
                    )
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Label(
                    text = n.toString(),
                    style = AppText.gutter,
                    color = if (isError) AppColors.error else AppColors.faint,
                    fontWeight = if (isError) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

/** Must match [AppText.code].lineHeight, or the gutter drifts from the text. */
private const val LINE_HEIGHT = 20
private const val GUTTER_WIDTH = 38
private const val GUTTER_PAD = 6
private const val TEXT_PAD = 8
