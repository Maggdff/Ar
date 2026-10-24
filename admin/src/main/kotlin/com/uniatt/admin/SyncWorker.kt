package com.uniatt.admin

import android.content.Context
import androidx.work.*
import com.uniatt.licensekit.LicenseManager
import java.util.concurrent.TimeUnit

/** مزامنة دورية في الخلفية كلما توفّر الإنترنت (حتى والتطبيق مغلق). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        val store = AdminStore(applicationContext)
        LicenseManager.ensure(applicationContext) { store.load().effectiveRelay }
        LicenseManager.refresh()
        if (!LicenseManager.allowed()) return ListenableWorker.Result.success()       // الترخيص موقوف: لا مزامنة
        val r = AdminSync.run(store)
        return if (r.offline) ListenableWorker.Result.retry() else ListenableWorker.Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("uniatt-sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
