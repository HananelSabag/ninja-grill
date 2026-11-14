package com.example.mygrill.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.mygrill.MainActivity
import com.example.mygrill.R
import com.example.mygrill.core.CookEvent
import com.example.mygrill.core.CookMode
import com.example.mygrill.core.CookPhase
import com.example.mygrill.core.CookSpecs
import com.example.mygrill.core.GrillView
import com.example.mygrill.ui.label
import com.example.mygrill.ui.temp

/**
 * Two kinds of notification: alerts for the moments of a cook (heads-up, sound) and one quiet
 * ongoing card while the app watches a cook in the background (the foreground service's own).
 */
class Notifier(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_ALERTS, context.getString(R.string.notif_channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.notif_channel_alerts_hint)
                    enableVibration(true)
                },
                NotificationChannel(CH_LIVE, context.getString(R.string.notif_channel_live), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.notif_channel_live_hint)
                    setShowBadge(false)
                },
            ),
        )
    }

    fun canPost(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun alert(event: CookEvent, state: UiState) {
        if (!canPost()) return
        ensureChannels()
        val (title, text) = words(event, state)
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        nm.notify(ID_ALERT + event.ordinal, n)
    }

    private fun words(event: CookEvent, s: UiState): Pair<String, String> {
        val v = s.view
        val f = s.unitsF
        val probe = (s.autoStop ?: s.lastAutoStop)?.let { probeName(s, it.probeKey) }
        val probeNow = s.autoStop?.let { probeTemp(s, it.probeKey) }
        return when (event) {
            CookEvent.STARTED -> str(R.string.notif_started) to (v?.let { heatLine(it, f) } ?: "")
            CookEvent.PREHEAT_DONE -> str(R.string.notif_hot) to str(R.string.notif_hot_text, temp(v?.grillC, f))
            CookEvent.FLIP -> str(R.string.prompt_flip_food) to str(R.string.notif_flip_text)
            CookEvent.DONE -> str(R.string.prompt_done) to str(R.string.prompt_get_food)
            CookEvent.TURNED_OFF -> str(R.string.notif_off) to str(R.string.notif_off_text)
            CookEvent.PULL_SOON -> str(R.string.notif_pull_soon) to
                str(R.string.notif_pull_soon_text, probe.orEmpty(), temp(probeNow, f), temp(s.autoStop?.pullC?.toDouble(), f))
            CookEvent.PULL_NOW -> str(R.string.notif_pull_now) to
                str(R.string.notif_pull_now_text, probe.orEmpty(), temp(s.lastAutoStop?.targetC?.toDouble(), f))
            CookEvent.STOP_FAILED -> str(R.string.notif_stop_failed) to str(R.string.notif_stop_failed_text, probe.orEmpty())
            CookEvent.LID_OPEN -> str(R.string.lid_open_title) to str(R.string.lid_open_text)
        }
    }

    // ------------------------------------------------------------------ ongoing card

    /** What the ongoing card shows; it is re-posted only when this changes. */
    fun liveKey(s: UiState): String {
        val v = s.view
        val probes = (0..1).map { probeTemp(s, "ninja$it")?.toInt() } + (0..1).map { probeTemp(s, "ext$it")?.toInt() }
        return listOf(v?.phase, v?.prompt, v?.mode, v?.setpoint, v?.grillC?.toInt(), v?.secondsLeft?.div(60), probes, s.unitsF).joinToString("|")
    }

    fun live(s: UiState): Notification {
        val v = s.view
        val f = s.unitsF
        val title = when {
            v == null -> str(R.string.phase_searching)
            v.prompt != null -> str(v.prompt.label())
            else -> listOfNotNull(str(v.phase.label()), heatLine(v, f).takeIf { it.isNotEmpty() }).joinToString(" · ")
        }
        val parts = buildList {
            v?.grillC?.let { add(str(R.string.notif_live_grill, temp(it, f))) }
            for (key in listOf("ninja0", "ninja1", "ext0", "ext1")) {
                val t = probeTemp(s, key) ?: continue
                val target = s.autoStop?.takeIf { it.probeKey == key }?.pullC
                add(probeName(s, key) + " " + temp(t, f) + (target?.let { " → " + temp(it.toDouble(), f) } ?: ""))
            }
        }
        val b = NotificationCompat.Builder(context, CH_LIVE)
            .setSmallIcon(R.drawable.ic_flame)
            .setContentTitle(title)
            .setContentText(parts.joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
        if (v != null && v.phase == CookPhase.PREHEAT) {
            val target = v.setpoint?.takeIf { it > 3 }
            val now = v.grillC?.toInt()
            if (target != null && now != null) b.setProgress(target, now.coerceAtMost(target), false)
        }
        val left = v?.secondsLeft?.takeIf { it > 0 && v.phase == CookPhase.COOKING }
        if (left != null) {
            b.setWhen(System.currentTimeMillis() + left * 1000L).setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
        } else {
            b.setShowWhen(false)
        }
        return b.build()
    }

    fun updateLive(s: UiState) {
        if (canPost()) nm.notify(ID_LIVE, live(s))
    }

    // ------------------------------------------------------------------ words

    private fun heatLine(v: GrillView, unitsF: Boolean): String {
        val mode = v.mode ?: return ""
        val heat = when {
            mode == CookMode.GRILL -> CookSpecs.grillLevel(v.setpoint)?.let { str(listOf(R.string.heat_lo, R.string.heat_med, R.string.heat_hi)[it - 1]) }
            v.setpoint != null -> temp(v.setpoint.toDouble(), unitsF)
            else -> null
        }
        return listOfNotNull(str(mode.label()), heat).joinToString(" ")
    }

    private fun probeName(s: UiState, key: String): String {
        val i = key.takeLast(1).toInt()
        return if (key.startsWith("ext")) str(if ((i == 0) == s.extWhiteFirst) R.string.probe_white else R.string.probe_black)
        else str(R.string.probe_n, i + 1)
    }

    private fun probeTemp(s: UiState, key: String): Double? {
        val i = key.takeLast(1).toInt()
        return if (key.startsWith("ext")) s.external?.takeIf { it.connected }?.reading?.probesC?.getOrNull(i)
        else s.view?.probes?.getOrNull(i)?.tempC
    }

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CH_ALERTS = "cook_alerts"
        const val CH_LIVE = "cook_live"
        const val ID_LIVE = 1
        private const val ID_ALERT = 100
    }
}
