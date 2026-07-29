package com.jiacimu.lulu

import androidx.compose.runtime.Composable

/** Keeps call sites concise without depending on wildcard imports from the saveable subpackage. */
@Composable
internal fun <T : Any> rememberSaveable(init: () -> T): T =
    androidx.compose.runtime.saveable.rememberSaveable(init = init)

@Composable
internal fun <T : Any> rememberSaveable(vararg inputs: Any?, init: () -> T): T =
    androidx.compose.runtime.saveable.rememberSaveable(*inputs, init = init)
