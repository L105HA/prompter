package com.prompter.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.prompter.app.R
import com.prompter.app.net.BridgeClient

/**
 * Live session switcher. The bridge parses the opencode TUI header (its tab
 * bar) into a "tabs" list shipped with every screen snapshot; tapping a row
 * cycles Alt+Up/Alt+Down the exact number of times — deterministic, no
 * dialogs, and safe while the agent is generating (arrow chords are never
 * eaten by the TUI input field, unlike ctrl+tab or the ctrl+x l modal).
 */
@Composable
fun SessionsScreen(modifier: Modifier = Modifier) {
    val client = BridgeClient
    val tabs by client.tabs.collectAsState()
    val connState by client.state.collectAsState()

    // Closing a tab can kill a running generation — confirm first.
    var confirmClose by remember { mutableStateOf(false) }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text(stringResource(R.string.tab_close_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.tab_close_text,
                        tabs.firstOrNull { it.active }?.title ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    client.closeTab()
                    confirmClose = false
                }) { Text(stringResource(R.string.tab_close)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClose = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.section_tabs), style = MaterialTheme.typography.titleSmall)

        // Cycle + manage: ◀ ▶ step by one, + creates, ✕ closes the active tab.
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp) {
            Row(modifier = Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KeyButton(stringResource(R.string.tab_prev), { client.cycleTab(false) }, Modifier.weight(1f), height = 46.dp)
                KeyButton(stringResource(R.string.tab_next), { client.cycleTab(true) }, Modifier.weight(1f), height = 46.dp)
                KeyButton(stringResource(R.string.tab_new), { client.newSession() }, Modifier.weight(1.3f), height = 46.dp)
                KeyButton(stringResource(R.string.tab_close), { confirmClose = true }, Modifier.weight(1f), height = 46.dp)
            }
        }

        if (tabs.isEmpty()) {
            Text(
                stringResource(R.string.tabs_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            tabs.forEachIndexed { index, tab ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { client.switchToTab(index) },
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = if (tab.active) 3.dp else 1.dp,
                    color = if (tab.active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface,
                    border = if (tab.active) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (tab.unread) {
                            // • unread marker from the TUI header
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        Text(
                            tab.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (tab.active) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (tab.active) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        if (connState is BridgeClient.ConnState.Error) {
            Text(
                stringResource(R.string.err_not_connected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Box(Modifier.height(4.dp))
    }
}
