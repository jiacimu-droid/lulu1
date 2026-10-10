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
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(hasFocus, imeBottom) {
        if (hasFocus) {
            delay(90L)
            requester.bringIntoView()
        }
    }
    return this.bringIntoViewRequester(requester).onFocusChanged { hasFocus = it.hasFocus }
}
