package com.karin.streamtv.util

import android.app.Activity

fun Activity.enableTvFocus() {
    if (!DeviceUtils.isTvDevice(this)) return
    window.decorView.post {
        window.decorView.requestFocusFromTouch()
    }
}
