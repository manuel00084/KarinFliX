package com.karin.streamtv.player

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.karin.streamtv.util.DeviceProfile
import java.io.File

/**
 * Caché de disco acotada (LRU) compartida por todo el proceso para los
 * segmentos de video. Acelera la carga porque:
 *  - las reproducciones/rebúferes reutilizan los bytes ya descargados,
 *  - los saltos (seek) no siempre obligan a re-pedir el rango al servidor,
 *  - el arranque de un mismo enlace en reintentos/failover es instantáneo.
 *
 * El tamaño se escala con el perfil del dispositivo para no saturar la RAM
 * ni el almacenamiento de gama baja.
 */
object VideoCache {
    @Volatile
    private var cache: Cache? = null
    private val lock = Any()

    private fun sizeFor(context: Context): Long = when (DeviceProfile.get(context).tier) {
        DeviceProfile.Tier.LOW -> 150L * 1024 * 1024
        DeviceProfile.Tier.MID -> 300L * 1024 * 1024
        DeviceProfile.Tier.HIGH -> 600L * 1024 * 1024
    }

    fun get(context: Context): Cache = cache ?: synchronized(lock) {
        cache ?: run {
            val appCtx = context.applicationContext
            val dir = File(appCtx.cacheDir, "video_exo")
            val db: DatabaseProvider = StandaloneDatabaseProvider(appCtx)
            val evictor = LeastRecentlyUsedCacheEvictor(sizeFor(appCtx))
            SimpleCache(dir, evictor, db).also { cache = it }
        }
    }

    fun wrap(context: Context, upstream: DataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
}
