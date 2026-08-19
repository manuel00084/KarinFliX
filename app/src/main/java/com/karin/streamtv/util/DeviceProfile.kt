package com.karin.streamtv.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock

/**
 * Perfil de capacidad del dispositivo, calculado una sola vez, persistido en disco
 * y cargado en memoria.
 *
 * En la primera ejecución el [com.karin.streamtv.ui.SplashActivity] ejecuta un
 * micro-benchmark de CPU real y guarda el resultado; en los arranques siguientes
 * el perfil se lee de preferencias (carga instantánea). El resto de la app consulta
 * [get] para adaptar el comportamiento automáticamente:
 *
 *  - [com.karin.streamtv.player.RamAwareLoadControl]: buffer de ExoPlayer acotado al perfil.
 *  - [com.karin.streamtv.player.TrackSelectorFactory]: resolución/bitrate máx.
 *  - [com.karin.streamtv.player.Media3SixtyFpsProcessor]: escala de dibujo inicial del DRS.
 *  - Interfaz de ajustes: deshabilita / avisa sobre opciones pesadas (GL, 60p,
 *    upscaler) cuando el perfil no puede sostenerlas sin perder fluidez.
 */
object DeviceProfile {

    enum class Tier(val label: String) {
        LOW("Perfil básico (RAM limitada)"),
        MID("Perfil alto"),
        HIGH("Perfil máximo")
    }

    class Info(
        val tier: Tier,
        val ramBytes: Long,
        val heapBytes: Int,
        val cores: Int,
        val screenHeightPx: Int,
        val isTv: Boolean,
        val glEsVersion: Float,
        val cpuScore: Float = 0f,
        val benchmarked: Boolean = false
    ) {
        val ramGb: Float get() = ramBytes / (1024f * 1024f * 1024f)

        val maxVideoHeight: Int get() = when (tier) {
            Tier.LOW -> 1080
            Tier.MID -> 1440
            Tier.HIGH -> 2160
        }

        val maxVideoBitrate: Int get() = when (tier) {
            Tier.LOW -> 4_000_000
            Tier.MID -> 10_000_000
            Tier.HIGH -> Int.MAX_VALUE
        }

        val supportsGlEnhance: Boolean get() = tier != Tier.LOW
        val supportsInterpolation: Boolean get() = tier != Tier.LOW
        val supportsUpscalers: Boolean get() = tier != Tier.LOW

        /** Escala de dibujo inicial del lazo DRS: los equipos humildes arrancan más
         *  abajo para no dar tirones en los primeros segundos de reproducción. */
        val recommendedRenderScale: Float get() = when (tier) {
            Tier.LOW -> 0.8f
            Tier.MID -> 0.9f
            Tier.HIGH -> 1.0f
        }
    }

    private const val PREFS_NAME = "karin_device_profile"
    private const val VERSION = 2

    private const val KEY_VERSION = "version"
    private const val KEY_TIER = "tier"
    private const val KEY_RAM = "ram"
    private const val KEY_HEAP = "heap"
    private const val KEY_CORES = "cores"
    private const val KEY_SCREEN = "screen_height"
    private const val KEY_TV = "is_tv"
    private const val KEY_GL = "gl_version"
    private const val KEY_CPU = "cpu_score"
    private const val KEY_BENCH = "benchmarked"

