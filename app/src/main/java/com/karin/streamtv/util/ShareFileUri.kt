package com.karin.streamtv.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Convierte un Uri de archivo a otro que pueda compartirse con otras apps (p. ej.
 * al delegar la reproducción a un reproductor externo). Los URI con esquema "file"
 * se re-expanden a través de [FileProvider] para evitar FileUriExposedException.
 */
object ShareFileUri {

    fun shareableUri(context: Context, uri: Uri): Uri {
        if (uri.scheme == "file") {
            val filePath = uri.path
            val file = filePath?.let { File(it) }
            if (file?.exists() == true && file.isFile) {
                return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }
        }
        return uri
    }
}