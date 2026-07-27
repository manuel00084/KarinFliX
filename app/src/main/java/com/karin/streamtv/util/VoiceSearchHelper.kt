package com.karin.streamtv.util

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.EditText
import androidx.core.app.ActivityCompat

object VoiceSearchHelper {

    const val REQUEST_VOICE_SEARCH = 1001
    private const val REQUEST_RECORD_AUDIO = 1002

    fun createIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre a buscar...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
    }

    fun isAvailable(activity: Activity): Boolean {
        val pm = activity.packageManager
        val intents = pm.queryIntentActivities(createIntent(), 0)
        return intents.isNotEmpty()
    }

    fun hasPermission(activity: Activity): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun requestPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    fun startVoiceSearch(activity: Activity) {
        if (!isAvailable(activity)) return
        if (!hasPermission(activity)) {
            requestPermission(activity)
            return
        }
        activity.startActivityForResult(createIntent(), REQUEST_VOICE_SEARCH)
    }

    fun handleResult(requestCode: Int, resultCode: Int, data: Intent?, target: EditText): Boolean {
        if (requestCode != REQUEST_VOICE_SEARCH) return false
        if (resultCode != Activity.RESULT_OK || data == null) return true
        val matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = matches?.firstOrNull()
        if (!text.isNullOrBlank()) {
            val fuzzyMatch = tryFuzzyMatch(text)
            val finalText = fuzzyMatch ?: text
            target.setText(finalText)
            target.setSelection(target.text.length)
        }
        return true
    }

    private fun tryFuzzyMatch(voiceText: String): String? {
        val recentAnime = EpisodeProgress.getRecentAnime()
        if (recentAnime.isEmpty()) return null

        val titles = recentAnime.map { it.replace(Regex("""[_\-]"""), " ").trim() }
        val result = FuzzySearch.findBestMatch(voiceText, titles, 0.45)
        return result?.first
    }
}
