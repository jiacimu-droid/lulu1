package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.ModelHttpException
import com.jiacimu.lulu.ai.ModelPostTransport
import com.jiacimu.lulu.ai.ModelPostTransportException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateChatModelFailurePolicyTest {
    @Test fun responseTimeoutAfterUploadCannotTriggerOuterPaidReplay() {
        val failure = ModelPostTransportException(
            ModelPostTransport.Phase.READ_RESPONSE, SocketTimeoutException("timed out"),
        )
        assertTrue(PrivateChatModelFailurePolicy.awaitNewUserInput(failure))
    }

    @Test fun interruptedUploadCannotBeReplayedEither() {
        val failure = ModelPostTransportException(
            ModelPostTransport.Phase.WRITE_BODY, SocketTimeoutException("write timed out"),
        )
        assertTrue(PrivateChatModelFailurePolicy.awaitNewUserInput(failure))
    }

    @Test fun preBodyFailureAlreadyExhaustedTransportsSafeRetry() {
        val failure = ModelPostTransportException(
            ModelPostTransport.Phase.CONNECT, SocketTimeoutException("connect timed out"), 1,
        )
        assertTrue(PrivateChatModelFailurePolicy.awaitNewUserInput(failure))
    }

    @Test fun providerRejectionAndAmbiguousServerFailureDoNotLoop() {
        assertTrue(PrivateChatModelFailurePolicy.awaitNewUserInput(ModelHttpException(400, "bad request")))
        assertTrue(PrivateChatModelFailurePolicy.awaitNewUserInput(ModelHttpException(503, "gateway error")))
        assertTrue(PrivateChatModelFailurePolicy.awaitNewUserInput(ModelHttpException(429, "limited")))
        assertFalse(PrivateChatModelFailurePolicy.awaitNewUserInput(IllegalArgumentException("local state error")))
    }
}
