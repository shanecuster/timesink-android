package com.shanetalkstech.timesink

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.io.File
import uniffi.timesink_ffi.Database
import uniffi.timesink_ffi.SyncServer

/**
 * Scheduling for the app's only background work: one short check every 15
 * minutes, and only while the master switch AND "update in the background" are
 * on. There is no foreground service and no wake lock; Android runs the job
 * when it chooses and it ends in moments.
 *
 * What the job does is in [TickJobService].
 */
object Background {
    /** The number Android knows our one scheduled job by. */
    private const val JOB_ID = 1

    /** How often the check runs. 15 minutes is the shortest Android allows for a repeating job. */
    private const val TICK_MS = 15 * 60 * 1000L

    /**
     * Makes the schedule match the settings: schedule the job if background
     * updates should run, cancel it if not. Safe to call as often as needed.
     */
    fun reschedule(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val settings = AppSettings(context)
        if (settings.enabled && settings.battery.backgroundUpdates) {
            // Keep an existing schedule so its timing is not reset at every app open.
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, TickJobService::class.java))
                .setPeriodic(TICK_MS)
                // Survive a reboot (needs the RECEIVE_BOOT_COMPLETED permission).
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        } else {
            scheduler.cancel(JOB_ID)
        }
    }
}

/**
 * The short job Android runs every 15 minutes while background updates are on.
 *
 * Each run: check the master switch, check the battery (sending the "paused"
 * notice if needed), and, only if not paused and a few hours have passed,
 * record the phone's usage and sync. Then it finishes.
 */
class TickJobService : JobService() {
    /** How long to wait between actual updates; the 15 minute check does nothing in between. */
    private val updateEveryMs = 3 * 60 * 60 * 1000L

    /**
     * Android calls this to start the job. The work runs on its own thread
     * (the main thread must stay free), and `jobFinished` tells Android it is done.
     */
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                tick()
            } catch (e: Exception) {
                // A failed background run is not worth a crash; the next one tries again.
            }
            jobFinished(params, false)
        }.start()
        // `true` = work is still going on another thread.
        return true
    }

    /** Called if Android has to stop the job early; `false` = do not retry it sooner than the schedule. */
    override fun onStopJob(params: JobParameters): Boolean = false

    /** One run of the job. */
    private fun tick() {
        val settings = AppSettings(this)
        // The first thing every run does is check the switches.
        if (!settings.enabled || !settings.battery.backgroundUpdates) return
        // Paused? Then say so (once) and do nothing else.
        if (BatteryGuard.checkAndNotify(this, settings) != null) return
        val now = System.currentTimeMillis()
        if (now - settings.lastBackgroundUpdateMs < updateEveryMs) return

        val db = Database.open(
            File(filesDir, "timesink.db").path,
            settings.deviceName,
            File(filesDir, "apps.conf").path,
        )
        try {
            if (UsageImporter.hasAccess(this)) UsageImporter.importNow(this, db, settings)
            if (settings.syncConfigured) {
                try {
                    db.syncNow(SyncServer(settings.serverUrl, settings.database, settings.user, settings.password))
                } catch (e: Exception) {
                    // No network, or the server is away: the next run tries again.
                }
            }
            settings.lastBackgroundUpdateMs = now
        } finally {
            db.close()
        }
    }
}
