package com.jiacimu.lulu.study

import androidx.compose.runtime.Composable

@Composable
internal fun <T : Any> rememberSaveable(init: () -> T): T =
    androidx.compose.runtime.saveable.rememberSaveable(init = init)
