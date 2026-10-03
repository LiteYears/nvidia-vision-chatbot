package com.example.data.remote

import android.util.Log
import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class NvidiaModelItem(
    val id: String,
    val ownedBy: String,
    val created: Long = 0
)

class NvidiaApiClient(
    private val getApiKey: () -> String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    companion object {
        private const val TAG = "NvidiaApiClient"
        const val BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val MODELS_URL = "https://integrate.api.nvidia.com/v1/models"
        const val DEFAULT_MODEL = "meta/llama-3.2-11b-vision-instruct"
        const val VISION_MODEL = "meta/llama-3.2-11b-vision-instruct"
        const val QUANTUM_MODEL = "Quantum 3"
        private const val MAX_NETWORK_RETRIES = 1

        val FALLBACK_CANDIDATES = listOf(
            "meta/llama-3.2-11b-vision-instruct",
            "openai/gpt-oss-20b",
            "meta/muse-glimmer-30b",
            "google/diffusiongemma-26b-a4b-it",
            "nvidia/nemotron-3-ultra-550b-a55b"
        )
    }

    /**
     * Dynamically queries https://integrate.api.nvidia.com/v1/models (build.nvidia.com/models)
     * to fetch all active available models.
     */
    suspend fun fetchAvailableModels(): Result<List<NvidiaModelItem>> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey().trim().trim('.', ',', ';', ':', '"', '\'', '`', ' ')
        val requestBuilder = Request.Builder()
            .url(MODELS_URL)
            .get()
            .addHeader("Accept", "application/json")

        if (apiKey.isNotBlank() && !apiKey.contains("placeholder", ignoreCase = true)) {
            requestBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        NvidiaApiException(response.code, "Failed to fetch models from NVIDIA: HTTP ${response.code}")
                    )
                }
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: return@withContext Result.success(emptyList())
                val list = mutableListOf<NvidiaModelItem>()
                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    val owner = item.optString("owned_by", "")
                    val created = item.optLong("created", 0)
                    if (id.isNotBlank()) {
                        list.add(NvidiaModelItem(id = id, ownedBy = owner, created = created))
                    }
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching models from $MODELS_URL", e)
            Result.failure(e)
        }
    }

    suspend fun sendChatCompletion(
        messages: List<ChatMessage>,
        model: String = DEFAULT_MODEL,
        systemPrompt: String? = null,
        temperature: Double = 0.7,
        topP: Double = 0.95,
        maxTokens: Int = 4096
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey().trim().trim('.', ',', ';', ':', '"', '\'', '`', ' ')
        if (apiKey.isEmpty() || apiKey.contains("placeholder", ignoreCase = true)) {
            return@withContext Result.failure(
                NvidiaApiException(
                    code = 401,
                    message = "NVIDIA API Key not configured. Please enter your API key in Developer Settings."
                )
            )
        }

        // Determine if there are image attachments in the message list
        val hasImage = messages.any { !it.imageBase64.isNullOrBlank() }

        // Resolve requested model - smoothly auto-migrate any deprecated/EOL or restricted models
        val targetModel = when {
            hasImage -> {
                if (model.contains("vision", ignoreCase = true) || model.contains("vila", ignoreCase = true)) model else VISION_MODEL
            }
            model.equals(QUANTUM_MODEL, ignoreCase = true) ||
                    model.contains("Quantum", ignoreCase = true) -> DEFAULT_MODEL
            model.isBlank() -> DEFAULT_MODEL
            model.contains("llama-3.2-3b", ignoreCase = true) ||
                    model.contains("llama-3.2-1b", ignoreCase = true) ||
                    model.contains("nemotron-70b", ignoreCase = true) -> DEFAULT_MODEL
            else -> model.trim()
        }

        // Attempt API call with targetModel with network retry
        var lastResult: Result<String>? = null
        for (attempt in 0..MAX_NETWORK_RETRIES) {
            val result = executeRequest(apiKey, targetModel, messages, systemPrompt, temperature, topP, maxTokens)
            if (result.isSuccess) {
                return@withContext result
            }
            lastResult = result
            val exception = result.exceptionOrNull()
            // If it's an authentication error (bad API key), don't retry or fallback
            if (exception is NvidiaApiException && (exception.code == 401 || (exception.code == 403 && exception.message?.contains("Authorization failed", ignoreCase = true) == true))) {
                return@withContext result
            }
            // If it's a client error (e.g. 404 model not found, not found for account, 400 bad request, 422), stop retrying same model
            if (exception is NvidiaApiException && (exception.code in 400..404 || exception.message?.contains("Not found for account", ignoreCase = true) == true)) {
                break
            }
            if (attempt < MAX_NETWORK_RETRIES) {
                Log.w(TAG, "Request to $targetModel failed on attempt ${attempt + 1}, retrying in 750ms... Error: ${exception?.message}")
                delay(750L * (attempt + 1))
            }
        }

        val primaryErrorMsg = lastResult?.exceptionOrNull()?.message ?: "Request failed for model '$targetModel'"
        val isAccountOrFunctionError = primaryErrorMsg.contains("Not found for account", ignoreCase = true) ||
                (lastResult?.exceptionOrNull() as? NvidiaApiException)?.code == 404

        // If targetModel is already one of the candidates, filter it out from fallback attempts
        val fallbackCandidates = FALLBACK_CANDIDATES.filter { !it.equals(targetModel, ignoreCase = true) }

        delay(500L)

        // Attempt intelligent fallback across verified active public models
        for (candidate in fallbackCandidates) {
            Log.w(TAG, "Primary model '$targetModel' failed ($primaryErrorMsg). Retrying with fallback: $candidate")
            val fallbackAttempt = executeRequest(apiKey, candidate, messages, systemPrompt, temperature, topP, maxTokens)
            if (fallbackAttempt.isSuccess) {
                val successfulReply = fallbackAttempt.getOrNull() ?: ""
                val adjustedReply = if (isAccountOrFunctionError) {
                    "[Note: '$targetModel' is not provisioned for your NVIDIA account on build.nvidia.com. Answer generated via '$candidate']\n\n$successfulReply"
                } else {
                    successfulReply
                }
                return@withContext Result.success(adjustedReply)
            }
        }

        // Both primary and all fallbacks failed: report informative error message
        val finalErrorMessage = when {
            isAccountOrFunctionError ->
                "Model '$targetModel' is not provisioned for your NVIDIA Developer account (Function not found for account). Please select an active public model like Llama 3.2 Vision, Gemma 3, or Mistral from the model selector."
            lastResult?.exceptionOrNull() is NvidiaApiException ->
                "Error with model '$targetModel': $primaryErrorMsg"
            else -> primaryErrorMsg
        }

        return@withContext Result.failure(
            NvidiaApiException(
                code = (lastResult?.exceptionOrNull() as? NvidiaApiException)?.code ?: 0,
                message = finalErrorMessage
            )
        )
    }

    private fun executeRequest(
        apiKey: String,
        resolvedModel: String,
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        temperature: Double = 0.7,
        topP: Double = 0.95,
        maxTokens: Int = 4096
    ): Result<String> {
        val isDeepSeekR1 = resolvedModel.contains("deepseek-r1", ignoreCase = true)

        return try {
            val jsonBody = JSONObject().apply {
                put("model", resolvedModel)
                // For DeepSeek R1, temperature should be moderate (0.6 recommended, max 1.0)
                val safeTemp = if (isDeepSeekR1) temperature.coerceIn(0.0, 1.0) else temperature.coerceIn(0.0, 1.5)
                put("temperature", safeTemp)
                put("top_p", topP.coerceIn(0.05, 1.0))
                put("max_tokens", maxTokens.coerceIn(256, 8192))
                put("stream", false)

                val messagesArray = JSONArray()

                // Insert custom system prompt if provided and not already present
                // Note: DeepSeek R1 on NVIDIA NIM rejects {"role": "system"}; we prepend it to the first user message instead
                val hasSystemMessageInList = messages.any { it.role == MessageRole.SYSTEM }
                if (!systemPrompt.isNullOrBlank() && !hasSystemMessageInList && !isDeepSeekR1) {
                    messagesArray.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt.trim())
                    })
                }

                // Normalize messages: coalesce consecutive text messages of the same role to prevent API 400 errors
                val normalizedMessages = mutableListOf<ChatMessage>()
                for (msg in messages) {
                    val last = normalizedMessages.lastOrNull()
                    if (last != null && last.role == msg.role && last.imageBase64.isNullOrBlank() && msg.imageBase64.isNullOrBlank() && msg.role != MessageRole.SYSTEM) {
                        val mergedContent = "${last.content}\n\n${msg.content}".trim()
                        normalizedMessages[normalizedMessages.size - 1] = last.copy(content = mergedContent)
                    } else {
                        normalizedMessages.add(msg)
                    }
                }

                for ((index, msg) in normalizedMessages.withIndex()) {
                    when (msg.role) {
                        MessageRole.USER -> {
                            val userMsgObj = JSONObject().apply {
                                put("role", "user")
                                val textContent = if (isDeepSeekR1 && index == 0 && !systemPrompt.isNullOrBlank()) {
                                    "[System Instructions: ${systemPrompt.trim()}]\n\n${msg.content.ifBlank { "Hello" }}"
                                } else {
                                    msg.content.ifBlank { "Hello" }
                                }

                                if (!msg.imageBase64.isNullOrBlank()) {
                                    // Multimodal format for vision-capable models
                                    val contentParts = JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", textContent)
                                        })
                                        put(JSONObject().apply {
                                            put("type", "image_url")
                                            val imgObj = JSONObject().apply {
                                                put("url", "data:image/jpeg;base64,${msg.imageBase64}")
                                            }
                                            put("image_url", imgObj)
                                        })
                                    }
                                    put("content", contentParts)
                                } else {
                                    // Pure text format: simple String (avoids 422 errors on standard LLMs)
                                    put("content", textContent)
                                }
                            }
                            messagesArray.put(userMsgObj)
                        }
                        MessageRole.ASSISTANT -> {
                            if (msg.content.isNotBlank()) {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", msg.content)
                                })
                            }
                        }
                        MessageRole.SYSTEM -> {
                            if (msg.content.isNotBlank() && !isDeepSeekR1) {
                                messagesArray.put(JSONObject().apply {
                                    put("role", "system")
                                    put("content", msg.content)
                                })
                            }
                        }
                    }
                }

                put("messages", messagesArray)
            }

            val requestBody = jsonBody.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            Log.d(TAG, "Calling NVIDIA NIM API: model=$resolvedModel, messagesCount=${messages.size}")

            val request = Request.Builder()
                .url(BASE_URL)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "API Error: ${response.code} -> $responseBody")
                    val errorMsg = try {
                        val errJson = JSONObject(responseBody)
                        val detail = errJson.opt("detail")
                        when (detail) {
                            is JSONArray -> {
                                if (detail.length() > 0) {
                                    detail.getJSONObject(0).optString("msg", "Validation error")
                                } else "Unknown validation error"
                            }
                            is String -> detail
                            else -> {
                                errJson.optJSONObject("error")?.optString("message")
                                    ?: errJson.optString("title", "API returned status ${response.code}")
                            }
                        }
                    } catch (_: Exception) {
                        "Request failed with HTTP ${response.code}: $responseBody"
                    }

                    return Result.failure(
                        NvidiaApiException(response.code, errorMsg)
                    )
                }

                val responseJson = JSONObject(responseBody)
                val choices = responseJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val firstChoice = choices.getJSONObject(0)
                    val messageObj = firstChoice.optJSONObject("message")
                    val textContent = messageObj?.optString("content", "") ?: ""
                    val reply = if (textContent.isNotBlank()) {
                        textContent
                    } else {
                        messageObj?.optString("reasoning_content")?.takeIf { it.isNotBlank() }
                            ?: firstChoice.optString("text", "No response generated.")
                    }
                    Log.d(TAG, "Received successful response (${reply.length} chars) from $resolvedModel")
                    Result.success(reply)
                } else {
                    Result.failure(
                        NvidiaApiException(200, "Empty response choices received from NVIDIA API.")
                    )
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network failure calling NVIDIA API for model $resolvedModel", e)
            val msg = if (e is java.net.UnknownHostException || e.message?.contains("Unable to resolve host", ignoreCase = true) == true) {
                "Network connection error: Unable to reach integrate.api.nvidia.com. Please check your device internet connection and tap retry."
            } else {
                "Network connection error: ${e.localizedMessage ?: "Unable to reach NVIDIA API server."}"
            }
            Result.failure(
                NvidiaApiException(0, msg)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in sendChatCompletion for model $resolvedModel", e)
            Result.failure(e)
        }
    }
}

class NvidiaApiException(val code: Int, message: String) : Exception(message)
