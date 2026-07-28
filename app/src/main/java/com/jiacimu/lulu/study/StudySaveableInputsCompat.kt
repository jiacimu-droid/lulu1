package com.jiacimu.lulu.study

import androidx.compose.runtime.Composable

@Composable
internal fun <T : Any> rememberSaveable(
    vararg inputs: Any?,
    init: () -> T,
): T = androidx.compose.runtime.saveable.rememberSaveable(
    *inputs,
    init = init,
)
