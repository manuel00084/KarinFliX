package com.karin.streamtv.karinlink

import android.content.Context
import android.content.SharedPreferences

/**
 * Guarda de forma local (SharedPreferences) los tokens de acceso de los servicios
 * en la nube. Los tokens que introduce el usuario se persisten aquí y se usan
 * tanto para listar carpetas como para el proxy de reproducción.
 */
object CloudTokenStore {

    private const val PREFS = "karin_flix_cloud"
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** Devuelve el token guardado para el proveedor, o null si no existe. */
    @Synchronized
    fun getToken(provider: CloudProvider): String? =
        if (::prefs.isInitialized) prefs.getString(provider.storageKey, null) ?: "" else ""

    /**
     * Guarda el token del proveedor. Si [token] es null o vacío elimina el token.
     * Devuelve true si requirió persistencia (no comprueba igualdad).
     */
    @Synchronized
    fun setToken(provider: CloudProvider, token: String?) {
        if (!::prefs.isInitialized) return
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(provider.storageKey)
            else putString(provider.storageKey, token.trim())
        }.apply()
    }

    /** Devuelve null si el proveedor está configurado (tiene token). */
    fun hasToken(provider: CloudProvider): Boolean = !getToken(provider).isNullOrBlank()

    /** Número de proveedores configurados. */
    fun configuredCount(): Int =
        if (::prefs.isInitialized) CloudProvider.entries.count { hasToken(it) } else 0

    fun allConfigured(): List<CloudProvider> =
        if (::prefs.isInitialized) CloudProvider.entries.filter { hasToken(it) } else emptyList()
}
