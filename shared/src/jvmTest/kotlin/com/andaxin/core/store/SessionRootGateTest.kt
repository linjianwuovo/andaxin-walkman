package com.andaxin.core.store

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class SessionRootGateTest {
    @Test fun delayedInitialEmissionReadsTheCompletedGuestWrite() = runBlocking {
        var current = false
        val roots = mutableListOf<Boolean>()
        val gate = SessionRootGate({ current }, { current = true })
        gate.enterGuest { roots += true }
        gate.resolve { roots += it }
        assertEquals(listOf(true, true), roots)
    }

    @Test fun inFlightOldReadFinishesBeforeGuestEntry() = runBlocking {
        withTimeout(5_000) {
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            var current = false
            val roots = mutableListOf<Boolean>()
            val gate = SessionRootGate(
                readCurrent = {
                    val snapshot = current
                    readStarted.complete(Unit)
                    releaseRead.await()
                    snapshot
                },
                writeGuest = { current = true },
            )
            val reader = launch(start = CoroutineStart.UNDISPATCHED) { gate.resolve { roots += it } }
            readStarted.await()
            val writer = launch(start = CoroutineStart.UNDISPATCHED) { gate.enterGuest { roots += true } }
            assertFalse(current)
            releaseRead.complete(Unit)
            reader.join()
            writer.join()
            assertEquals(listOf(false, true), roots)
        }
    }

    @Test fun queuedReadWaitsForGuestWriteAndCallback() = runBlocking {
        withTimeout(5_000) {
            val writeStarted = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            var current = false
            var reads = 0
            val roots = mutableListOf<Boolean>()
            val gate = SessionRootGate(
                readCurrent = { reads++; current },
                writeGuest = {
                    writeStarted.complete(Unit)
                    releaseWrite.await()
                    current = true
                },
            )
            val writer = launch(start = CoroutineStart.UNDISPATCHED) { gate.enterGuest { roots += true } }
            writeStarted.await()
            val reader = launch(start = CoroutineStart.UNDISPATCHED) { gate.resolve { roots += it } }
            assertEquals(0, reads)
            releaseWrite.complete(Unit)
            writer.join()
            reader.join()
            assertEquals(listOf(true, true), roots)
        }
    }

    @Test fun currentSignedOutStateCanStillReturnToLogin() = runBlocking {
        var current = false
        val roots = mutableListOf<Boolean>()
        val gate = SessionRootGate({ current }, { current = true })
        gate.enterGuest { roots += true }
        current = false
        gate.resolve { roots += it }
        assertEquals(listOf(true, false), roots)
    }

    @Test fun failedGuestWriteDoesNotEnterTabsOrLeaveTheGateLocked() = runBlocking {
        withTimeout(5_000) {
            var entered = false
            val gate = SessionRootGate({ false }, { error("guest write failed") })
            val failure = runCatching { gate.enterGuest { entered = true } }.exceptionOrNull()
            assertIs<IllegalStateException>(failure)
            assertFalse(entered)
            gate.resolve { assertFalse(it) }
        }
    }
}
