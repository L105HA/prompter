package com.prompter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.prompter.app.data.SettingsRepository
import com.prompter.app.net.BridgeClient
import com.prompter.app.ui.CommandsScreen
import com.prompter.app.ui.PultScreen
import com.prompter.app.ui.SessionsScreen
import com.prompter.app.ui.SettingsScreen
import com.prompter.app.ui.theme.PrompterTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * AppCompatActivity (not ComponentActivity) is required for
 * AppCompatDelegate per-app locales used by the language setting.
 */
class MainActivity : AppCompatActivity() {
    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private lateinit var repo: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SettingsRepository(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        // Android 13+: the "generation finished" notification needs POST_NOTIFICATIONS.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        lifecycleScope.launch {
            applySavedLocale()
            // A QR deep link saves settings and connects by itself;
            // otherwise connect automatically when a token is already stored.
            if (!handleDeepLink(intent?.data)) {
                autoConnect()
            }
        }
        setContent {
            val theme by repo.theme.collectAsState(initial = "system")
            val dark = when (theme) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }
            PrompterTheme(darkTheme = dark) {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // QR deep link arriving while the app is already running
        lifecycleScope.launch { handleDeepLink(intent.data) }
    }

    /** Applies the language saved in Settings; guarded to avoid a recreate loop. */
    private suspend fun applySavedLocale() {
        val lang = repo.lang.first()
        val desired = LocaleListCompat.forLanguageTags(if (lang == "system") "" else lang)
        if (AppCompatDelegate.getApplicationLocales() != desired) {
            AppCompatDelegate.setApplicationLocales(desired)
        }
    }

    /**
     * prompter://connect?host=<ip>&port=<port>&token=<token> — printed as a QR code by the prompter CLI
     * by run-all.sh. Saves the connection and connects immediately.
     * @return true when the link was valid and consumed.
     */
    private suspend fun handleDeepLink(uri: Uri?): Boolean {
        if (uri?.scheme != "prompter" || uri.host != "connect") return false
        val host = uri.getQueryParameter("host") ?: return false
        val port = uri.getQueryParameter("port") ?: "8788"
        val token = uri.getQueryParameter("token") ?: return false
        repo.setHost(host)
        repo.setPort(port)
        repo.setToken(token)
        BridgeClient.connect(host, port, token)
        return true
    }

    private suspend fun autoConnect() {
        if (BridgeClient.state.value is BridgeClient.ConnState.Connected) return
        val host = repo.host.first()
        val port = repo.port.first()
        val token = repo.token.first()
        if (token.isNotBlank() && host.isNotBlank()) {
            BridgeClient.connect(host, port, token)
        }
    }
}

data class NavItem(val titleRes: Int, val icon: ImageVector)

@Composable
fun AppNav() {
    var selected by remember { mutableIntStateOf(0) }
    // With targetSdk 35 the window is edge-to-edge: manifest adjustResize no
    // longer resizes it, so the keyboard must be handled via IME insets.
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val context = LocalContext.current
    val connState by BridgeClient.state.collectAsState()

    // While connected, a foreground service mirrors the state into an ongoing
    // notification and alerts when the agent finishes generating.
    LaunchedEffect(connState) {
        if (connState is BridgeClient.ConnState.Connected) {
            ContextCompat.startForegroundService(
                context, Intent(context, BridgeService::class.java)
            )
        } else {
            context.stopService(Intent(context, BridgeService::class.java))
        }
    }
    val items = listOf(
        NavItem(R.string.tab_pult, Icons.Default.Home),
        NavItem(R.string.tab_commands, Icons.Default.Build),
        NavItem(R.string.tab_sessions, Icons.AutoMirrored.Filled.List),
        NavItem(R.string.tab_settings, Icons.Default.Settings)
    )
    Scaffold(
        // The bottom nav under an open keyboard is dead weight — hide it
        // and let the screen content ride above the IME instead.
        bottomBar = {
            if (!imeVisible) {
                NavigationBar {
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(item.icon, contentDescription = stringResource(item.titleRes)) },
                            label = { Text(stringResource(item.titleRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        // imePadding lifts the input field above the keyboard (no-op when closed).
        val content = Modifier.padding(padding).imePadding()
        when (selected) {
            0 -> PultScreen(content)
            1 -> CommandsScreen(content)
            2 -> SessionsScreen(content)
            3 -> SettingsScreen(content)
            else -> PultScreen(content)
        }
    }
}
