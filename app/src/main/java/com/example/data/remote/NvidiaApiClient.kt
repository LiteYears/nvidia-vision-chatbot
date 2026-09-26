package com.example.data.remote

import android.util.Log
import com.example.data.model.ChatMessage
import com.example.data.model.MessageRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class NvidiaApiClient(
    private val getApiKey: () -> String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()


    companion object {
        private const val TAG = "NvidiaApiClient"
        const val BASE_URL = "https://integrate.api.nvidia.com/v1/chat/completions"
        const val DEFAULT_MODEL = "meta/llama-3.2-11b-vision-instruct"
        const val FALLBACK_MODEL = "meta/llama-3.2-11b-vision-instruct"
        const val QUANTUM_MODEL = "Quantum 3"
    }

    suspend fun sendChatCompletion(
        messages: List<ChatMessage>,
        model: String = DEFAULT_MODEL,
        systemPrompt: String? = null,
        temperature: Double = 0.7,
        topP: Double = 0.95,
        maxTokens: Int = 4096
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey().trim()
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

        // Resolve requested model
        val targetModel = when {
            hasImage -> {
                // If message has an image, ensure a vision-capable model is used
                if (model.contains("vision", ignoreCase = true)) model else DEFAULT_MODEL
            }
            model.equals(QUANTUM_MODEL, ignoreCase = true) ||
                    model.contains("Quantum", ignoreCase = true) -> DEFAULT_MODEL
            model.isBlank() -> DEFAULT_MODEL
            else -> model
        }

        // Attempt API call with targetModel, and fallback to FALLBACK_MODEL if it fails
        val firstAttempt = executeRequest(apiKey, targetModel, messages, systemPrompt, temperature, topP, maxTokens)
        if (firstAttempt.isSuccess || targetModel == FALLBACK_MODEL) {
            return@withContext firstAttempt
        }

        Log.w(TAG, "Request to $targetModel failed (${firstAttempt.exceptionOrNull()?.message}). Retrying with $FALLBACK_MODEL")
        val fallbackAttempt = executeRequest(apiKey, FALLBACK_MODEL, messages, systemPrompt, temperature, topP, maxTokens)
        return@withContext fallbackAttempt
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
        return try {
            val jsonBody = JSONObject().apply {
                put("model", resolvedModel)
                put("temperature", temperature.coerceIn(0.0, 1.5))
                put("top_p", topP.coerceIn(0.05, 1.0))
                put("max_tokens", maxTokens.coerceIn(256, 8192))
                put("stream", false)

                val messagesArray = JSONArray()

                // Insert custom system prompt if provided and not already present
                val hasSystemMessageInList = messages.any { it.role == MessageRole.SYSTEM }
                if (!systemPrompt.isNullOrBlank() && !hasSystemMessageInList) {
                    messagesArray.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt.trim())
                    })
                }

                for (msg in messages) {
                    when (msg.role) {
                        MessageRole.USER -> {
                            val userMsgObj = JSONObject().apply {
                                put("role", "user")
                                if (!msg.imageBase64.isNullOrBlank()) {
                                    // Multimodal format for vision-capable models
                                    val contentParts = JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", msg.content.ifBlank { "Describe this image in detail." })
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
                                    put("content", msg.content.ifBlank { "Hello" })
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
                            if (msg.content.isNotBlank()) {
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
            Result.failure(
                NvidiaApiException(0, "Network connection error: ${e.localizedMessage ?: "Unable to reach NVIDIA API server."}")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in sendChatCompletion for model $resolvedModel", e)
            Result.failure(e)
        }
    }
}

class NvidiaApiException(val code: Int, message: String) : Exception(message)
