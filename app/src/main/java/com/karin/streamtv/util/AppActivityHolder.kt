package com.karin.streamtv.util

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicReference

object AppActivityHolder : Application.ActivityLifecycleCallbacks {

    private val current = AtomicReference<Activity?>()

    fun current(): Activity? = current.get()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {
        current.set(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (current.get() === activity) current.set(null)
    }

    override fun onActivityStopped(activity: Activity) {
        if (current.get() === activity) current.set(null)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (current.get() === activity) current.set(null)
    }
}
