package com.prompter.app.net

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Singleton WebSocket client for the Prompter bridge.
 *
 * Auth uses the "Authorization: Bearer <token>" header, so the token never
 * appears in the URL (and thus never lands in server logs). On connect the
 * client subscribes to screen mirroring; pushed snapshots are exposed via
 * [screen], bridge errors via [lastError].
 */
/**
 * One styled text run of the mirrored pane (parsed from the bridge "runs" field).
 * [fg]/[bg] are "#rrggbb" or a decimal xterm-256 index string, null = default;
 * [attrs] is a char set from "bdiur" (bold/dim/italic/underline/reverse).
 */
data class ScreenRun(
    val text: String,
    val fg: String?,
    val bg: String?,
    val attrs: String?,
)

/**
 * One TUI session tab parsed from the mirrored header line.
 * [active] marks the currently open tab, [unread] a tab with new output (•).
 */
data class SessionTab(
    val title: String,
    val active: Boolean,
    val unread: Boolean,
)

/** A chunk of tmux scrollback fetched on demand (negative line indexes). */
data class ScrollbackChunk(val from: Int, val to: Int, val text: String)

object BridgeClient {

    /** Sentinel stored in [lastError] when a send() was dropped (not connected). */
    const val ERR_NOT_CONNECTED = "not_connected"

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var host = ""
    private var port = ""
    private var token = ""
    private var shouldReconnect = true
    private var reconnectAttempts = 0
    private val handler = Handler(Looper.getMainLooper())

    sealed class ConnState {
        object Disconnected : ConnState()
        object Connecting : ConnState()
        object Connected : ConnState()
        data class Error(val msg: String) : ConnState()
    }

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    /** Latest pane snapshot pushed by the bridge (empty until connected). */
    private val _screen = MutableStateFlow("")
    val screen: StateFlow<String> = _screen

    /**
     * Colored runs for the latest snapshot: one list of runs per line.
     * null = the current snapshot carries no style info (plain fallback).
     */
    private val _screenRuns = MutableStateFlow<List<List<ScreenRun>>?>(null)
    val screenRuns: StateFlow<List<List<ScreenRun>>?> = _screenRuns

    /** tmux session name reported by the bridge in the "ready" message. */
    private val _session = MutableStateFlow("")
    val session: StateFlow<String> = _session

    /** Session tabs from the latest snapshot header (empty until known). */
    private val _tabs = MutableStateFlow<List<SessionTab>>(emptyList())
    val tabs: StateFlow<List<SessionTab>> = _tabs

    /** True while the agent is generating (spinner in the TUI header); null = unknown. */
    private val _busy = MutableStateFlow<Boolean?>(null)
    val busy: StateFlow<Boolean?> = _busy

    /** Last scrollback chunk fetched via [scrollback] (null until first fetch). */
    private val _scrollback = MutableStateFlow<ScrollbackChunk?>(null)
    val scrollback: StateFlow<ScrollbackChunk?> = _scrollback

    /** Last bridge/transport error; null when everything is fine. */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun clearError() {
        _lastError.value = null
    }

    fun connect(host: String, port: String, token: String) {
        this.host = host
        this.port = port
        this.token = token
        shouldReconnect = true
        reconnectAttempts = 0
        _lastError.value = null
        doConnect()
    }

    /** Parses the bridge "runs" payload; null on any malformation. */
    private fun parseRuns(arr: JSONArray): List<List<ScreenRun>>? {
        return try {
            val lines = ArrayList<List<ScreenRun>>(arr.length())
            for (i in 0 until arr.length()) {
                val lineArr = arr.optJSONArray(i) ?: continue
                val runs = ArrayList<ScreenRun>(lineArr.length())
                for (j in 0 until lineArr.length()) {
                    val r = lineArr.optJSONObject(j) ?: continue
                    runs.add(
                        ScreenRun(
                            text = r.optString("t", ""),
                            fg = if (r.has("f")) r.getString("f") else null,
                            bg = if (r.has("b")) r.getString("b") else null,
                            attrs = if (r.has("a")) r.getString("a") else null,
                        )
                    )
                }
                lines.add(runs)
            }
            lines
        } catch (_: Exception) {
            null
        }
    }

