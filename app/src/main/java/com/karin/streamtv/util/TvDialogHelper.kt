package com.karin.streamtv.util

import android.app.AlertDialog
import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.ListView

object TvDialogHelper {

    fun makeListTvReady(dialog: AlertDialog, listView: ListView, context: Context) {
        if (!DeviceUtils.isTvDevice(context)) return
        listView.isFocusable = true
        listView.isFocusableInTouchMode = false
        dialog.setOnShowListener {
            listView.requestFocus()
            listView.setSelection(0)
        }
    }

    /**
     * Habilita navegación con control remoto entre el ListView y los botones
     * de navegación (anterior / capítulos / siguiente) del diálogo.
     */
    fun makeDialogTvReady(
        dialog: AlertDialog,
        listView: ListView,
        context: Context,
        vararg buttons: View
    ) {
        if (!DeviceUtils.isTvDevice(context)) return
        val enabledButtons = buttons.filter { it.isFocusable && it.visibility == View.VISIBLE }
        if (enabledButtons.isEmpty()) {
            makeListTvReady(dialog, listView, context)
            return
        }

        listView.isFocusable = true
        listView.isFocusableInTouchMode = false
        enabledButtons.forEach { btn ->
            btn.isFocusable = true
            btn.isFocusableInTouchMode = false
        }

        dialog.setOnShowListener {
            listView.requestFocus()
            listView.setSelection(0)
        }

        // Desde la lista: DPAD_DOWN al final -> foco en el primer botón.
        listView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                val last = listView.lastVisiblePosition
                if (last >= listView.count - 1) {
                    enabledButtons.first().requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }

        // Entre botones: DPAD_LEFT/RIGHT navegan horizontalmente.
        enabledButtons.forEachIndexed { index, btn ->
            btn.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (index > 0) enabledButtons[index - 1].requestFocus() else listView.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (index < enabledButtons.lastIndex) enabledButtons[index + 1].requestFocus() else listView.requestFocus()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        listView.requestFocus()
                        true
                    }
                    else -> false
                }
            }
        }
    }
}