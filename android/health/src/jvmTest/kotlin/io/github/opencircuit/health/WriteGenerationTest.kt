package io.github.opencircuit.health

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class WriteGenerationTest {

    /** A store that outlives the [WriteGeneration]s built over it, as the database outlives a launch. */
    private class MemoryStore(var value: Long? = null, var failSave: Boolean = false) : WriteGenerationStore {
        override suspend fun load(): Long? = value
        override suspend fun save(generation: Long) {
            if (failSave) throw IllegalStateException("disk full")
            value = generation
        }
    }

    @Test
    fun theFirstGenerationIsOneAndEachNextIsOneHigher() = runTest {
        val store = MemoryStore()
        val generation = WriteGeneration(store)
        assertEquals(listOf(1L, 2L, 3L), List(3) { generation.next() })
        assertEquals(3L, store.value)
    }

    @Test
    fun aRelaunchContinuesFromTheStoredGeneration() = runTest {
        val store = MemoryStore()
        WriteGeneration(store).next()
        WriteGeneration(store).next()
        // A third launch over the same store.
        assertEquals(3L, WriteGeneration(store).next())
        assertEquals(42L, WriteGeneration(MemoryStore(41)).next())
    }

    @Test
    fun aGenerationIsSavedBeforeItIsHandedOut() = runTest {
        val store = MemoryStore(value = 7, failSave = true)
        assertFailsWith<IllegalStateException> { WriteGeneration(store).next() }
        // Nothing handed out, nothing stored: the next try still gets 8, never a reused 7.
        store.failSave = false
        assertEquals(8L, WriteGeneration(store).next())
    }

    @Test
    fun concurrentCallsNeverShareAGeneration() = runTest {
        val generation = WriteGeneration(MemoryStore())
        val got = List(50) { async { generation.next() } }.awaitAll()
        assertEquals((1L..50L).toList(), got.sorted())
    }

    @Test
    fun aDamagedStoredValueIsRefusedNotRestartedFromOne() = runTest {
        // Restarting at 1 would make every later write lose to the records already stored.
        for (bad in listOf(0L, -5L, Long.MAX_VALUE)) {
            val store = MemoryStore(bad)
            assertFailsWith<IllegalStateException>("stored $bad") { WriteGeneration(store).next() }
            assertEquals(bad, store.value, "stored $bad must be left as found")
        }
    }

    @Test
    fun nothingStoredMeansNothingHandedOutYet() = runTest {
        val store = MemoryStore()
        WriteGeneration(store) // building it reads and writes nothing
        assertNull(store.value)
    }
}
