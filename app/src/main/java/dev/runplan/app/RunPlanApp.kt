package dev.runplan.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.runplan.app.data.Store

class RunPlanApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Active run", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows your current step and pace while a run is recording"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_RUN = "run"
        lateinit var instance: RunPlanApp
            private set
        val store: Store get() = instance.store
    }
}
