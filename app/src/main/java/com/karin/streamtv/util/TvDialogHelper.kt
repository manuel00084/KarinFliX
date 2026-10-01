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

    /** Variante para diálogos de AppCompat (misma conducta D-pad). */
    fun makeListTvReady(
        dialog: androidx.appcompat.app.AlertDialog,
        listView: ListView?,
        context: Context,
    ) {
        if (!DeviceUtils.isTvDevice(context)) return
        if (listView == null) return
        listView.isFocusable = true
        listView.isFocusableInTouchMode = false
        dialog.setOnShowListener {
            listView.requestFocus()
            if (listView.count > 0) listView.setSelection(0)
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

    /**
     * Diálogo genérico usable con mando: enfoca el primer botón visible y
     * cablea LEFT/RIGHT entre botones + UP/DOWN hacia la lista si existe.
     * Llamar después de show() o desde setOnShowListener.
     */
    fun makeButtonsTvReady(
        dialog: AlertDialog,
        context: Context,
        listView: ListView? = null,
    ) {
        if (!DeviceUtils.isTvDevice(context)) return
        dialog.setOnShowListener {
            val buttons = listOf(
                dialog.getButton(AlertDialog.BUTTON_POSITIVE),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE),
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL),
            ).filter { it != null && it.visibility == View.VISIBLE && it.isFocusable }
            buttons.forEach { b ->
                b.isFocusable = true
                b.isFocusableInTouchMode = false
            }
            listView?.let {
                it.isFocusable = true
                it.isFocusableInTouchMode = false
            }
            // Foco inicial: lista si hay, si no primer botón.
            if (listView != null && listView.count > 0) {
                listView.requestFocus()
                listView.setSelection(0)
            } else {
                buttons.firstOrNull()?.requestFocus()
            }
            buttons.forEachIndexed { index, btn ->
                btn.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index > 0) buttons[index - 1].requestFocus()
                            else listView?.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index < buttons.lastIndex) buttons[index + 1].requestFocus()
                            else listView?.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (listView != null) { listView.requestFocus(); true } else false
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (listView != null) { listView.requestFocus(); true } else false
                        }
                        else -> false
                    }
                }
            }
            listView?.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (listView.lastVisiblePosition >= listView.count - 1) {
                        buttons.firstOrNull()?.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    /** Variante AppCompat del helper genérico de botones. */
    fun makeButtonsTvReady(
        dialog: androidx.appcompat.app.AlertDialog,
        context: Context,
        listView: ListView? = null,
    ) {
        if (!DeviceUtils.isTvDevice(context)) return
        dialog.setOnShowListener {
            val buttons = listOf(
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE),
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE),
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL),
            ).filter { it != null && it.visibility == View.VISIBLE && it.isFocusable }
            buttons.forEach { b ->
                b.isFocusable = true
                b.isFocusableInTouchMode = false
            }
            listView?.let {
                it.isFocusable = true
                it.isFocusableInTouchMode = false
            }
            if (listView != null && listView.count > 0) {
                listView.requestFocus()
                listView.setSelection(0)
            } else {
                buttons.firstOrNull()?.requestFocus()
            }
            buttons.forEachIndexed { index, btn ->
                btn.setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index > 0) buttons[index - 1].requestFocus()
                            else listView?.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index < buttons.lastIndex) buttons[index + 1].requestFocus()
                            else listView?.requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (listView != null) { listView.requestFocus(); true } else false
                        }
                        else -> false
                    }
                }
            }
        }
    }
}