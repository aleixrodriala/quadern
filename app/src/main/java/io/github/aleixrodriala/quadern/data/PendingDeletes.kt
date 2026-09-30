package io.github.aleixrodriala.quadern.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Notes swiped off the list: hidden at once, deleted for good only once the moment to undo them
 * has passed. If the process dies before that the note simply stays, the safe way to fail.
 *
 * Only the latest one can be undone; removing another finishes the previous one. Call from one
 * thread ([scope] should run there too): the main thread in the app.
 */
class PendingDeletes(private val scope: CoroutineScope, private val delete: suspend (String) -> Unit) {
    data class Undoable(val id: String, val serial: Long)

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Notes the list shouldn't show. Deleted ones stay in it: dropping them the moment the delete
     * returns could show a stale query result with the note in it for a frame.
     */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _undoable = MutableStateFlow<Undoable?>(null)

    /** What Undo would bring back, if anything. */
    val undoable: StateFlow<Undoable?> = _undoable.asStateFlow()

    private var timer: Job? = null
    private var serial = 0L

    fun remove(id: String, undoWindowMs: Long) {
        _undoable.value?.let { if (it.id != id) commit(it.id) }
        _hidden.update { it + id }
        _undoable.value = Undoable(id, ++serial)
        timer?.cancel()
        timer = scope.launch {
            delay(undoWindowMs)
            commit(id)
        }
    }

    /** Brings the latest removed note back; returns its id, or null if it's too late. */
    fun undo(): String? {
        val u = _undoable.value ?: return null
        timer?.cancel()
        _undoable.value = null
        _hidden.update { it - u.id }
        return u.id
    }

    private fun commit(id: String) {
        if (_undoable.value?.id == id) _undoable.value = null
        scope.launch {
            runCatching { delete(id) }.onFailure {
                Log.w("PendingDeletes", "Couldn't delete $id", it)
                // Still there: better to show it again than to pretend it's gone.
                _hidden.update { h -> h - id }
            }
        }
    }
}
