package com.moronigranja.localttsreader.locate

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * CR-3/A3: the single serialization point for index MUTATION — import
 * publish, delete removal, and the launch-time rebuild all reconcile under
 * this lock, so no stale snapshot can purge a book the Room store already
 * committed, and no durable write can land after its index entry was
 * removed. Room itself is written outside the lock (durable truth); the
 * derived index is only ever touched inside it.
 *
 * The lock is a plain coroutine [Mutex]; guards are reentrant-free, so a
 * caller must not hold it across another indexed operation.
 */
class IndexLock {
    private val mutex = Mutex()

    suspend fun <T> withExclusiveIndex(block: suspend () -> T): T = mutex.withLock { block() }
}
