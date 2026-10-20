package com.jiacimu.lulu

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared focus treatment for every 自序 text editor.
 *
 * The parent editor must be vertically scrollable and IME-inset-aware.
 * Wait for the soft keyboard animation before requesting visibility so a
 * focused field lower in a long form does not disappear beneath the IME.
 * No user text or storage records are modified by this UI-only helper.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.selfPrefaceKeepFieldVisible(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this.bringIntoViewRequester(requester).onFocusChanged { focus ->
        if (focus.isFocused) scope.launch {
            delay(280)
            requester.bringIntoView()
        }
    }
}
