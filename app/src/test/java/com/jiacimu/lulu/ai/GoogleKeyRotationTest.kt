package com.jiacimu.lulu.ai

import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GoogleKeyRotationTest {
    private val channel = UUID.randomUUID().toString()

    @Test fun requestTimeRotationSupportsMoreThanSixKeysAndIgnoresDuplicates() = runBlocking {
        val keys = (1..11).map { "fixture-$it" }
        val extras = keys.drop(1) + listOf("", " fixture-1 ")
        val requested = (0..keys.size).map {
            GoogleKeyRotation.request(channel, keys.first(), extras) { it }
        }
        assertEquals(keys + keys.first(), requested)
        assertEquals("fixture-1", GoogleKeyRotation.request("other-$channel", keys.first(), extras) { it })
    }

    @Test fun onlyExplicitKeyRejectionsTryTheNextKeyAndReserveEachAdditionalAttempt() = runBlocking {
        val events = mutableListOf<String>()
        val response = GoogleKeyRotation.request(channel, "a", listOf("b", "c"),
            beforeRetry = { events += "reserve" }) { key ->
            events += key
            when (key) {
                "a" -> throw ModelHttpException(429, "quota")
                "b" -> throw ModelHttpException(400, "invalid key", providerReason = "API_KEY_INVALID")
                else -> "success"
            }
        }
        assertEquals("success", response)
        assertEquals(listOf("a", "reserve", "b", "reserve", "c"), events)
    }

    @Test fun exhaustionStopsAfterEachUniqueKeyExactlyOnce() = runBlocking {
        val tried = mutableListOf<String>()
        val error = runCatching {
            GoogleKeyRotation.request(channel, "a", listOf("b", "a", " b ")) { key ->
                tried += key
                throw ModelHttpException(429, "quota")
            }
        }.exceptionOrNull() as ModelHttpException
        assertEquals(listOf("a", "b"), tried)
        assertEquals(429, error.status)
        assertTrue(error.keyRotationExhausted)
    }

    @Test fun disabledSingleKeyAndAmbiguousFailuresDoNotReplay() = runBlocking {
        val failures = listOf(
            ModelHttpException(403, "model permission"),
            ModelHttpException(503, "unavailable"),
            SocketTimeoutException("response timed out"),
            CancellationException("cancelled"),
        )
        val calls = mutableListOf<String>()
        for (failure in failures) {
            calls.clear()
            val error = runCatching {
                GoogleKeyRotation.request("${failure.message}-$channel", "a", listOf("b")) {
                    calls += it
                    throw failure
                }
            }.exceptionOrNull()
            assertSame(failure, error)
            assertEquals(listOf("a"), calls)
        }
        for ((id, extras) in listOf(null to listOf("b", "c"), channel to emptyList())) {
            calls.clear()
            val error = runCatching {
                GoogleKeyRotation.request(id, "a", extras) {
                    calls += it
                    throw ModelHttpException(429, "quota")
                }
            }.exceptionOrNull() as ModelHttpException
            assertFalse(error.keyRotationExhausted)
            assertEquals(listOf("a"), calls)
        }
    }
}
