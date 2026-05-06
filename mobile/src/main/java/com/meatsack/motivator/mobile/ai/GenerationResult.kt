package com.meatsack.motivator.mobile.ai

sealed class GenerationResult {
    data class Success(val messages: List<String>) : GenerationResult()
    data object NoApiKey : GenerationResult()
    data class HttpError(val status: Int, val body: String?) : GenerationResult()
    data class Failed(val error: Throwable) : GenerationResult()
}
