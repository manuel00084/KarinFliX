package com.karin.streamtv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener

@OptIn(UnstableApi::class)
class CastHelper(private val context: Context) {

    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    private var sessionManagerListener: SessionManagerListener<CastSession>? = null

    var isCasting: Boolean = false
        private set

    var onCastSessionChanged: ((isCasting: Boolean) -> Unit)? = null

    init {
        try {
            castContext = CastContext.getSharedInstance(context)
            setupSessionListener()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing CastContext: ${e.message}")
        }
    }

    private fun setupSessionListener() {
        sessionManagerListener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarting(session: CastSession) {}

            override fun onSessionStarted(session: CastSession, sessionId: String) {
                Log.i(TAG, "Cast session started")
                isCasting = true
                onCastSessionChanged?.invoke(true)
            }

            override fun onSessionStartFailed(session: CastSession, error: Int) {
                Log.w(TAG, "Cast session start failed: $error")
                isCasting = false
                onCastSessionChanged?.invoke(false)
            }

            override fun onSessionEnding(session: CastSession) {}

            override fun onSessionEnded(session: CastSession, error: Int) {
                Log.i(TAG, "Cast session ended")
                isCasting = false
                onCastSessionChanged?.invoke(false)
            }

            override fun onSessionResuming(session: CastSession, sessionId: String) {}

            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
                Log.i(TAG, "Cast session resumed")
                isCasting = true
                onCastSessionChanged?.invoke(true)
            }

            override fun onSessionResumeFailed(session: CastSession, error: Int) {
                isCasting = false
                onCastSessionChanged?.invoke(false)
            }

            override fun onSessionSuspended(session: CastSession, reason: Int) {
                isCasting = false
                onCastSessionChanged?.invoke(false)
            }
        }
        castContext?.sessionManager?.addSessionManagerListener(
            sessionManagerListener!!, CastSession::class.java
        )
    }

    fun getCastPlayer(): CastPlayer? {
        if (castPlayer == null) {
            castContext?.let {
                castPlayer = CastPlayer(it)
            }
        }
        return castPlayer
    }

    fun isDeviceConnected(): Boolean {
        return castContext?.sessionManager?.currentCastSession?.isConnected == true
    }

    fun castVideo(url: String, title: String, subtitle: String = "") {
        val player = getCastPlayer() ?: return
        if (!isDeviceConnected()) {
            Log.w(TAG, "No Cast device connected")
            return
        }

        val mediaItem = MediaItem.Builder()
            .setMediaId(url)
            .setUri(Uri.parse(url))
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(subtitle)
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()

        Log.i(TAG, "Casting: $title")
    }

    fun disconnect() {
        castPlayer?.release()
        castPlayer = null
        isCasting = false
    }

    fun release() {
        sessionManagerListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(
                listener, CastSession::class.java
            )
        }
        disconnect()
    }

    companion object {
        private const val TAG = "CastHelper"
    }
}
