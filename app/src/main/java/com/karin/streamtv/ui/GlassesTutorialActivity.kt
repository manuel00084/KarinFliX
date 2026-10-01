package com.karin.streamtv.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentAdapter.LayoutResultCallback
import android.print.PrintDocumentAdapter.WriteResultCallback
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.karin.streamtv.R
import com.karin.streamtv.util.onActionKey
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil
import kotlin.math.max

/**
 * Tutorial de gafas 3D caseras: anaglifo, Pulfrich y visor VR Cardboard
 * (materiales, dónde conseguirlos, medidas, plano y armado paso a paso).
 * Se abre desde el diálogo "Tecnología 3D" del reproductor.
 * Incluye exportar el tutorial a PDF (compartir o imprimir).
 */
class GlassesTutorialActivity : ScrollableInfoActivity() {

    override val layoutRes = R.layout.activity_glasses_tutorial

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val PAGE_MARGIN = 24
        const val PDF_FILE_NAME = "gafas_3d_caseras.pdf"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btnPdf = findViewById<TextView>(R.id.btn_pdf)
        btnPdf.setOnClickListener { showPdfOptions() }
        btnPdf.onActionKey { showPdfOptions() }
    }

    private fun showPdfOptions() {
        val options = arrayOf("Compartir como PDF", "Imprimir (o guardar como PDF)")
        AlertDialog.Builder(this)
            .setTitle("Tutorial en PDF")
            .setItems(options) { _, which ->
                if (which == 0) sharePdf() else printPdf()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sharePdf() {
        val file = exportPdf() ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Gafas 3D caseras - KarinFLiX")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Compartir PDF"))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo compartir el PDF", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportPdf(): File? {
        val document = buildPdfDocument()
        if (document == null) {
            Toast.makeText(this, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
            return null
        }
        val file = File(cacheDir, PDF_FILE_NAME)
        try {
            FileOutputStream(file).use { document.writeTo(it) }
            return file
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo generar el PDF", Toast.LENGTH_LONG).show()
            return null
        } finally {
            document.close()
        }
    }

    private fun printPdf() {
        try {
            val printManager = getSystemService(PRINT_SERVICE) as PrintManager
            printManager.print("Gafas 3D caseras", TutorialPdfAdapter(), null)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir la impresión", Toast.LENGTH_LONG).show()
        }
    }

    /** Dibuja el contenido completo del tutorial sobre páginas A4 con fondo de la app. */
    private fun buildPdfDocument(): PdfDocument? {
        val content = findViewById<LinearLayout>(R.id.content_layout) ?: return null
        if (content.width <= 0 || content.height <= 0) return null

        val document = PdfDocument()
        val background = ContextCompat.getColor(this, R.color.bg_dark)
        val usableWidth = PAGE_WIDTH - 2 * PAGE_MARGIN
        val usableHeight = PAGE_HEIGHT - 2 * PAGE_MARGIN
        val scale = usableWidth.toFloat() / content.width
        val pageCount = max(1, ceil(content.height * scale / usableHeight).toInt())

        try {
            for (index in 0 until pageCount) {
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create()
                )
                val canvas = page.canvas
                canvas.drawColor(background)
                canvas.save()
                canvas.scale(scale, scale)
                canvas.translate(
                    PAGE_MARGIN / scale,
                    PAGE_MARGIN / scale - index * usableHeight / scale
                )
                content.draw(canvas)
                canvas.restore()
                document.finishPage(page)
            }
        } catch (e: Exception) {
            document.close()
            return null
        }
        return document
    }

    private inner class TutorialPdfAdapter : PrintDocumentAdapter() {

        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled() == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder(PDF_FILE_NAME)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            if (cancellationSignal?.isCanceled() == true) {
                callback.onWriteCancelled()
                return
            }
            val document = buildPdfDocument()
            if (document == null || destination == null) {
                callback.onWriteFailed("No se pudo generar el PDF")
                document?.close()
                return
            }
            try {
                FileOutputStream(destination.fileDescriptor).use { document.writeTo(it) }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            } finally {
                document.close()
            }
        }
    }
}
