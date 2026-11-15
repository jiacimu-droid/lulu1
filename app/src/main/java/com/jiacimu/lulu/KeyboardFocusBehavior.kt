package com.jiacimu.lulu

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay

/**
 * Once the user taps a text input, keep it within the resized viewport.
 * Passive page navigation never requests text focus or summons a keyboard.
 */
@Composable
internal fun Modifier.keepFocusedFieldVisible(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // IME bottom changes on every animation frame. Keying the effect to its
    // pixel height repeatedly cancels the delay and schedules a new scroll,
    // causing unnecessary layout work while the keyboard opens or closes.
    // Only the visibility transition (or focus change) needs a new request.
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(hasFocus, keyboardVisible) {
        if (hasFocus) {
            delay(90L)
            requester.bringIntoView()
        }
    }
    return this.bringIntoViewRequester(requester).onFocusChanged { hasFocus = it.hasFocus }
}
