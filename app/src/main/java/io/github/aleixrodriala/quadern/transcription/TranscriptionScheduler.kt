package io.github.aleixrodriala.quadern.transcription

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.aleixrodriala.quadern.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * One unique WorkManager queue drains every pending chunk. WorkManager persists it across process
 * death and reboots, waits for connectivity, and backs off exponentially on failures.
 */
class TranscriptionScheduler(private val context: Context, private val settings: SettingsRepository) {
    /**
     * [now] = the user asked for it (retry button, app opened): replace whatever is queued, including
     * a run sleeping in backoff, and start right away. A run that's already working is never replaced:
     * cancelling it mid-cut left two runs cutting the same chunk at once, clobbering each other's file.
     */
    suspend fun enqueue(now: Boolean = false) {
        val s = settings.current()
        val needsNetwork = s.provider.needsNetwork || s.fallback?.needsNetwork == true
        val network = when {
            !needsNetwork -> NetworkType.NOT_REQUIRED
            s.wifiOnly -> NetworkType.UNMETERED
            else -> NetworkType.CONNECTED
        }
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        val wm = WorkManager.getInstance(context)
        val policy = if (now && !isRunning(wm)) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        wm.enqueueUniqueWork(UNIQUE_NAME, policy, request)
    }

    private suspend fun isRunning(wm: WorkManager): Boolean = withContext(Dispatchers.IO) {
        runCatching { wm.getWorkInfosForUniqueWork(UNIQUE_NAME).get().any { it.state == WorkInfo.State.RUNNING } }.getOrDefault(false)
    }

    companion object {
        const val UNIQUE_NAME = "transcription"
        const val TAG = "transcription"
    }
}
