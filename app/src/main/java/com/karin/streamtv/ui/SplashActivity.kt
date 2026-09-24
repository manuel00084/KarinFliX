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
import com.karin.streamtv.util.EpisodeProgress
import com.karin.streamtv.util.Http
import com.karin.streamtv.util.WatchHistory

class SplashActivity : AppCompatActivity() {

    private lateinit var progressBar: ProgressBar
    private lateinit var tvProgress: TextView
    private lateinit var tvLoadingStatus: TextView
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            CrashLogger.init(this)
            CrashLogger.log(this, "Splash", "onCreate started")
            setContentView(R.layout.activity_splash)

            Http.initCache(cacheDir)
            com.karin.streamtv.scraper.ScrapingEngine.init(this)
            WatchHistory.init(this)
            EpisodeProgress.init(this)
            DiskImageCache.init(this)
            AutoPlayManager.setAutoPlayEnabled(AppPreferences.isAutoPlayEnabled())

            progressBar = findViewById(R.id.progress_splash)
            tvProgress = findViewById(R.id.tv_progress)
            tvLoadingStatus = findViewById(R.id.tv_loading_status)

            timer = object : CountDownTimer(2800, 50) {
                override fun onTick(millisUntilFinished: Long) {
                    val progress = ((2800 - millisUntilFinished) * 100 / 2800).toInt()
                    progressBar.progress = progress
                    tvProgress.text = "$progress%"
                    tvLoadingStatus.text = loadingMessageFor(progress)
                }

                override fun onFinish() {
                    try {
                        progressBar.progress = 100
                        tvProgress.text = "100%"
                        tvLoadingStatus.text = "¡Listo! 🌸"
                        CrashLogger.log(this@SplashActivity, "Splash", "navigating")
                        val next = if (AppPreferences.isFirstRun()) {
                            OnboardingActivity::class.java
                        } else {
                            MainActivity::class.java
                        }
                        startActivity(Intent(this@SplashActivity, next))
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

    override fun onDestroy() {
        timer?.cancel()
        timer = null
        super.onDestroy()
    }

    private fun loadingMessageFor(progress: Int): String = when {
        progress < 15 -> "Cargando recursos..."
        progress < 35 -> "Cargando catálogo..."
        progress < 55 -> "Conectando sitios..."
        progress < 75 -> "Sincronizando historial..."
        progress < 90 -> "Preparando interfaz..."
        else -> "Finalizando..."
    }
}
