package com.karin.streamtv.karinlink

import java.io.File

/**
 * Qué queda expuesto por [LinkProtocol.FS_PATH] en un momento dado.
 *
 * Se lee en cada petición, no se cachea, para que apagar el interruptor de
 * Ajustes corte el acceso en el acto aunque el servidor siga vivo.
 */
data class FsConfig(
    val enabled: Boolean,
    val token: String?,
    /** Carpetas compartidas. Cualquier ruta fuera de ellas se rechaza. */
    val roots: List<File>
) {
    /** Sin raíces no se sirve nada: un token sin contenido no expone archivos. */
    val isServable: Boolean get() = enabled && roots.isNotEmpty()

    /**
     * Si la escritura de un vídeo está permitida.
     *
     * Separate de [isServable] a propósito: una TV puede no compartir ninguna
     * carpeta y aun así aceptar que le manden un vídeo para ver. Atar ambas
     * cosas habría hecho que subir fallara en el equipo que mejor lo necesita,
     * el que solo usa KARIN Link para pasarle pelis.
     */
    val isWritable: Boolean get() = enabled && !token.isNullOrBlank()
}
