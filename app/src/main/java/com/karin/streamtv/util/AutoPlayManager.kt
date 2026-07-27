package com.karin.streamtv.util

import android.os.CountDownTimer
import android.util.Log

object AutoPlayManager {

    private const val TAG = "AutoPlayManager"
    private const val COUNTDOWN_SECONDS = 5L
    private const val COUNTDOWN_INTERVAL = 1000L

    private var countdownTimer: CountDownTimer? = null

    interface AutoPlayCallback {
        fun onCountdownTick(secondsRemaining: Int)
        fun onCountdownFinish()
        fun onAutoPlayCancelled()
    }

    fun setAutoPlayEnabled(enabled: Boolean) {
        AppPreferences.setAutoPlayEnabled(enabled)
    }

    fun isAutoPlayEnabled(): Boolean {
        return AppPreferences.isAutoPlayEnabled()
    }

    fun startCountdown(callback: AutoPlayCallback) {
        cancelCountdown()

        if (!isAutoPlayEnabled()) {
            Log.d(TAG, "Auto-play disabled")
            return
        }

        Log.d(TAG, "Starting auto-play countdown: ${COUNTDOWN_SECONDS}s")

        countdownTimer = object : CountDownTimer(COUNTDOWN_SECONDS * 1000, COUNTDOWN_INTERVAL) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = ((millisUntilFinished + 500) / 1000).toInt()
                callback.onCountdownTick(secondsRemaining)
            }

            override fun onFinish() {
                Log.d(TAG, "Auto-play countdown finished")
                callback.onCountdownFinish()
            }
        }.start()
    }

    fun cancelCountdown() {
        countdownTimer?.cancel()
        countdownTimer = null
    }

    fun findNextEpisodeUrl(currentUrl: String, currentEpisodeNumber: Int): String? {
        val nextNumber = currentEpisodeNumber + 1

        val patterns = listOf(
            Regex("""(episodio-)\d+""") to "episodio-$nextNumber",
            Regex("""(-)\d+(/|$)""") to "-$nextNumber/",
            Regex("""(episode/)\d+""") to "episode/$nextNumber",
        )

        for ((pattern, replacement) in patterns) {
            val matcher = pattern.find(currentUrl)
            if (matcher != null) {
                val newUrl = currentUrl.replaceRange(matcher.range, replacement)
                if (newUrl != currentUrl) return newUrl
            }
        }

        return null
    }
}
