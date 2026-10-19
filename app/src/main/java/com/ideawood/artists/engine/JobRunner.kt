package com.ideawood.artists.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class JobState(
    val running: Boolean = false,
    val kind: String = "",
    val title: String = "",
    val status: String = "",
    val progress: Float = -1f,          // 0..100, or -1 when unknown
    val error: String? = null,
    val finished: Boolean = false,
    val seq: Int = 0,
)

/** Handed to a job so it can report progress and notice a Cancel tap. */
class JobCtl internal constructor() {
    fun status(s: String) = JobRunner.update { it.copy(status = s) }
    fun progress(p: Double) = JobRunner.update { it.copy(progress = p.toFloat().coerceIn(0f, 100f)) }
    fun cancelled(): Boolean = JobRunner.cancelRequested
}

/**
 * One long job at a time (sync / preview / render). Jobs run inside a foreground service, so the work keeps going
 * when the screen turns off or the artist switches apps, and the UI thread is never blocked.
 */
object JobRunner {
    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state.asStateFlow()

    @Volatile var cancelRequested = false
    private var pending: (suspend (JobCtl) -> Unit)? = null
    private var seq = 0

    internal fun update(f: (JobState) -> JobState) { _state.value = f(_state.value) }

    fun submit(ctx: Context, kind: String, title: String, block: suspend (JobCtl) -> Unit): Boolean {
        synchronized(this) {
            if (_state.value.running) return false
            cancelRequested = false
            pending = block
            seq += 1
            _state.value = JobState(running = true, kind = kind, title = title, status = "Starting...", seq = seq)
        }
        ContextCompat.startForegroundService(ctx, Intent(ctx, RenderService::class.java))
        return true
    }

    fun cancel() { cancelRequested = true; update { it.copy(status = "Cancelling...") } }

    fun clearResult() { if (!_state.value.running) _state.value = JobState(seq = seq) }

    internal suspend fun runPending() {
        val block = synchronized(this) { pending.also { pending = null } }
        if (block == null) { update { it.copy(running = false) }; return }
        try {
            block(JobCtl())
            update { it.copy(running = false, finished = true, progress = 100f, status = "Done") }
        } catch (e: CancellationException) {
            update { it.copy(running = false, finished = true, error = "Cancelled") }
        } catch (e: Throwable) {
            update { it.copy(running = false, finished = true, error = e.message ?: e.javaClass.simpleName) }
        }
    }
}

class RenderService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private fun channel(): String {
        val id = "render"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            nm.createNotificationChannel(NotificationChannel(id, "Lyric video jobs", NotificationManager.IMPORTANCE_LOW))
        }
        return id
    }

    private fun notification(s: JobState): Notification {
        val b = NotificationCompat.Builder(this, channel())
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(s.title.ifEmpty { "Ideawood Studio" })
            .setContentText(s.status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (s.progress >= 0) b.setProgress(100, s.progress.toInt(), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val first = notification(JobRunner.state.value)
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= 29 -> startForeground(1, first, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(1, first)
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ideawood:job").also {
            it.acquire(6 * 60 * 60 * 1000L)
        }
        scope.launch {
            val nm = getSystemService(NotificationManager::class.java)
            val watcher = launch {
                var lastText = ""
                JobRunner.state.collect { st ->
                    val key = "${st.status}|${st.progress.toInt()}"
                    if (st.running && key != lastText) { lastText = key; nm.notify(1, notification(st)) }
                }
            }
            JobRunner.runPending()
            watcher.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }
}
