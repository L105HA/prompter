package com.prompter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.prompter.app.net.BridgeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service mirroring the connection state into an ongoing
 * notification, and posting a local "generation finished" alert when the
 * agent's spinner (busy flag from screen snapshots) goes true -> false.
 * This is what makes the app usable with the phone in the pocket: kick off
 * a task, get notified when the agent is done or waits for input.
 */
class BridgeService : Service() {

    companion object {
        const val CH_STATUS = "bridge_status"
        const val CH_EVENTS = "bridge_events"
        const val NOTIF_ID = 42
        const val EVENT_ID = 43
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        var wasBusy = false
        scope.launch {
            // State + busy flag arrive as separate flows; pair them.
            combine(BridgeClient.state, BridgeClient.busy) { s, b -> s to b }
                .collect { (st, busy) ->
                    updateNotification(st, busy == true)
                    if (st is BridgeClient.ConnState.Connected) {
                        if (wasBusy && busy == false) notifyGenerationDone()
                        wasBusy = busy == true
                    } else {
                        wasBusy = false
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(getString(R.string.notif_waiting), false),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CH_STATUS, getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_EVENTS, getString(R.string.notif_channel_events), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun statusText(st: BridgeClient.ConnState): String = when (st) {
        is BridgeClient.ConnState.Connected -> getString(R.string.notif_connected)
        is BridgeClient.ConnState.Connecting -> getString(R.string.notif_waiting)
        is BridgeClient.ConnState.Error -> getString(R.string.status_error, st.msg)
        else -> getString(R.string.notif_disconnected)
    }

    private fun buildNotification(text: String, busy: Boolean): Notification {
        val content = if (busy) "$text · ${getString(R.string.busy_label)}" else text
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(st: BridgeClient.ConnState, busy: Boolean) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(statusText(st), busy))
    }

    private fun notifyGenerationDone() {
        val nm = getSystemService(NotificationManager::class.java)
        val n = NotificationCompat.Builder(this, CH_EVENTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_gen_done))
            .setContentText(getString(R.string.notif_gen_done_text))
            .setAutoCancel(true)
            .build()
        nm.notify(EVENT_ID, n)
    }
}
