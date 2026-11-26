package com.andaxin.core.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keep root callbacks ordered with guest persistence, including a late initial flow emission. */
internal class SessionRootGate(
    private val readCurrent: suspend () -> Boolean,
    private val writeGuest: suspend () -> Unit,
) {
    private val mutex = Mutex()

    suspend fun resolve(onResolved: (Boolean) -> Unit) = mutex.withLock {
        onResolved(readCurrent())
    }

    suspend fun enterGuest(onEntered: () -> Unit) = mutex.withLock {
        writeGuest()
        onEntered()
    }
}
