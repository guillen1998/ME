package dev.apkforge.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.apkforge.app.ApkForgeApp
import dev.apkforge.app.MainActivity
import dev.apkforge.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Qué se está haciendo ahora mismo (para la notificación). */
data class BusyInfo(val title: String, val detail: String, val percent: Int)

/**
 * Servicio en primer plano: mientras haya trabajo (decompilar, compilar, firmar, buscar, escanear,
 * agente IA o edición con IA) mantiene la app viva en segundo plano y muestra el progreso en la
 * notificación. Al terminar avisa con otra notificación que abre la app.
 */
class BackgroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels(this)
        val first = current() ?: BusyInfo("ApkForge", "Preparando…", -1)
        val n = progressNotification(first)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_PROGRESS, n)
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ApkForge:trabajo").apply { acquire(3 * 60 * 60 * 1000L) }
        }
        if (loop?.isActive != true) loop = scope.launch {
            var idleTicks = 0
            var lastTitle = first.title
            val nm = getSystemService(NotificationManager::class.java)
            while (isActive) {
                val b = current()
                if (b != null) {
                    idleTicks = 0; lastTitle = b.title
                    nm.notify(ID_PROGRESS, progressNotification(b))
                } else if (++idleTicks >= 3) {
                    nm.notify(ID_DONE, doneNotification(lastTitle))
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    break
                }
                delay(700)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        loop?.cancel()
        runCatching { wakeLock?.release() }
        super.onDestroy()
    }

    private fun openApp() = PendingIntent.getActivity(this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun progressNotification(b: BusyInfo): Notification =
        NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_forge)
            .setContentTitle(b.title)
            .setContentText(b.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(b.detail))
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setContentIntent(openApp())
            .apply { if (b.percent in 0..100) setProgress(100, b.percent, false).setSubText("${b.percent}%") else setProgress(0, 0, true) }
            .build()

    private fun doneNotification(title: String): Notification {
        val t = TaskRunner.state.value
        val text = when {
            t?.error != null -> "Error: " + t.error.take(120)
            t?.result != null -> "Listo: " + t.result.name
            else -> "Trabajo terminado"
        }
        return NotificationCompat.Builder(this, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat_forge)
            .setContentTitle(if (t?.error != null) "✖ $title" else "✔ $title")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
    }

    companion object {
        private const val CH_PROGRESS = "progreso"
        private const val CH_DONE = "terminados"
        private const val ID_PROGRESS = 41
        private const val ID_DONE = 42
        @Volatile private var running = false

        /** Junta el estado de todos los trabajos en curso. */
        fun current(): BusyInfo? {
            TaskRunner.state.value?.takeIf { it.running }?.let {
                return BusyInfo(it.title, it.logs.lastOrNull() ?: "Trabajando…", it.percent)
            }
            AiEditStore.jobs.value.values.firstOrNull { it.running }?.let { return BusyInfo("IA editando código", it.progress, -1) }
            AgentStore.busyInfo()?.let { return it }
            SearchStore.busyInfo()?.let { return it }
            ScanStore.busyInfo()?.let { return it }
            JavaSearchStore.busyInfo()?.let { return it }
            return null
        }

        fun ensureRunning() {
            val app = ApkForgeApp.instance
            if (running) return
            running = true
            runCatching { ContextCompat.startForegroundService(app, Intent(app, BackgroundService::class.java)) }
                .onFailure { running = false }
        }

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Progreso de trabajos", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CH_DONE, "Trabajos terminados", NotificationManager.IMPORTANCE_DEFAULT))
        }

        fun isIgnoringBattery(ctx: Context): Boolean =
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

        /** Pide quitar la optimización de batería para que no corte los trabajos largos. */
        fun requestIgnoreBattery(ctx: Context) {
            runCatching {
                ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    init { running = true }
}
