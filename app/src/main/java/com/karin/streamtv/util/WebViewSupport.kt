package com.karin.streamtv.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.WebView

/**
 * Soporte WebView con conciencia de Smart TV.
 *
 * En muchos TV el proveedor WebView viene desactivado o desactualizado y
 * `WebView(context)` lanza excepción (en EmbedWebViewActivity mata la
 * activity; en CloudflareInterceptor, al correr sin try/catch en el hilo
 * principal, tumba la app). Estas funciones permiten detectarlo antes y
 * degradar a navegador externo en vez de morir.
 */
object WebViewSupport {

    private const val TAG = "WebViewSupport"

    /** true si se puede instanciar un WebView en este equipo. */
    fun isAvailable(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pkg = WebView.getCurrentWebViewPackage() ?: return false
                val ai = context.packageManager.getApplicationInfo(pkg.packageName, 0)
                if (!ai.enabled) {
                    Log.w(TAG, "Proveedor WebView deshabilitado: ${pkg.packageName}")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "WebView no disponible: ${e.message}")
            false
        }
    }

    /** Abre la URL en el navegador del sistema. false si ni eso se pudo. */
    fun openExternal(context: Context, url: String): Boolean {
        return try {
            if (url.isBlank()) return false
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Sin navegador para $url: ${e.message}")
            false
        }
    }
}
