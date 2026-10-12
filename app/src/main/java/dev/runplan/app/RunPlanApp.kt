package dev.runplan.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.runplan.app.data.BugReport
import dev.runplan.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import java.io.File

class RunPlanApp : Application() {
    lateinit var store: Store
        private set

    override fun onCreate() {
        super.onCreate()
        BugReport.install(this)
        instance = this
        store = Store(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        scope.launch(Dispatchers.IO) { runCatching { store.backfillAnalytics() } }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Active run", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows your current step and pace while a run is recording"
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val CHANNEL_RUN = "run"

        /** Outlives screens and the run service: used for uploads. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        lateinit var instance: RunPlanApp
            private set
        val store: Store get() = instance.store
    }
}
