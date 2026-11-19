package com.jiacimu.lulu

import android.content.ActivityNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A missing speech-recognition Activity must not terminate the chat process. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class QqChatSpeechLaunchTest {
    @Test fun recognizerAvailableLaunchesNormally() {
        var launched = false
        assertTrue(launchQqSpeechRecognition { launched = true })
        assertTrue(launched)
    }

    @Test fun noSpeechRecognitionActivityReturnsFailureInsteadOfCrashing() {
        assertFalse(launchQqSpeechRecognition {
            throw ActivityNotFoundException("No Activity found to handle RECOGNIZE_SPEECH")
        })
    }

    @Test fun blockedSpeechRecognitionActivityAlsoReturnsFailure() {
        assertFalse(launchQqSpeechRecognition {
            throw SecurityException("Speech activity is not exported")
        })
    }

    @Test(expected = IllegalStateException::class)
    fun unrelatedProgrammingErrorsAreNotHidden() {
        launchQqSpeechRecognition { throw IllegalStateException("unexpected launcher lifecycle") }
    }
}
