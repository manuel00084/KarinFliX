package com.karin.streamtv.player

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.common.util.UnstableApi

@OptIn(UnstableApi::class)
class CastMediaService : Service() {

    private val binder = LocalBinder()
    private var castPlayer: CastPlayer? = null
    private var mediaSession: MediaSessionCompat? = null

    inner class LocalBinder : Binder() {
        fun getService(): CastMediaService = this@CastMediaService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        setupMediaSession()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "KarinFLiXCast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    castPlayer?.play()
                }

                override fun onPause() {
                    castPlayer?.pause()
                }

                override fun onStop() {
                    castPlayer?.stop()
                }

                override fun onSeekTo(pos: Long) {
                    castPlayer?.seekTo(pos)
                }
            })
        }
    }

    fun setCastPlayer(player: CastPlayer?) {
        castPlayer = player
        updateMediaSessionState()
    }

    private fun updateMediaSessionState() {
        val player = castPlayer ?: return
        val state = when (player.playbackState) {
            androidx.media3.common.Player.STATE_IDLE -> PlaybackStateCompat.STATE_NONE
            androidx.media3.common.Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            androidx.media3.common.Player.STATE_READY -> {
                if (player.playWhenReady) PlaybackStateCompat.STATE_PLAYING
                else PlaybackStateCompat.STATE_PAUSED
            }
            androidx.media3.common.Player.STATE_ENDED -> PlaybackStateCompat.STATE_STOPPED
            else -> PlaybackStateCompat.STATE_NONE
        }

        val position = player.currentPosition.coerceAtLeast(0)
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, position, 1.0f)
            .build()

        mediaSession?.setPlaybackState(playbackState)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CastMediaService"
    }
}
