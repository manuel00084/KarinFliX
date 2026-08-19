package com.karin.streamtv.ui

import com.karin.streamtv.R
import com.karin.streamtv.karinlink.RemoteRef
import com.karin.streamtv.karinlink.SmbRef
import java.io.File

data class FileEntry(
    val file: File,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val itemCount: Int = 0,
    val customName: String? = null,
    val storageDescription: String? = null,
    val storageTotalBytes: Long = 0L,
    val storageUsedBytes: Long = 0L,
    val storagePrimary: Boolean = false,
    val remote: RemoteRef? = null,
    val smb: SmbRef? = null,
    val isAnimeBadge: Boolean = false
) {

    val name: String get() = customName ?: file.name.ifEmpty { file.absolutePath }

    val extension: String get() = file.name.substringAfterLast('.', "").lowercase()

    val fileType: FileType
        get() = when {
            isDirectory -> FileType.FOLDER
            extension in VIDEO_EXTENSIONS -> FileType.VIDEO
            extension in AUDIO_EXTENSIONS -> FileType.AUDIO
            extension in IMAGE_EXTENSIONS -> FileType.IMAGE
            extension in DOC_EXTENSIONS -> FileType.DOCUMENT
            extension == "apk" -> FileType.APK
            extension in ARCHIVE_EXTENSIONS -> FileType.ARCHIVE
            else -> FileType.OTHER
        }

    /** True si esta entrada proviene de un dispositivo remoto (KARIN Link o SMB). */
    val isNetwork: Boolean get() = remote != null || smb != null

    /** Referencia al servidor remoto asociado (Network o SMB). */
    val networkRef: Any? get() = remote ?: smb

    enum class FileType(val iconRes: Int, val colorRes: Int) {
        FOLDER(R.drawable.ic_folder, R.color.file_folder),
        VIDEO(R.drawable.ic_video, R.color.file_video),
        AUDIO(R.drawable.ic_audio, R.color.file_audio),
        IMAGE(R.drawable.ic_image, R.color.file_image),
        DOCUMENT(R.drawable.ic_doc, R.color.file_document),
        APK(R.drawable.ic_apk, R.color.file_apk),
        ARCHIVE(R.drawable.ic_archive, R.color.file_archive),
        OTHER(R.drawable.ic_file, R.color.file_other)
    }

    companion object {
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "3gp",
            "mpg", "mpeg", "m2ts", "rmvb", "vob", "ogv", "divx", "asf"
        )
        val AUDIO_EXTENSIONS = setOf(
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma", "mid", "midi", "amr"
        )
        val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "ico", "tif", "tiff"
        )
        val DOC_EXTENSIONS = setOf(
            "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
            "rtf", "epub", "md", "csv", "json", "xml", "html", "htm"
        )
        val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst")

        fun fromFile(file: File, itemCount: Int = 0): FileEntry {
            val isDir = file.isDirectory
            return FileEntry(
                file = file,
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else file.length(),
                lastModified = file.lastModified(),
                itemCount = if (isDir) itemCount else 0
            )
        }

        /**
         * Entrada de un dispositivo remoto. El [File] es un marcador de posición
         * cuyo último segmento es el nombre real del archivo, así el cálculo de
         * extensión/tipo funciona igual que con archivos locales.
         */
        fun fromRemote(
            ref: RemoteRef,
            name: String,
            isDirectory: Boolean,
            sizeBytes: Long,
            lastModified: Long,
            itemCount: Int = 0
        ): FileEntry {
            val base = "remote://${ref.host}:${ref.port}"
            val p = ref.remotePath
            val dummy = File(if (p.startsWith("/")) base + p else "$base/$p")
            return FileEntry(
                file = dummy,
                isDirectory = isDirectory,
                sizeBytes = if (isDirectory) 0L else sizeBytes,
                lastModified = lastModified,
                itemCount = if (isDirectory) itemCount else 0,
                customName = name,
                remote = ref
            )
        }

        /**
         * Entrada de un recurso SMB (Windows network share). El [File] es un
         * marcador de posición cuyo último segmento es el nombre real del archivo.
         */
        fun fromSmb(
            ref: SmbRef,
            name: String,
            isDirectory: Boolean,
            sizeBytes: Long,
            lastModified: Long,
            itemCount: Int = 0,
            isAnimeBadge: Boolean = false
        ): FileEntry {
            val base = "smb://${ref.host}:${ref.port}"
            val p = ref.remotePath
            val dummy = File(if (p.startsWith("/")) base + p else "$base/$p")
            return FileEntry(
                file = dummy,
                isDirectory = isDirectory,
                sizeBytes = if (isDirectory) 0L else sizeBytes,
                lastModified = lastModified,
                itemCount = if (isDirectory) itemCount else 0,
                customName = name,
                smb = ref,
                isAnimeBadge = isAnimeBadge
            )
        }

        /**
         * Entrada de almacenamiento raíz (share SMB o volumen). El [File] es un
         * marcador de posición cuyo nombre refleja el share.
         */
        fun fromSmbShare(
            ref: SmbRef,
            name: String,
            description: String,
            totalBytes: Long = 0L,
            usedBytes: Long = 0L
        ): FileEntry {
            val dummy = File("smb://${ref.host}:${ref.port}/$name")
            return FileEntry(
                file = dummy,
                isDirectory = true,
                sizeBytes = 0L,
                lastModified = 0L,
                itemCount = 0,
                customName = name,
                storageDescription = description,
                storageTotalBytes = totalBytes,
                storageUsedBytes = usedBytes,
                storagePrimary = false,
                smb = ref.copy(share = name, remotePath = "/"),
                isAnimeBadge = false
            )
        }
    }
}
