package com.prompter.app.ui

import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import com.prompter.app.R
import com.prompter.app.data.SettingsRepository
import com.prompter.app.net.BridgeClient
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val client = BridgeClient

    val host by repo.host.collectAsState(initial = "")
    val port by repo.port.collectAsState(initial = "8788")
    val token by repo.token.collectAsState(initial = "")
    val lang by repo.lang.collectAsState(initial = "system")
    val theme by repo.theme.collectAsState(initial = "system")

    var h by remember(host) { mutableStateOf(host) }
    var p by remember(port) { mutableStateOf(port) }
    var t by remember(token) { mutableStateOf(token) }
    var l by remember(lang) { mutableStateOf(lang) }
    var th by remember(theme) { mutableStateOf(theme) }

    var scanning by remember { mutableStateOf(false) }

    val connState = client.state.collectAsState().value
    val lastError by client.lastError.collectAsState()

    fun persistAll() {
        scope.launch {
            repo.setHost(h)
            repo.setPort(p)
            repo.setToken(t)
            repo.setLang(l)
            repo.setTheme(th)
        }
    }

    /** Applies the chosen language immediately (recreates the activity). */
    fun applyLocale(langCode: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(if (langCode == "system") "" else langCode)
        )
    }

    /** prompter://connect?host=..&port=..&token=.. from prompter CLI QR / system camera. */
    fun consumeLink(value: String): Boolean {
        val uri = Uri.parse(value)
        if (uri.scheme != "prompter" || uri.host != "connect") return false
        val qHost = uri.getQueryParameter("host") ?: return false
        val qPort = uri.getQueryParameter("port") ?: "8788"
        val qToken = uri.getQueryParameter("token") ?: return false
        h = qHost
        p = qPort
        t = qToken
        scope.launch {
            repo.setHost(qHost)
            repo.setPort(qPort)
            repo.setToken(qToken)
        }
        client.connect(qHost, qPort, qToken)
        return true
    }

    if (scanning) {
        QrScanOverlay(
            onQr = { value ->
                if (consumeLink(value)) {
                    scanning = false
                    true
                } else {
                    false
                }
            },
            onDismiss = { scanning = false }
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(stringResource(R.string.quick_connect), style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { scanning = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(Icons.Default.QrCode, contentDescription = null)
            Text("  " + stringResource(R.string.scan_qr))
        }
        Text(stringResource(R.string.qr_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text(stringResource(R.string.manual_setup), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(value = h, onValueChange = { h = it }, label = { Text(stringResource(R.string.host)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = p, onValueChange = { p = it }, label = { Text(stringResource(R.string.port)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        // Token is a secret: masked by default, eye toggle to peek
        var tokenVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = t,
            onValueChange = { t = it },
            label = { Text(stringResource(R.string.token)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { tokenVisible = !tokenVisible }) {
                    Icon(
                        if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (tokenVisible) R.string.hide_token else R.string.show_token
                        )
                    )
                }
            }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { persistAll() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.save))
            }
            OutlinedButton(
                onClick = {
                    if (connState is BridgeClient.ConnState.Connected ||
                        connState is BridgeClient.ConnState.Connecting
                    ) {
                        client.disconnect()
                    } else {
                        persistAll()
                        if (t.isNotEmpty()) {
                            client.connect(h, p, t)
                        }
                    }
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(when (connState) {
                    is BridgeClient.ConnState.Connected -> stringResource(R.string.disconnect)
                    is BridgeClient.ConnState.Connecting -> stringResource(R.string.cancel)
                    else -> stringResource(R.string.connect)
                })
            }
        }

        Text(stringResource(R.string.status) + ": " + when (connState) {
            is BridgeClient.ConnState.Connected -> stringResource(R.string.status_connected)
            is BridgeClient.ConnState.Connecting -> stringResource(R.string.status_connecting)
            is BridgeClient.ConnState.Error -> stringResource(R.string.status_error, (connState as BridgeClient.ConnState.Error).msg)
            else -> stringResource(R.string.status_disconnected)
        })
        lastError?.let { err ->
            Text(
                text = if (err == BridgeClient.ERR_NOT_CONNECTED) stringResource(R.string.err_not_connected) else err,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleMedium)
        @Composable
        fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(label)
            }
        }
        // Theme/language apply AND persist immediately — no Save needed
        RadioRow(stringResource(R.string.theme_system), th == "system") { th = "system"; scope.launch { repo.setTheme("system") } }
        RadioRow(stringResource(R.string.theme_light), th == "light") { th = "light"; scope.launch { repo.setTheme("light") } }
        RadioRow(stringResource(R.string.theme_dark), th == "dark") { th = "dark"; scope.launch { repo.setTheme("dark") } }

        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
        RadioRow(stringResource(R.string.lang_system), l == "system") { l = "system"; applyLocale("system"); scope.launch { repo.setLang("system") } }
        RadioRow(stringResource(R.string.lang_ru), l == "ru") { l = "ru"; applyLocale("ru"); scope.launch { repo.setLang("ru") } }
        RadioRow(stringResource(R.string.lang_en), l == "en") { l = "en"; applyLocale("en"); scope.launch { repo.setLang("en") } }

        Text("Bridge: ws://$h:$p/ws", style = MaterialTheme.typography.bodySmall)
    }
}
