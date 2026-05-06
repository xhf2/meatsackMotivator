package com.meatsack.motivator.mobile.ai

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClaudeApiClient(private val apiKeyStore: ApiKeyStore) {

    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        model: String = "claude-haiku-4-5-20251001",
        maxTokens: Int = 1024,
    ): GenerationResult {
        val key = apiKeyStore.read() ?: return GenerationResult.NoApiKey

        return withContext(Dispatchers.IO) {
            try {
                val client: AnthropicClient = AnthropicOkHttpClient.builder()
                    .apiKey(key)
                    .build()

                val params = MessageCreateParams.builder()
                    .model(Model.of(model))
                    .maxTokens(maxTokens.toLong())
                    .system(systemPrompt)
                    .addUserMessage(userPrompt)
                    .build()

                val response = client.messages().create(params)
                val text = response.content()
                    .mapNotNull { it.text().orElse(null)?.text() }
                    .joinToString("\n")

                val messages = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()

                GenerationResult.Success(messages)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "Claude API call failed", t)
                GenerationResult.Failed(t)
            }
        }
    }

    companion object {
        private const val TAG = "ClaudeApiClient"
    }
}
