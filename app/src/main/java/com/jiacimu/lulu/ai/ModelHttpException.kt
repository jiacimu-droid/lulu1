package com.jiacimu.lulu.ai

internal class ModelHttpException(
    val status: Int,
    message: String,
    val providerCode: String = "",
    val providerReason: String = "",
    val keyRotationExhausted: Boolean = false,
) : IllegalStateException(message)
