package com.karin.streamtv.util

import android.app.Activity

fun Activity.enableTvFocus() {
    if (!DeviceUtils.isTvDevice(this)) return
    window.decorView.post {
        // En TV sin touchscreen requestFocusFromTouch() es no-op:
        // se usa requestFocus() clásico con fallback al primer focuseable.
        val decor = window.decorView
        if (!decor.requestFocus()) {
            decor.focusSearch(android.view.View.FOCUS_FORWARD)?.requestFocus()
        }
    }
}
