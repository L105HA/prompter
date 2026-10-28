package com.prompter.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.prompter.app.R
import com.prompter.app.data.Macro
import com.prompter.app.data.Macros
import com.prompter.app.data.SettingsRepository
import com.prompter.app.net.BridgeClient
import kotlinx.coroutines.launch

@Composable
fun CommandsScreen(modifier: Modifier = Modifier) {
    val client = BridgeClient
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    val connState by client.state.collectAsState()

    fun tap(block: () -> Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        block()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.section_menu), style = MaterialTheme.typography.titleSmall)
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("Ctrl+P", { tap { client.combo(listOf("ctrl"), "p") } }, Modifier.weight(1f), height = 46.dp)
                KeyButton("/", { tap { client.key("/") } }, Modifier.weight(1f), height = 46.dp)
                KeyButton("Ctrl+O", { tap { client.combo(listOf("ctrl"), "o") } }, Modifier.weight(1f), height = 46.dp)
            }
        }

        Text(stringResource(R.string.section_navigation), style = MaterialTheme.typography.titleSmall)
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // D-pad: a cross reads as "move" much faster than a flat row of arrows.
                // All four directions auto-repeat when held.
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Spacer(Modifier.weight(1f))
                        KeyButton("▲", { tap { client.action("up") } }, Modifier.weight(1f), height = 48.dp, repeat = true)
                        Spacer(Modifier.weight(1f))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyButton("◀", { tap { client.action("left") } }, Modifier.weight(1f), height = 48.dp, repeat = true)
                        KeyButton("▼", { tap { client.action("down") } }, Modifier.weight(1f), height = 48.dp, repeat = true)
                        KeyButton("▶", { tap { client.action("right") } }, Modifier.weight(1f), height = 48.dp, repeat = true)
                    }
                }
                // The keys you press right after moving the selection
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    KeyButton("Enter", { tap { client.key("enter") } }, Modifier.fillMaxWidth().height(52.dp), filled = true)
                    KeyButton(stringResource(R.string.esc), { tap { client.action("cancel") } }, Modifier.fillMaxWidth().height(48.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        KeyButton("Tab", { tap { client.action("tab") } }, Modifier.weight(1f), height = 48.dp)
                        KeyButton("⇧Tab", { tap { client.action("shifttab") } }, Modifier.weight(1f), height = 48.dp)
                    }
                }
            }
        }

        // Scrolling the TUI transcript from the phone (yes/no/C-w/C-c live on the Pult)
        Text(stringResource(R.string.section_scroll), style = MaterialTheme.typography.titleSmall)
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton("PgUp", { tap { client.key("PageUp") } }, Modifier.weight(1f), height = 46.dp, repeat = true)
                KeyButton("PgDn", { tap { client.key("PageDown") } }, Modifier.weight(1f), height = 46.dp, repeat = true)
                KeyButton("Home", { tap { client.key("Home") } }, Modifier.weight(1f), height = 46.dp)
                KeyButton("End", { tap { client.key("End") } }, Modifier.weight(1f), height = 46.dp)
            }
        }

        Text(stringResource(R.string.section_quick), style = MaterialTheme.typography.titleSmall)
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton(stringResource(R.string.choose1), { tap { client.action("up"); client.action("up"); client.action("up"); client.action("enter") } }, Modifier.weight(1f), height = 46.dp)
                KeyButton(stringResource(R.string.choose2), { tap { client.action("down"); client.action("enter") } }, Modifier.weight(1f), height = 46.dp)
                KeyButton(stringResource(R.string.choose3), { tap { client.action("down"); client.action("down"); client.action("enter") } }, Modifier.weight(1f), height = 46.dp)
            }
        }

        // User macros: named button sequences stored in DataStore.
        val context = LocalContext.current
        val repo = remember { SettingsRepository(context) }
        val scope = rememberCoroutineScope()
        val macros by repo.macros.collectAsState(initial = emptyList())
        var editorOpen by remember { mutableStateOf(false) }
        var editLabel by remember { mutableStateOf("") }
        var editSteps by remember { mutableStateOf("") }

        fun runMacro(m: Macro) {
            m.steps.forEach { s ->
                when (s.kind) {
                    "text" -> client.typeText(s.a1, s.a2 == "1")
                    "key" -> client.key(s.a1)
                    "combo" -> client.combo(s.a1.split(Regex("[ ,]+")).filter { it.isNotBlank() }, s.a2)
                    "action" -> client.action(s.a1)
                }
            }
        }

        Text(stringResource(R.string.section_macros), style = MaterialTheme.typography.titleSmall)
        if (macros.isEmpty()) {
            Text(
                stringResource(R.string.macros_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    macros.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { tap { runMacro(m) } }
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                m.label,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                scope.launch { repo.setMacros(macros.filter { it !== m }) }
                            }) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.macros_delete),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
        KeyButton(
            stringResource(R.string.macros_add),
            { tap { editLabel = ""; editSteps = ""; editorOpen = true } },
            Modifier.fillMaxWidth(),
            height = 46.dp
        )

        if (editorOpen) {
            AlertDialog(
                onDismissRequest = { editorOpen = false },
                title = { Text(stringResource(R.string.macros_add)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = editLabel,
                            onValueChange = { editLabel = it },
                            label = { Text(stringResource(R.string.macros_label)) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = editSteps,
                            onValueChange = { editSteps = it },
                            label = { Text(stringResource(R.string.macros_steps)) },
                            supportingText = {
                                Text(
                                    stringResource(R.string.macros_hint),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            minLines = 3
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val m = Macros.parse(editSteps, editLabel)
                        if (m.steps.isNotEmpty()) {
                            val updated = macros + m
                            scope.launch { repo.setMacros(updated) }
                        }
                        editorOpen = false
                    }) { Text(stringResource(R.string.save)) }
                },
                dismissButton = {
                    TextButton(onClick = { editorOpen = false }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }

        if (connState is BridgeClient.ConnState.Error) {
            // send() failures are surfaced by the banner on the Pult tab; a small
            // reminder here helps when commands appear dead.
            Text(
                stringResource(R.string.err_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Box(Modifier.height(4.dp))
    }
}