    @Volatile private var cached: Info? = null
    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        var p = prefs
        if (p == null) {
            p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = p
        }
        return p
    }

    /** Rápido: devuelve el perfil persistido si existe; si no, construye uno estático
     *  (sin benchmark) en memoria. La primera ejecución real corre [recompute]. */
    fun get(context: Context): Info {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            loadFromPrefs(context)?.let {
                cached = it
                return it
            }
            val info = build(context)
            cached = info
            return info
        }
    }

    /** Ejecuta el micro-benchmark de CPU real y persiste el perfil completo.
     *  Solo se usa en la primera ejecución (o cuando se pide recalcular). */
    fun recompute(context: Context): Info {
        synchronized(this) {
            val static = get(context)
            val score = runCpuBenchmark()
            val info = Info(
                tier = tierFor(static.ramBytes, static.cores, static.heapBytes, score),
                ramBytes = static.ramBytes,
                heapBytes = static.heapBytes,
                cores = static.cores,
                screenHeightPx = static.screenHeightPx,
                isTv = static.isTv,
                glEsVersion = static.glEsVersion,
                cpuScore = score,
                benchmarked = true
            )
            savePrefs(context, info)
            cached = info
            return info
        }
    }

    private fun build(context: Context): Info {
        val ctx = context.applicationContext

        var ram = 0L
        var heap = 64
        var cores = 0
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            if (am != null) {
                val mi = android.app.ActivityManager.MemoryInfo()
                am.getMemoryInfo(mi)
                ram = if (Build.VERSION.SDK_INT >= 16) mi.totalMem else mi.availMem
                heap = am.memoryClass
            }
        } catch (t: Throwable) { ram = 0L }

        try {
            cores = Runtime.getRuntime().availableProcessors()
        } catch (t: Throwable) { cores = 0 }

        var height = 0
        try {
            height = ctx.resources.displayMetrics.heightPixels
        } catch (t: Throwable) { height = 0 }

        var gl = 2.0f
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val cfg = am?.deviceConfigurationInfo
            if (cfg != null) {
                val ver = cfg.reqGlEsVersion
                val major = (ver ushr 8) and 0xFF
                val minor = ver and 0xFF
                gl = (major + minor / 10f).coerceAtLeast(2.0f)
            }
        } catch (t: Throwable) { gl = 2.0f }

        val tier = tierFor(ram, cores, heap, 0f)
        return Info(tier, ram, heap, cores, height, DeviceUtils.isTvDevice(ctx), gl)
    }

    private fun loadFromPrefs(context: Context): Info? {
        val p = prefs(context) ?: return null
        if (p.getInt(KEY_VERSION, 0) != VERSION) return null
        val tierIdx = p.getInt(KEY_TIER, -1)
        if (tierIdx !in 0 until Tier.entries.size) return null
        val ram = p.getLong(KEY_RAM, 0)
        if (ram <= 0) return null
        return Info(
            tier = Tier.entries[tierIdx],
            ramBytes = ram,
            heapBytes = p.getInt(KEY_HEAP, 64),
            cores = p.getInt(KEY_CORES, 0),
            screenHeightPx = p.getInt(KEY_SCREEN, 0),
            isTv = p.getBoolean(KEY_TV, false),
            glEsVersion = p.getFloat(KEY_GL, 2.0f),
            cpuScore = p.getFloat(KEY_CPU, 0f),
            benchmarked = p.getBoolean(KEY_BENCH, false)
        )
    }

    private fun savePrefs(context: Context, info: Info) {
        val p = prefs(context) ?: return
        p.edit()
            .putInt(KEY_VERSION, VERSION)
            .putInt(KEY_TIER, info.tier.ordinal)
            .putLong(KEY_RAM, info.ramBytes)
            .putInt(KEY_HEAP, info.heapBytes)
            .putInt(KEY_CORES, info.cores)
            .putInt(KEY_SCREEN, info.screenHeightPx)
            .putBoolean(KEY_TV, info.isTv)
            .putFloat(KEY_GL, info.glEsVersion)
            .putFloat(KEY_CPU, info.cpuScore)
            .putBoolean(KEY_BENCH, info.benchmarked)
            .apply()
    }

    // Sink que impide al JIT eliminar el bucle del benchmark como código muerto.
    @Volatile private var benchSink = 0.0

    /** Micro-benchmark de CPU puro: mide cuánto tarda un bucle fijo de aritmética
     *  en doble precisión y devuelve una puntuación normalizada (mayor = más rápido).
     *  Está acotado a ~0.2-0.6 s incluso en hardware muy débil. */
    private fun runCpuBenchmark(): Float {
        return try {
            val work = 24_000_000

            // Warmup: el primer paso paga cold-cache/JIT y no se cronometra.
            var warm = 0.0
            for (i in 0 until 2_000_000) warm += (i and 255) * 1.0000001
            benchSink = warm

            val start = SystemClock.uptimeMillis()
            var acc = 0.0
            for (i in 0 until work) {
                acc += (i and 1023) * 0.999999999 + (i ushr 10)
                if ((i and 0x7FFF) == 0) acc -= 0.0000000001
            }
            benchSink = acc
            val elapsedMs = (SystemClock.uptimeMillis() - start).coerceAtLeast(1L)
            (2000f / elapsedMs).coerceIn(1f, 120f)
        } catch (t: Throwable) {
            0f
        }
    }

    /** Puntuación combinada de recursos físicos + benchmark → gama del equipo. */
    private fun tierFor(ram: Long, cores: Int, heap: Int, cpuScore: Float): Tier {
        var score = 0
        val lowLimitBytes = (1.5f * 1024 * 1024 * 1024).toLong()
        val highLimitBytes = (4f * 1024 * 1024 * 1024).toLong()

        if (ram > 0) {
            if (ram < lowLimitBytes) score -= 1
            else if (ram >= highLimitBytes) score += 1
        }
        if (cores in 1..3) score -= 1
        if (cores >= 6) score += 1
        if (heap < 100) score -= 1
        if (cpuScore > 0f) {
            if (cpuScore < 18f) score -= 1
            else if (cpuScore > 35f) score += 1
        }

        return when {
            score >= 2 -> Tier.HIGH
            score <= -2 -> Tier.LOW
            else -> Tier.MID
        }
    }
}
