package com.prompter.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Uniform "key-cap" button for the Pult and Commands screens.
 *
 * Key names stay literal (Enter, C-w, ⌫, PgUp): they are TUI keys, not words,
 * so they must look identical in every locale.
 *
 * With [repeat] = true, holding the button auto-repeats the action
 * (after 400 ms, then every 80 ms); a short tap fires exactly once.
 */
@Composable
fun KeyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    filled: Boolean = false,
    repeat: Boolean = false,
) {
    if (!repeat) {
        val shape = RoundedCornerShape(10.dp)
        if (filled) {
            FilledTonalButton(
                onClick = onClick,
                modifier = modifier.height(height),
                shape = shape,
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { KeyLabel(label) }
        } else {
            OutlinedButton(
                onClick = onClick,
                modifier = modifier.height(height),
                shape = shape,
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) { KeyLabel(label) }
        }
        return
    }

    // Hold-to-repeat variant: own non-consuming press tracker, so the
    // Surface's click still handles single taps.
    val currentOnClick by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    var suppressClick by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (pressed) {
            delay(400)
            while (pressed) {
                suppressClick = true
                currentOnClick()
                delay(80)
            }
        }
    }

    Surface(
        onClick = {
            if (!suppressClick) currentOnClick()
            suppressClick = false
        },
        modifier = modifier
            .height(height)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    waitForUpOrCancellation()
                    pressed = false
                }
            },
        shape = RoundedCornerShape(10.dp),
        color = if (filled) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        contentColor = if (filled) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.primary,
        border = if (filled) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Box(contentAlignment = Alignment.Center) { KeyLabel(label) }
    }
}

@Composable
private fun KeyLabel(label: String) {
    Text(
        label,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        maxLines = 1,
        textAlign = TextAlign.Center,
    )
}
