package com.karin.streamtv.karinlink

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Las carpetas que el usuario decide compartir.
 *
 * Vive en prefs en vez de en [KarinLinkManager] porque lo consultan dos caminos
 * distintos: la pantalla de Ajustes, que escribe, y el servidor, que lee en
 * cada petición. Si solo lo guardara el manager, apagar KARIN Link y volver a
 * encenderlo perdería el estado.
 */
object FsStore {

    private const val TAG = "KarinLinkFs"
    private const val PREFS = "karin_link"
    private const val KEY_ROOTS = "fs_roots"
    private const val KEY_ENABLED = "remote_fs_enabled"
    private const val KEY_TOKEN = "fs_token"

    fun roots(context: Context): List<File> {
        val raw = prefs(context).getString(KEY_ROOTS, null) ?: return emptyList()
        return raw.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.isDirectory }
    }

    /**
     * Añade una carpeta compartida.
     *
     * Se guarda la ruta canónica para que comparar contra las peticiones
     * posteriores no dependa de si el usuario escribió `/media/x` o
     * `/media/x/` o con un punto suelto en medio.
     *
     * @return false si no se pudo añadir, para poder avisar en la UI.
     */
    fun addRoot(context: Context, dir: File): Boolean {
        if (!dir.isDirectory) return false
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return false
        val current = roots(context).toMutableList()
        if (current.any { it.path == canonical.path }) return true
        current.add(canonical)
        if (write(context, current)) {
            Log.i(TAG, "Sharing ${canonical.path}")
            return true
        }
        return false
    }

    fun removeRoot(context: Context, dir: File) {
        val canonical = runCatching { dir.canonicalFile }.getOrNull() ?: return
        val remaining = roots(context).filter { it.path != canonical.path }
        write(context, remaining)
        Log.i(TAG, "Stopped sharing ${canonical.path}")
    }

    private fun write(context: Context, list: List<File>): Boolean = runCatching {
        prefs(context).edit()
            .putString(KEY_ROOTS, list.joinToString("\n") { it.path })
            .apply()
        true
    }.getOrElse {
        Log.w(TAG, "Could not save shared folders: ${it.message}")
        false
    }

    /**
     * Lo que el servidor puede servir ahora mismo.
     *
     * @return null cuando la función está apagada, para que el endpoint
     *   responda 404 y no confirme siquiera que existe.
     */
    fun config(context: Context): FsConfig? {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return null
        val token = prefs.getString(KEY_TOKEN, null)
        return FsConfig(enabled = true, token = token, roots = roots(context))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
