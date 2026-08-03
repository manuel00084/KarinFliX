package com.karin.streamtv.util

import android.app.AlertDialog
import android.content.Context
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
}
