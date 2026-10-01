package com.karin.streamtv.karinlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Mantiene el servidor de KARIN Link con prioridad de primer plano.
 *
 * La lógica vive en [KarinLinkHost]; este servicio solo existe porque un socket
 * abierto desde una app en background puede ser matado por el sistema en
 * cualquier momento. En una TV eso significaba que el control remoto dejaba de
 * funcionar a mitad de un capítulo, sin que el usuario hiciera nada mal.
 *
 * No duplica el bind del socket ni el NSD: arrancarlo dos veces arrancaría dos
 * servidores compitiendo por el mismo puerto.
 */
class KarinLinkService : Service() {

    companion object {
        private const val TAG = "KarinLinkService"
        private const val CHANNEL_ID = "karin_link"
        private const val NOTIFICATION_ID = 4711

        /** Arranca el servicio. No hace nada si Karin Link está apagado. */
        fun start(context: Context) {
            if (!com.karin.streamtv.util.AppPreferences.isKarinLinkEnabled()) return
            val intent = Intent(context, KarinLinkService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }.onFailure { Log.w(TAG, "Could not start: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, KarinLinkService::class.java))
            }.onFailure { Log.w(TAG, "Could not stop: ${it.message}") }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Antes de tocar el socket: en Android 12+ startForeground tiene una
        // ventana corta y saltársela provoke un crash por ANR del servicio.
        startForeground(NOTIFICATION_ID, buildNotification())

        // El bind y el NSD bloquean, y no pueden ir en el hilo principal.
        Thread({
            KarinLinkHost.start(applicationContext)
        }, "karinlink-service").apply {
            isDaemon = true
            start()
        }

        // START_STICKY porque el objetivo es sobrevivir a que el sistema mate la
        // app; sin esto el servidor solo aguanta mientras el proceso respire.
        return START_STICKY
    }

    override fun onDestroy() {
        // El host puede seguir en pie por otra vía ( activities abiertas), así
        // que solo se detiene si nada más lo sostiene.
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "KARIN Link",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene el control remoto disponible en esta TV"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Abrir los ajustes al tocar la notificación: apagar el servicio desde
        // ahí es lo más cercano que tiene el usuario a un interruptor.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, KarinLinkConfigActivity::class.java),
            flags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("KARIN Link activo")
            .setContentText("Control remoto disponible en la red local")
            .setSmallIcon(com.karin.streamtv.R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }
}
