package com.karin.streamtv.ui

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.karin.streamtv.R
import com.karin.streamtv.util.AppPreferences
import com.karin.streamtv.util.AutoPlayManager
import com.karin.streamtv.util.CrashLogger
import com.karin.streamtv.util.DiskImageCache
import com.karin.streamtv.util.Http
import com.karin.streamtv.util.WatchHistory

class SplashActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            CrashLogger.init(this)
            CrashLogger.log(this, "Splash", "onCreate started")
            setContentView(R.layout.activity_splash)

            AppPreferences.init(this)
            Http.initCache(cacheDir)
            WatchHistory.init(this)
            DiskImageCache.init(this)
            AutoPlayManager.setAutoPlayEnabled(AppPreferences.isAutoPlayEnabled())

            progressBar = findViewById(R.id.progress_splash)
            tvProgress = findViewById(R.id.tv_progress)

            object : CountDownTimer(1500, 50) {
                override fun onTick(millisUntilFinished: Long) {
                    val progress = ((1500 - millisUntilFinished) * 100 / 1500).toInt()
                    progressBar.progress = progress
                    tvProgress.text = "$progress%"
                }

                override fun onFinish() {
                    try {
                        progressBar.progress = 100
                        CrashLogger.log(this@SplashActivity, "Splash", "navigating to MainActivity")
                        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                    } catch (e: Exception) {
                        CrashLogger.log(this@SplashActivity, "Splash", "onFinish error: ${e.message}")
                    }
                    finish()
                }
            }.start()
        } catch (e: Exception) {
            try {
                startActivity(Intent(this, MainActivity::class.java))
            } catch (_: Exception) {
            }
            finish()
        }
    }
}
