package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.ModelHttpException
import com.jiacimu.lulu.ai.ModelPostTransportException

/**
 * The transport's own no-replay guarantee is not enough if the outer Android
 * WorkManager or foreground reader submits the same possibly billed turn.
 * Transport failures already exhausted their safe pre-body retry.
 */
internal object PrivateChatModelFailurePolicy {
    fun awaitNewUserInput(error: Throwable): Boolean =
        error is ModelPostTransportException || error is ModelHttpException
}