    /** Parses the bridge "tabs" payload; null on any malformation. */
    private fun parseTabs(arr: JSONArray): List<SessionTab>? {
        return try {
            val out = ArrayList<SessionTab>(arr.length())
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                out.add(
                    SessionTab(
                        title = t.optString("t", ""),
                        active = t.optInt("a", 0) == 1,
                        unread = t.optInt("u", 0) == 1,
                    )
                )
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun doConnect() {
        val cur = _state.value
        if (cur is ConnState.Connecting || cur is ConnState.Connected) return
        _state.value = ConnState.Connecting
        val req = Request.Builder()
            .url("ws://$host:$port/ws")
            .header("Authorization", "Bearer $token")
            .build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                _state.value = ConnState.Connected
                // Screen mirroring: subscribe right away; the bridge pushes on change.
                webSocket.send("""{"cmd":"subscribe_screen","interval_ms":700}""")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = JSONObject(text)
                    when (obj.optString("type")) {
                        "screen" -> {
                            _screen.value = obj.optString("text")
                            val arr = obj.optJSONArray("runs")
                            if (arr == null) {
                                _screenRuns.value = null // snapshot without style info
                            } else {
                                parseRuns(arr)?.let { _screenRuns.value = it } // keep old on parse fail
                            }
                            val tabsArr = obj.optJSONArray("tabs")
                            if (tabsArr != null) {
                                parseTabs(tabsArr)?.let { _tabs.value = it } // keep old on parse fail
                            }
                            if (obj.has("busy")) _busy.value = obj.optBoolean("busy")
                        }
                        "scrollback" -> {
                            _scrollback.value = ScrollbackChunk(
                                from = obj.optInt("from"),
                                to = obj.optInt("to"),
                                text = obj.optString("text"),
                            )
                        }
                        "ready" -> _session.value = obj.optString("session")
                        "error" -> _lastError.value = obj.optString("error", "bridge error")
                        "screen_error" -> _lastError.value = obj.optString("error", "capture failed")
                    }
                } catch (_: Exception) {
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnState.Error(t.message ?: "connection failure")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnState.Disconnected
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectAttempts++
        // Exponential backoff with jitter: ~1s, 2s, 4s, 8s, 16s, 30s cap.
        val base = minOf(30_000L, 1_000L shl minOf(reconnectAttempts, 5))
        val delay = base + (0..500L).random()
        handler.postDelayed({
            if (shouldReconnect &&
                _state.value !is ConnState.Connected &&
                _state.value !is ConnState.Connecting
            ) {
                doConnect()
            }
        }, delay)
    }

    fun disconnect() {
        shouldReconnect = false
        handler.removeCallbacksAndMessages(null)
        ws?.close(1000, "bye")
        ws = null
        _state.value = ConnState.Disconnected
    }

    /**
     * Sends a command to the bridge.
     * @return false when the command was NOT sent (not connected / transport failure).
     */
    fun send(cmd: String, payload: JSONObject = JSONObject()): Boolean {
        val socket = ws
        if (_state.value !is ConnState.Connected || socket == null) {
            _lastError.value = ERR_NOT_CONNECTED
            return false
        }
        return try {
            val obj = JSONObject()
            obj.put("cmd", cmd)
            val keys = payload.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                obj.put(k, payload.get(k))
            }
            socket.send(obj.toString())
        } catch (e: Exception) {
            _lastError.value = e.message ?: "send failed"
            false
        }
    }

    fun typeText(text: String, submit: Boolean = false): Boolean {
        val p = JSONObject()
        p.put("text", text)
        p.put("submit", submit)
        return send("type", p)
    }

    fun key(k: String): Boolean {
        val p = JSONObject()
        p.put("key", k)
        return send("key", p)
    }

    fun combo(mods: List<String>, key: String): Boolean {
        val p = JSONObject()
        p.put("mods", JSONArray(mods))
        p.put("key", key)
        return send("combo", p)
    }

    fun action(id: String): Boolean {
        val p = JSONObject()
        p.put("id", id)
        return send("action", p)
    }

    /**
     * (Re-)subscribe to screen pushes with a width crop: cols <= 0 sends the
     * full pane, cols > 0 makes the bridge crop each line to that many chars
     * (hides the TUI sidebar). trim=true drops the trailing input-box /
     * status-bar decoration block. Called on connect and whenever the user
     * changes the preview options.
     */
    fun subscribeScreen(cols: Int, trim: Boolean): Boolean {
        val p = JSONObject()
        p.put("interval_ms", 700)
        if (cols > 0) p.put("cols", cols)
        if (trim) p.put("trim", true)
        return send("subscribe_screen", p)
    }

    /**
     * Fetches tmux scrollback above the visible pane. Negative line indexes:
     * from < to <= -1, at most 1000 lines. Result lands in [scrollback].
     */
    fun scrollback(from: Int, to: Int, cols: Int): Boolean {
        val p = JSONObject()
        p.put("from", from)
        p.put("to", to)
        if (cols > 0) p.put("cols", cols)
        return send("scrollback", p)
    }

    // --- Session tabs ---------------------------------------------------------

    /** Alt+Down / Alt+Up: cycle to the next/previous TUI session tab. */
    fun cycleTab(forward: Boolean): Boolean =
        combo(listOf("alt"), if (forward) "Down" else "Up")

    /**
     * Jump to the tab at [index] (from the latest "tabs" snapshot) by cycling
     * Alt+Up/Alt+Down the required number of times. Deterministic and safe
     * while the TUI is busy: arrow chords are never eaten by the input field.
     */
    fun switchToTab(index: Int): Boolean {
        val cur = _tabs.value
        val from = cur.indexOfFirst { it.active }
        if (from < 0 || index == from || index !in cur.indices) return false
        val forward = index > from
        repeat(kotlin.math.abs(index - from)) {
            if (!cycleTab(forward)) return false
        }
        return true
    }

    /** Leader chord ctrl+x followed by a key (session shortcuts). */
    private fun leader(k: String): Boolean {
        if (!combo(listOf("ctrl"), "x")) return false
        return typeText(k)
    }

    /** ctrl+x n — create a new session tab. */
    fun newSession(): Boolean = leader("n")

    /** ctrl+x w — close the current session tab. */
    fun closeTab(): Boolean = leader("w")
}
