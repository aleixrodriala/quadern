package io.github.aleixrodriala.quadern

import io.github.aleixrodriala.quadern.data.PendingDeletes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PendingDeletesTest {
    private val deleted = mutableListOf<String>()

    private fun TestScope.pending(fail: Boolean = false) = PendingDeletes(backgroundScope) {
        if (fail) error("disk full")
        deleted += it
    }

    @Test fun hidesAtOnceAndDeletesAfterTheWindow() = runTest {
        val p = pending()
        p.remove("a", 5_000)
        assertEquals(setOf("a"), p.hidden.value)
        assertEquals("a", p.undoable.value?.id)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(emptyList<String>(), deleted)
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("a"), deleted)
        assertNull(p.undoable.value)
        // Stays hidden: the list may still hold it for a moment.
        assertEquals(setOf("a"), p.hidden.value)
    }

    @Test fun undoBringsItBackAndNeverDeletes() = runTest {
        val p = pending()
        p.remove("a", 5_000)
        assertEquals("a", p.undo())
        assertEquals(emptySet<String>(), p.hidden.value)
        assertNull(p.undo())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(emptyList<String>(), deleted)
    }

    @Test fun anotherRemovalFinishesThePreviousOne() = runTest {
        val p = pending()
        p.remove("a", 5_000)
        advanceTimeBy(3_000)
        p.remove("b", 5_000)
        runCurrent()
        assertEquals(listOf("a"), deleted)
        assertEquals(setOf("a", "b"), p.hidden.value)
        // The window starts again for the new one.
        advanceTimeBy(4_000); runCurrent()
        assertEquals(listOf("a"), deleted)
        assertEquals("b", p.undo())
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("a"), deleted)
        assertEquals(setOf("a"), p.hidden.value)
    }

    @Test fun aFailedDeleteShowsTheNoteAgain() = runTest {
        val p = pending(fail = true)
        p.remove("a", 5_000)
        advanceTimeBy(5_001); runCurrent()
        assertEquals(emptySet<String>(), p.hidden.value)
    }
}
