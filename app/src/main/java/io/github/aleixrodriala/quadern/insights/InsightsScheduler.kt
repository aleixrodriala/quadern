package io.github.aleixrodriala.quadern.insights

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One unique queue for titles and summaries, separate from transcription so neither waits on the other. */
class InsightsScheduler(private val context: Context) {
    /**
     * Queues a run. [now] skips a pending backoff (the user asked, or the app was opened) but never
     * interrupts a run that's already talking to the service.
     */
    suspend fun enqueue(now: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        val policy = if (now && !isRunning(wm)) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        val request = OneTimeWorkRequestBuilder<InsightsWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        wm.enqueueUniqueWork(UNIQUE_NAME, policy, request)
    }

    private suspend fun isRunning(wm: WorkManager): Boolean = withContext(Dispatchers.IO) {
        runCatching { wm.getWorkInfosForUniqueWork(UNIQUE_NAME).get().any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
    }

    companion object {
        const val UNIQUE_NAME = "insights"
    }
}
