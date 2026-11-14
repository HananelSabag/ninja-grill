package com.example.mygrill.data

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps watching a cook with the app closed: the beacon scan, the cloud poll, the alerts and the
 * thermometer auto-stop all live in [GrillRepository], and this service only keeps it running
 * (plus the ongoing card). It starts with a cook and ends a couple of minutes after the cook does.
 */
class MonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: GrillRepository
    private lateinit var notifier: Notifier
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        repo = GrillRepository.get(this)
        notifier = Notifier(this).also { it.ensureChannels() }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        val started = runCatching { ServiceCompat.startForeground(this, Notifier.ID_LIVE, notifier.live(repo.state.value), type) }
        if (started.isFailure) {
            stopSelf()
            return
        }
        // Screen off for hours: the cloud poll's timers and the auto-stop must keep running.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NinjaGrill:cook")
            .apply { acquire(MAX_COOK_MS) }
        repo.hold(HOLDER)
        scope.launch {
            repo.state.map { notifier.liveKey(it) }.distinctUntilChanged().collect { notifier.updateLive(repo.state.value) }
        }
        scope.launch {
            repo.state.map { it.cookActive }.distinctUntilChanged().collectLatest { active ->
                if (!active) {
                    delay(LINGER_MS)
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        if (::repo.isInitialized) repo.release(HOLDER)
        wakeLock?.takeIf { it.isHeld }?.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val HOLDER = "monitor"
        private const val LINGER_MS = 2 * 60_000L
        private const val MAX_COOK_MS = 13 * 3_600_000L

        /** Only from the foreground (a tap, or the open app seeing a cook): Android refuses otherwise. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java)) }
        }
    }
}
