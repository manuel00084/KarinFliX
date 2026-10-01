package com.karin.streamtv.util

import android.view.KeyEvent
import android.view.View

fun View.onActionKey(action: () -> Unit) {
    setOnKeyListener { _, keyCode, event ->
        if (GamepadHelper.isSelect(keyCode)) {
            // Consume ACTION_DOWN (dispara) y ACTION_UP (evita click sintético
            // doble). Otras teclas se devuelven false para no romper la
            // navegación D-pad existente en la vista.
            if (event.action == KeyEvent.ACTION_DOWN) action()
            true
        } else false
    }
}

object GamepadHelper {

    fun mapGamepadToDpad(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_MENU
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_SEARCH
            KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_MENU
            // SELECT ya no dispara SEARCH (provocaba búsquedas fantasma):
            // actúa como OK secundario.
            KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_MODE -> KeyEvent.KEYCODE_HOME
            KeyEvent.KEYCODE_BUTTON_C -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_Z -> KeyEvent.KEYCODE_MENU
            KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_PAGE_UP
            KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_PAGE_DOWN
            KeyEvent.KEYCODE_BUTTON_THUMBL -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_THUMBR -> KeyEvent.KEYCODE_MENU
            else -> keyCode
        }
    }

    fun isSelect(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A ||
                keyCode == KeyEvent.KEYCODE_BUTTON_SELECT
    }

}
