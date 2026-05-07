package com.meatsack.motivator.mobile.ai

import android.util.Log
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.Model
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ClaudeApiClient(private val apiKeyStore: ApiKeyStore) {

    // Anthropic SDK clients hold an OkHttp connection pool, dispatcher, and TLS
    // state — building one per call wastes ~50ms of handshake and leaks sockets
    // under any per-trigger use. Cache by current key; rebuild only when the
    // user replaces their key.
    @Volatile private var cachedClient: AnthropicClient? = null

    @Volatile private var cachedKey: String? = null

    private fun clientFor(key: String): AnthropicClient {
        cachedClient?.takeIf { cachedKey == key }?.let { return it }
        return synchronized(this) {
            cachedClient?.takeIf { cachedKey == key } ?: AnthropicOkHttpClient.builder()
                .apiKey(key)
                .build()
                .also {
                    cachedClient = it
                    cachedKey = key
                }
        }
    }

    suspend fun generate(
        systemPrompt: String,
        userPrompt: String,
        model: String = "claude-haiku-4-5-20251001",
        maxTokens: Int = 1024,
    ): GenerationResult {
        val key = apiKeyStore.read() ?: return GenerationResult.NoApiKey

        return withContext(Dispatchers.IO) {
            try {
                val client = clientFor(key)

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
            } catch (e: AnthropicServiceException) {
                // HTTP-level error returned by Anthropic — auth (401), rate-limit
                // (429), bad request (400), etc. Carry the status so the UI can
                // give the user something actionable instead of "Error: null".
                val body = runCatching { e.body().toString() }.getOrNull()
                Log.w(TAG, "Anthropic returned HTTP ${e.statusCode()}", e)
                GenerationResult.HttpError(e.statusCode(), body)
            } catch (e: AnthropicIoException) {
                // Network failure (no connection, DNS, TLS, timeout).
                Log.w(TAG, "Network error calling Anthropic", e)
                GenerationResult.Failed(e)
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
