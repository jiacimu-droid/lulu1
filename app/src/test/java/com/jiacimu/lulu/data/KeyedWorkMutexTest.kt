package com.jiacimu.lulu.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class KeyedWorkMutexTest {
    @Test fun slowPersonCannotBlockAnotherPersonAndSameOwnerRemainsSerial() = runBlocking {
        val locks = KeyedWorkMutex()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("a") { entered.complete(Unit); release.await() }
        }
        entered.await()
        var sameOwnerEntered = false
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("a") { sameOwnerEntered = true }
        }
        assertFalse(sameOwnerEntered)
        assertEquals("b-result", withTimeout(1_000) {
            async { locks.withLock("b") { "b-result" } }.await()
        })
        // Cancelling a waiter must not retire the still-held owner's lock.
        waiter.cancel(); waiter.join()
        val next = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("a") { sameOwnerEntered = true }
        }
        assertFalse(sameOwnerEntered)
        release.complete(Unit)
        first.join(); next.join()
        assertTrue(sameOwnerEntered)
        assertEquals(3, locks.withLock("a") { 3 })
    }
}
