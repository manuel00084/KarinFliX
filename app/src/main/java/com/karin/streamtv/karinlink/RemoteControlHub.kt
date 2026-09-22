package com.karin.streamtv.karinlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.widget.EditText
import com.karin.streamtv.player.ExoPlayerActivity
import com.karin.streamtv.util.AppActivityHolder
import org.json.JSONObject

/**
 * Ejecuta en este equipo los comandos del control remoto recibidos por
 * Karin Link (`remote.*`).
 *
 * - Teclas: se inyectan en la actividad en primer plano (solo dentro de la
 *   propia app, sin permisos especiales).
 * - Texto: se escribe en el `EditText` enfocado; si no hay ninguno, se usa el
 *   servicio de accesibilidad ([KarinRemoteService]).
 * - Mouse: gestos del servicio de accesibilidad + cursor overlay.
 * - Multimedia/volumen: reproductor si está abierto, si no `AudioManager`
 *   y acciones globales.
 */
object RemoteControlHub {

    private const val TAG = "RemoteControlHub"
    private val main = Handler(Looper.getMainLooper())

    fun handle(appContext: Context, type: String, data: JSONObject) {
        val ctx = appContext.applicationContext
        main.post {
            try {
                when (type) {
                    RemoteProtocol.KEY -> injectKey(data.optInt("keyCode", 0))
                    RemoteProtocol.TEXT -> commitText(
                        ctx,
                        data.optString("text", ""),
                        data.optBoolean("submit", false),
                    )
                    RemoteProtocol.MOUSE_MOVE -> {
                        val x = data.optDouble("x", 0.5).toFloat()
                        val y = data.optDouble("y", 0.5).toFloat()
                        RemoteCursorOverlay.move(ctx, x, y)
                    }
                    RemoteProtocol.MOUSE_TAP -> {
                        val x = data.optDouble("x", 0.5).toFloat()
                        val y = data.optDouble("y", 0.5).toFloat()
                        RemoteCursorOverlay.move(ctx, x, y)
                        if (!KarinRemoteService.tapAt(ctx, x, y)) {
                            // Sin accesibilidad: cae a ENTER sobre el foco actual.
                            injectKey(KeyEvent.KEYCODE_DPAD_CENTER)
                        }
                    }
                    RemoteProtocol.MOUSE_SCROLL -> {
                        val dx = data.optDouble("dx", 0.0).toFloat()
                        val dy = data.optDouble("dy", 0.0).toFloat()
                        val x = data.optDouble("x", 0.5).toFloat()
                        val y = data.optDouble("y", 0.5).toFloat()
                        if (!KarinRemoteService.scrollAt(ctx, x, y, dx, dy)) {
                            injectKey(if (dy < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN)
                        }
                    }
                    RemoteProtocol.MEDIA -> media(ctx, data.optString("cmd", ""), data.optLong("value", 0L))
                }
            } catch (e: Exception) {
                Log.w(TAG, "handle $type failed: ${e.message}")
            }
        }
    }

    // ── Teclado / D-pad ──────────────────────────────────────────

    private fun injectKey(keyCode: Int) {
        if (keyCode == 0) return
        val activity = AppActivityHolder.current() ?: run {
            // Sin actividad propia: al menos Atrás vía accesibilidad.
            if (keyCode == KeyEvent.KEYCODE_BACK) KarinRemoteService.globalBack()
            return
        }
        activity.runOnUiThread {
            try {
                val downTime = System.currentTimeMillis()
                activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0))
                activity.dispatchKeyEvent(KeyEvent(downTime, downTime, KeyEvent.ACTION_UP, keyCode, 0))
            } catch (e: Exception) {
                Log.w(TAG, "injectKey failed: ${e.message}")
            }
        }
    }

    // ── Texto ────────────────────────────────────────────────────

    private fun commitText(ctx: Context, text: String, submit: Boolean) {
        if (text.isEmpty() && !submit) return
        val activity = AppActivityHolder.current()
        val field = activity?.currentFocus as? EditText
        if (field != null) {
            activity.runOnUiThread {
                try {
                    if (text.isNotEmpty()) {
                        val pos = field.selectionStart.coerceAtLeast(0)
                        field.text?.insert(pos, text)
                    }
                    if (submit) injectKey(KeyEvent.KEYCODE_ENTER)
                } catch (e: Exception) {
                    Log.w(TAG, "commitText failed: ${e.message}")
                }
            }
            return
        }
        if (!KarinRemoteService.setTextOnFocused(text, append = true) && submit) {
            injectKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    // ── Multimedia ───────────────────────────────────────────────

    private fun media(ctx: Context, cmd: String, value: Long) {
        if (tryCustomMedia(cmd, value)) return
        val activity = AppActivityHolder.current()
        val playerActivity = activity as? ExoPlayerActivity
        when (cmd) {
            RemoteProtocol.CMD_TOGGLE -> {
                if (playerActivity?.remoteTogglePlay() != true) {
                    injectKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                }
            }
            RemoteProtocol.CMD_PLAY -> {
                if (playerActivity?.remoteSetPlaying(true) != true) {
                    injectKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }
            RemoteProtocol.CMD_PAUSE -> {
                if (playerActivity?.remoteSetPlaying(false) != true) {
                    injectKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
                }
            }
            RemoteProtocol.CMD_FF -> {
                if (playerActivity?.remoteSeekBy(if (value > 0) value else 10_000L) != true) {
                    injectKey(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
                }
            }
            RemoteProtocol.CMD_RW -> {
                if (playerActivity?.remoteSeekBy(-(if (value > 0) value else 10_000L)) != true) {
                    injectKey(KeyEvent.KEYCODE_MEDIA_REWIND)
                }
            }
            RemoteProtocol.CMD_VOL_UP -> adjustVolume(ctx, AudioManager.ADJUST_RAISE)
            RemoteProtocol.CMD_VOL_DOWN -> adjustVolume(ctx, AudioManager.ADJUST_LOWER)
            RemoteProtocol.CMD_MUTE -> adjustVolume(ctx, AudioManager.ADJUST_TOGGLE_MUTE)
            RemoteProtocol.CMD_BACK -> {
                if (activity != null) {
                    activity.runOnUiThread { activity.onBackPressed() }
                } else {
                    KarinRemoteService.globalBack()
                }
            }
            RemoteProtocol.CMD_HOME -> {
                try {
                    val home = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    ctx.startActivity(home)
                } catch (e: Exception) {
                    KarinRemoteService.globalHome()
                }
            }
        }
    }

    private fun adjustVolume(ctx: Context, direction: Int) {
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            Log.w(TAG, "adjustVolume failed: ${e.message}")
        }
    }

    /** Punto de anclaje para otras actividades que quieran comandos propios. */
    interface MediaHandler {
        /** Devuelve true si consumió el comando. */
        fun onRemoteMedia(cmd: String, value: Long): Boolean
    }

    private var mediaHandler: MediaHandler? = null

    fun setMediaHandler(handler: MediaHandler?) {
        mediaHandler = handler
    }

    internal fun tryCustomMedia(cmd: String, value: Long): Boolean {
        return try {
            mediaHandler?.onRemoteMedia(cmd, value) == true
        } catch (_: Exception) {
            false
        }
    }
}
