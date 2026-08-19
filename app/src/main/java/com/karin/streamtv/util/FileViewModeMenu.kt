package com.karin.streamtv.util

import android.view.View
import android.widget.PopupMenu

/** Menú de vista de archivos (estilo Explorador de Windows). */
object FileViewModeMenu {

    fun label(mode: FileViewMode): String = when (mode) {
        FileViewMode.EXTRA_LARGE -> "Iconos muy grandes"
        FileViewMode.LARGE -> "Iconos grandes"
        FileViewMode.MEDIUM -> "Iconos medianos"
        FileViewMode.SMALL -> "Iconos pequeños"
        FileViewMode.LIST -> "Lista"
        FileViewMode.DETAIL -> "Detallado"
    }

    fun show(anchor: View, onSelected: (FileViewMode) -> Unit) {
        val popup = PopupMenu(anchor.context, anchor)
        val modes = FileViewMode.values()
        modes.forEachIndexed { index, mode ->
            popup.menu.add(0, index, index, label(mode))
        }
        popup.setOnMenuItemClickListener { item ->
            modes.getOrNull(item.itemId)?.let(onSelected)
            true
        }
        popup.show()
    }
}
