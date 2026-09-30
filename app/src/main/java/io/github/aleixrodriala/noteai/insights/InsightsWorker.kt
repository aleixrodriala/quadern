package io.github.aleixrodriala.noteai.insights

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.aleixrodriala.noteai.NoteApp
import io.github.aleixrodriala.noteai.data.InsightsStatus
import io.github.aleixrodriala.noteai.transcription.SttException
import io.github.aleixrodriala.noteai.util.Notifications

/**
 * Writes titles, summaries and tags for finished transcripts, newest note first. Each result is
 * saved as soon as it arrives. Failures follow the transcription rules: transient ones back off and
 * retry, missing setup parks the notes as BLOCKED until settings change, refusals mark the note FAILED.
 */
class InsightsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val container = (context.applicationContext as NoteApp).container
    private val dao = container.db.notes()

    override suspend fun doWork(): Result {
        val settings = container.settings.current()
        if (!settings.summarize) {
            dao.moveInsights(InsightsStatus.PENDING, InsightsStatus.NONE)
            container.repository.fillMissingTitles()
            return Result.success()
        }
        if (dao.notesAwaitingInsights().isEmpty()) return Result.success()

        val tags = InsightsPrompt.topTags(dao.tagStrings()).toMutableList()
        val tries = mutableMapOf<String, Int>()
        while (true) {
            // Re-read each round: notes finish transcribing, or get edited, while this runs.
            val note = dao.notesAwaitingInsights().firstOrNull { (tries[it.id] ?: 0) < MAX_TRIES_PER_RUN } ?: break
            tries[note.id] = (tries[note.id] ?: 0) + 1
            // Settings are read again for every note: a transcript must never go to a service the
            // user switched away from (or turned off) while this run was going.
            val current = container.settings.current()
            if (!current.summarize) {
                dao.moveInsights(InsightsStatus.PENDING, InsightsStatus.NONE)
                container.repository.fillMissingTitles()
                return Result.success()
            }
            val setup = setupKey(current)
            val summarizer = try {
                container.summarizers.create(current)
            } catch (e: SttException) {
                Log.i(TAG, "No summary service: ${e.message}")
                dao.moveInsights(InsightsStatus.PENDING, InsightsStatus.BLOCKED)
                container.repository.fillMissingTitles()
                return Result.success()
            }
            if (note.transcript.isBlank()) {
                dao.clearInsights(note.id, InsightsStatus.NONE)
                continue
            }
            try {
                val result = safely { summarizer.summarize(note.transcript, tags) }
                // Dropped if the transcript changed meanwhile; the loop then summarizes the new text.
                val summary = if (InsightsPrompt.wantsSummary(note.transcript)) result.summary else ""
                val saved = dao.applyInsights(note.id, note.insightsGen, note.transcript, result.title, summary, result.tags.joinToString(","))
                if (saved > 0) result.tags.forEach { if (it !in tags) tags += it }
            } catch (e: SttException) {
                val attempts = note.insightsAttempts + 1
                Log.w(TAG, "Summary for ${note.id} failed (${e.javaClass.simpleName}): ${e.message}")
                when (e) {
                    is SttException.Transient -> {
                        if (attempts >= MAX_ATTEMPTS) {
                            dao.setInsightsOutcome(note.id, note.insightsGen, note.transcript, InsightsStatus.FAILED, attempts)
                            container.repository.fillMissingTitles()
                        } else {
                            dao.setInsightsOutcome(note.id, note.insightsGen, note.transcript, InsightsStatus.PENDING, attempts)
                            return Result.retry()
                        }
                    }
                    is SttException.Auth, is SttException.NotConfigured -> {
                        // Only if the setup that failed is still the current one: the user may have
                        // just switched service or fixed the key while this request was out.
                        if (setupKey(container.settings.current()) != setup) continue
                        dao.moveInsights(InsightsStatus.PENDING, InsightsStatus.BLOCKED)
                        container.repository.fillMissingTitles()
                        return Result.success()
                    }
                    is SttException.Permanent -> dao.setInsightsOutcome(note.id, note.insightsGen, note.transcript, InsightsStatus.FAILED, attempts)
                }
            }
        }
        // Notes whose summary failed for good still deserve a title.
        container.repository.fillMissingTitles()
        // Anything still waiting (its transcript kept changing under us) gets another run.
        return if (dao.notesAwaitingInsights().isNotEmpty()) Result.retry() else Result.success()
    }

    /** Everything that decides which service and credentials a request uses. */
    private fun setupKey(s: io.github.aleixrodriala.noteai.data.AppSettings): String {
        val id = s.summarizer
        return listOf(
            id?.name, id?.let { s.chatModelFor(it) }, s.customBaseUrl,
            container.secrets.version.value, container.auth.account.value?.email,
        ).joinToString("|")
    }

    /** Anything unexpected becomes a transient failure; cancellation passes through. */
    private suspend fun <T> safely(block: suspend () -> T): T = try {
        block()
    } catch (e: SttException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Unexpected summary failure", e)
        throw SttException.Transient(e.message ?: e.javaClass.simpleName, e)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val n = Notifications.summarizing(applicationContext)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_SUMMARIZING, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_SUMMARIZING, n)
        }
    }

    private companion object {
        const val TAG = "InsightsWorker"
        const val MAX_ATTEMPTS = 8
        const val MAX_TRIES_PER_RUN = 2
    }
}
