package com.karin.streamtv.util

import android.view.KeyEvent
import android.view.View

fun View.onActionKey(action: () -> Unit) {
    setOnKeyListener { _, keyCode, event ->
        if (event.action == KeyEvent.ACTION_DOWN && GamepadHelper.isSelect(keyCode)) {
            action()
            true
        } else false
    }
}

object GamepadHelper {

    fun mapGamepadToDpad(keyCode: Int): Int {
        return when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_MENU
            KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_SEARCH
            KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_MEDIA_REWIND
            KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            else -> keyCode
        }
    }

    fun isSelect(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_BUTTON_A
    }

}
