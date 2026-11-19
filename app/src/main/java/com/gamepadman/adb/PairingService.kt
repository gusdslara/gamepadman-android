package com.gamepadman.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gamepadman.R
import com.gamepadman.ui.ActivationActivity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lets the user type the Wireless debugging pairing code straight into a notification, so the
 * "Pair device with pairing code" dialog in Settings stays open. Pairs, then starts the helper.
 */
class PairingService : Service() {
    companion object {
        private const val ACTION_START = "com.gamepadman.PAIR_START"
        private const val ACTION_CODE = "com.gamepadman.PAIR_CODE"
        private const val ACTION_STOP = "com.gamepadman.PAIR_STOP"
        private const val KEY_CODE = "code"
        private const val CHANNEL = "gamepadman_pairing"
        private const val ID = 9

        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PairingService::class.java).setAction(ACTION_START))
            }
        }
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "pairing") }
    private val working = AtomicBoolean(false)
    @Volatile private var port = -1
    @Volatile private var discovering = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pairing", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Enter the Wireless debugging pairing code"
                setSound(null, null)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ok = try {
            ServiceCompat.startForeground(
                this, ID, waiting("Open Wireless debugging → “Pair device with pairing code”, then enter the code here."),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
            true
        } catch (_: Throwable) {
            false
        }
        if (!ok) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_START -> startDiscovery()
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString().orEmpty()
                onCode(code)
            }
            ACTION_STOP -> finish()
            else -> finish()
        }
        return START_NOT_STICKY
    }

    private fun startDiscovery() {
        if (discovering) return
        discovering = true
        worker.execute {
            // The pairing service only exists while the dialog is open; keep looking for ~10 minutes.
            val end = System.currentTimeMillis() + 10 * 60_000L
            while (discovering && port <= 0 && System.currentTimeMillis() < end) {
                val p = Activator.discoverPairingPort(this, 20_000)
                if (p > 0) {
                    port = p
                    notify(waiting("Pairing dialog found. Type the 6-digit code shown on screen."))
                }
            }
            if (port <= 0 && discovering) finish()
        }
    }

    private fun onCode(code: String) {
        if (!working.compareAndSet(false, true)) return
        notify(progress("Pairing…"))
        Thread {
            val r = Activator.pair(this, code, port)
            if (r.isSuccess) {
                discovering = false
                notify(progress("Paired. Starting the controller helper…"))
                val l = Activator.launchHelper(this)
                notify(done(if (l.isSuccess) "GamepadMan is activated. You can go back and start the overlay." else "Paired, but the helper did not start: ${l.exceptionOrNull()?.message}. Open GamepadMan and tap Start helper."))
                stopForegroundKeepNotification()
            } else {
                port = -1 // the dialog may have been reopened with a new port
                notify(waiting(r.exceptionOrNull()?.message ?: "Pairing failed. Try again."))
                working.set(false)
                if (!discovering) startDiscovery()
            }
        }.start()
    }

    private fun stopForegroundKeepNotification() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun finish() {
        discovering = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, ActivationActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun base(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_gamepad)
        .setContentTitle("GamepadMan activation")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(openApp())
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setCategory(NotificationCompat.CATEGORY_STATUS)

    private fun waiting(text: String): Notification {
        val reply = PendingIntent.getService(
            this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val stop = PendingIntent.getService(
            this, 2, Intent(this, PairingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return base(text)
            .setOngoing(true)
            .addAction(NotificationCompat.Action.Builder(R.drawable.ic_pencil, "Enter code", reply).addRemoteInput(input).build())
            .addAction(R.drawable.ic_x, "Cancel", stop)
            .build()
    }

    private fun progress(text: String) = base(text).setOngoing(true).setProgress(0, 0, true).build()

    private fun done(text: String) = base(text).setOngoing(false).setAutoCancel(true).build()

    private fun notify(n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(ID, n) }
    }

    override fun onDestroy() {
        discovering = false
        worker.shutdownNow()
        super.onDestroy()
    }
}
