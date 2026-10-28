package com.prompter.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import com.prompter.app.R
import com.prompter.app.data.SettingsRepository
import com.prompter.app.net.BridgeClient
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PultScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val client = BridgeClient
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    val connState by client.state.collectAsState()
    val session by client.session.collectAsState()
    val screen by client.screen.collectAsState()
    val runs by client.screenRuns.collectAsState()
    val lastError by client.lastError.collectAsState()
    val busy by client.busy.collectAsState()
    val scrollbackChunk by client.scrollback.collectAsState()

    // Follow-mode for the preview: pinned to the bottom while true; the user
    // scrolling up pauses the auto-scroll (pushes stop yanking), scrolling
    // back to the bottom resumes it. Simple position-derived rule.
    var autoScroll by remember { mutableStateOf(true) }

    // Scrollback history loaded above the live snapshot (negative indexes).
    var histText by remember { mutableStateOf<String?>(null) }
    var histFrom by remember { mutableIntStateOf(0) }
    var histReqFrom by remember { mutableIntStateOf(Int.MIN_VALUE) }

    // Preview settings sheet (long-press the preview).
    var sheetVisible by remember { mutableStateOf(false) }

    // rememberSaveable: survives the activity recreations that per-app
    // locale switches / config changes can trigger mid-typing.
    var text by rememberSaveable { mutableStateOf("") }
    var previewVisible by remember { mutableStateOf(true) }
    val previewScroll = rememberScrollState()
    val previewHScroll = rememberScrollState()

    // Preview display prefs: wrap-vs-pan and font size (persisted in DataStore)
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val wrap by repo.previewWrap.collectAsState(initial = true)
    val fontSp by repo.previewFont.collectAsState(initial = 10)
    val colorOn by repo.previewColor.collectAsState(initial = true)
    val colsPref by repo.previewCols.collectAsState(initial = 150)
    val trimOn by repo.previewTrim.collectAsState(initial = true)
    val wakeOn by repo.previewWake.collectAsState(initial = true)

    val copiedMsg = stringResource(R.string.copied_screen)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (!matches.isNullOrEmpty()) {
            text = text + if (text.isEmpty()) matches[0] else " " + matches[0]
        }
    }

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.hold_to_speak))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        }
        launcher.launch(intent)
    }

    /** Light tick on every key tap; distinct feel without being noisy. */
    fun tap(block: () -> Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        block()
    }

    /** Copies the mirrored pane — handy to paste an agent error elsewhere. */
    fun copyScreen() {
        if (screen.isEmpty()) return
        clipboard.setText(AnnotatedString(screen))
        Toast.makeText(context, copiedMsg, Toast.LENGTH_SHORT).show()
    }

    // Follow mode: purely derived from the scrollbar position. Near the bottom
    // (or content shorter than the viewport) = follow; scrolled up = paused.
    LaunchedEffect(previewScroll) {
        snapshotFlow { previewScroll.value to previewScroll.maxValue }
            .collect { (v, max) -> autoScroll = max - v <= 8 }
    }
    // Pin to the bottom on new pushes, but only while in follow mode.
    LaunchedEffect(screen, fontSp, wrap, autoScroll) {
        if (autoScroll) previewScroll.scrollTo(previewScroll.maxValue)
    }

    // Keep the screen on while the preview is visible (toggle in the sheet).
    val view = LocalView.current
    DisposableEffect(previewVisible, wakeOn) {
        val window = (view.context as? Activity)?.window
        if (previewVisible && wakeOn) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    /** Loads the previous chunk of tmux history above the live snapshot. */
    fun loadHistory() {
        var from = if (histText == null) -400 else histFrom - 400
        val to = if (histText == null) -1 else histFrom - 1
        if (from < -10000) from = -10000
        if (to <= from) {
            Toast.makeText(context, context.getString(R.string.history_end), Toast.LENGTH_SHORT).show()
            return
        }
        histReqFrom = from
        client.scrollback(from, to, colsPref)
    }

    // Prepend fetched scrollback chunks to the loaded history.
    LaunchedEffect(scrollbackChunk) {
        val chunk = scrollbackChunk ?: return@LaunchedEffect
        if (chunk.from == histReqFrom) {
            histText = if (histText == null) chunk.text else chunk.text + "\n" + histText
            histFrom = chunk.from
            histReqFrom = Int.MIN_VALUE
        }
    }

    // Width crop: hides the TUI sidebar so chat lines don't interleave with it
    // in wrap mode. Re-subscribe whenever it changes (also fires on connect).
    LaunchedEffect(colsPref, trimOn, connState) {
        if (connState is BridgeClient.ConnState.Connected) {
            client.subscribeScreen(colsPref, trimOn)
        }
    }

    // Colored preview: TUI canvas colors used when the snapshot has style runs.
    val runsLines = runs
    val colored = colorOn && runsLines != null && screen.isNotEmpty()
    val defFg = Color(0xFFE6E6E6)
    val defBg = Color(0xFF101016)
    val previewText = remember(screen, runsLines, colored) {
        if (colored && runsLines != null) buildAnnotatedString {
            runsLines.forEachIndexed { li, line ->
                if (li > 0) append("\n")
                line.forEach { run ->
                    withStyle(runSpanStyle(run, defFg, defBg)) { append(run.text) }
                }
            }
        } else {
            AnnotatedString(screen)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Connection status + copy/preview toggles (compact icons keep room for the status text)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (val st = connState) {
                    is BridgeClient.ConnState.Connected ->
                        if (session.isBlank()) stringResource(R.string.status_connected)
                        else stringResource(R.string.status_connected_session, session)
                    is BridgeClient.ConnState.Connecting -> stringResource(R.string.status_connecting)
                    is BridgeClient.ConnState.Error -> stringResource(R.string.status_error, st.msg)
                    else -> stringResource(R.string.status_disconnected)
                },
                style = MaterialTheme.typography.labelMedium,
                color = when (connState) {
                    is BridgeClient.ConnState.Connected -> MaterialTheme.colorScheme.primary
                    is BridgeClient.ConnState.Error -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            // Busy dot: the agent is generating right now (spinner in the TUI header)
            if (busy == true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.busy_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
            IconButton(
                onClick = { copyScreen() },
                enabled = screen.isNotEmpty(),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.copy_screen),
                    modifier = Modifier.size(18.dp)
                )
            }
            IconButton(
                onClick = { previewVisible = !previewVisible },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    if (previewVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (previewVisible) R.string.screen_hide else R.string.screen_show
                    ),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Error banner (bridge errors / dropped sends)
        lastError?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (err == BridgeClient.ERR_NOT_CONNECTED) {
                            stringResource(R.string.err_not_connected)
                        } else {
                            err
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    IconButton(onClick = { client.clearError() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.dismiss),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // Screen preview: mirrored tmux pane pushed by the bridge.
        // Long-press opens the preview settings sheet (copy lives in the
        // status row and in the sheet).
        if (previewVisible) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .combinedClickable(onClick = {}, onLongClick = { sheetVisible = true }),
                shape = RoundedCornerShape(12.dp),
                color = if (colored) defBg else MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    // Compact toolbar: font stepper + wrap/pan toggle.
                    // A 220-col pane does not fit a phone width — wrapping is the default.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "A−",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable {
                                    if (fontSp > 6) scope.launch { repo.setPreviewFont(fontSp - 1) }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text(
                            text = "A+",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable {
                                    if (fontSp < 24) scope.launch { repo.setPreviewFont(fontSp + 1) }
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Text(
                            text = fontSp.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // Load tmux history (scrollback) above the live snapshot.
                        Text(
                            text = if (histText == null) "↑ ${stringResource(R.string.history)}"
                                   else "↑+ ${stringResource(R.string.history)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { loadHistory() }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (wrap) stringResource(R.string.preview_wrap)
                                   else stringResource(R.string.preview_pan),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (wrap) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clickable { scope.launch { repo.setPreviewWrap(!wrap) } }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .verticalScroll(previewScroll)
                            .then(if (wrap) Modifier else Modifier.horizontalScroll(previewHScroll))
                            .padding(6.dp)
                    ) {
                        Column {
                            // Scrollback history fetched on demand (plain text,
                            // rendered above the live snapshot in one stream).
                            histText?.let { hist ->
                                Text(
                                    text = hist,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = fontSp.sp,
                                    lineHeight = (fontSp * 1.3f).sp,
                                    softWrap = wrap,
                                    color = if (colored) defFg else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = if (colored) previewText
                                       else AnnotatedString(screen.ifEmpty { stringResource(R.string.screen_empty) }),
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSp.sp,
                                lineHeight = (fontSp * 1.3f).sp,
                                softWrap = wrap,
                                color = when {
                                    colored -> defFg
                                    screen.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                        // Jump-to-bottom: shown whenever follow mode is paused.
                        if (!autoScroll) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shadowElevation = 4.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(10.dp)
                            ) {
                                IconButton(onClick = {
                                    scope.launch { previewScroll.scrollTo(previewScroll.maxValue) }
                                }) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown,
                                        contentDescription = stringResource(R.string.scroll_to_end),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Box(Modifier.weight(1f)) // keeps the bottom controls anchored down here
        }

        // Editable text (typed or recognized), with a local clear button
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 92.dp, max = 180.dp),
            shape = RoundedCornerShape(12.dp),
            placeholder = { Text(stringResource(R.string.text_hint)) },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_text_desc)
                        )
                    }
                }
            }
        )

        // Primary voice input + send: all in the bottom thumb zone
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // C-u clears the TUI input line (what "clear field" actually does remotely)
            FilledTonalButton(
                onClick = { tap { client.action("clear") } },
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) {
                Text("C-u", style = MaterialTheme.typography.titleMedium)
            }
            FloatingActionButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    startListening()
                },
                shape = CircleShape,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.hold_to_speak), modifier = Modifier.size(30.dp))
            }
            Button(
                onClick = {
                    if (client.typeText(text, submit = true)) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        text = ""
                    }
                },
                enabled = text.isNotBlank(),
                modifier = Modifier.weight(1.4f).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.send), maxLines = 1)
            }
        }

        // Frequent dialog responses for the agent flow.
        // "Enter" stays literal: it is a key name, not a word.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton("Enter", { tap { client.key("enter") } }, Modifier.weight(1f), height = 44.dp, filled = true)
            KeyButton(stringResource(R.string.yes), { tap { client.action("yes") } }, Modifier.weight(1f))
            KeyButton(stringResource(R.string.no), { tap { client.action("no") } }, Modifier.weight(1f))
            KeyButton(stringResource(R.string.esc), { tap { client.action("cancel") } }, Modifier.weight(1f))
        }

        // Secondary navigation / editing keys. Arrows auto-repeat when held.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            KeyButton("▲", { tap { client.action("up") } }, Modifier.weight(1f), repeat = true)
            KeyButton("▼", { tap { client.action("down") } }, Modifier.weight(1f), repeat = true)
            KeyButton("⌫", { tap { client.key("backspace") } }, Modifier.weight(0.9f))
            KeyButton("Tab", { tap { client.action("tab") } }, Modifier.weight(1.1f))
            KeyButton("⇧Tab", { tap { client.action("shifttab") } }, Modifier.weight(1.2f))
            KeyButton("C-w", { tap { client.action("wordback") } }, Modifier.weight(1f))
            KeyButton("C-c", { tap { client.action("interrupt") } }, Modifier.weight(1f))
        }
    }

    // Preview settings sheet: the rarely-used knobs moved out of the toolbar.
    if (sheetVisible) {
        ModalBottomSheet(onDismissRequest = { sheetVisible = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.preview_settings),
                    style = MaterialTheme.typography.titleMedium
                )
                // Width crop
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.preview_width),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "W−",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { if (colsPref > 0) scope.launch { repo.setPreviewCols(colsPref - 10) } }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                    Text(
                        text = if (colsPref > 0) colsPref.toString()
                               else stringResource(R.string.preview_width_auto),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "W+",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { if (colsPref < 220) scope.launch { repo.setPreviewCols(colsPref + 10) } }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                // Toggles: colors / compact / keep screen on
                @Composable
                fun SheetSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange(!checked) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = checked, onCheckedChange = onChange)
                    }
                }
                SheetSwitchRow(stringResource(R.string.preview_color), colorOn) {
                    scope.launch { repo.setPreviewColor(it) }
                }
                SheetSwitchRow(stringResource(R.string.preview_compact), trimOn) {
                    scope.launch { repo.setPreviewTrim(it) }
                }
                SheetSwitchRow(stringResource(R.string.keep_screen_on), wakeOn) {
                    scope.launch { repo.setPreviewWake(it) }
                }
                OutlinedButton(
                    onClick = {
                        copyScreen()
                        sheetVisible = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = screen.isNotEmpty()
                ) {
                    Text(stringResource(R.string.copy_screen))
                }
            }
        }
    }
}
