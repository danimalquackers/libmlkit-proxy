package com.libmlkitproxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Prompt
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class OpenAIServer(private val context: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/v1/models" -> if (session.method == Method.GET) handleModels() else methodNotAllowed()
                "/v1/chat/completions" -> if (session.method == Method.POST) handleChatCompletions(session) else methodNotAllowed()
                // "/v1/audio/transcriptions" -> ... (Speech API logic)
                else -> notFound()
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    private fun handleModels(): Response {
        val json = """
            {
              "object": "list",
              "data": [
                {
                  "id": "gemini-nano",
                  "object": "model",
                  "created": 1701849600,
                  "owned_by": "google"
                }
              ]
            }
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleChatCompletions(session: IHTTPSession): Response {
        val map = HashMap<String, String>()
        session.parseBody(map)
        val bodyString = map["postData"] ?: return badRequest("Missing request body")
        
        val requestJson = JSONObject(bodyString)
        val messages = requestJson.optJSONArray("messages") ?: return badRequest("Missing 'messages' array")
        
        val promptBuilder = java.lang.StringBuilder()
        var parsedBitmap: Bitmap? = null

        // 1. Parse the full context and handle multi-modal blocks
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)
            val role = msg.optString("role", "user").uppercase()
            val contentObj = msg.opt("content")

            var textContent = ""

            if (contentObj is String) {
                textContent = contentObj
            } else if (contentObj is JSONArray) {
                // Handle the OpenAI array content format for multimodal
                for (j in 0 until contentObj.length()) {
                    val part = contentObj.getJSONObject(j)
                    val type = part.optString("type")
                    
                    if (type == "image_url") {
                        val urlObj = part.optJSONObject("image_url")
                        val urlString = urlObj?.optString("url") ?: ""
                        
                        // Parse standard Data URI Base64 image
                        if (urlString.startsWith("data:image")) {
                            try {
                                val base64Data = urlString.substringAfter(",")
                                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                parsedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                            } catch (e: Exception) {
                                return badRequest("Failed to decode base64 image_url")
                            }
                        }
                    } else if (type == "text") {
                        textContent += part.optString("text") + "\n"
                    }
                }
            }
            
            if (textContent.isNotBlank()) {
                promptBuilder.append("$role: $textContent\n")
            }
        }
        
        promptBuilder.append("ASSISTANT: ")
        val finalContext = promptBuilder.toString()
        val textPart = TextPart(finalContext)

        // Build Request (Multimodal or Text-Only)
        // The GenerateContentRequest.Builder constructor dictates the modality
        val requestBuilder = if (parsedBitmap != null) {
            GenerateContentRequest.Builder(ImagePart(parsedBitmap), textPart)
        } else {
            GenerateContentRequest.Builder(textPart)
        }

        // Apply any parameters requested by the OpenAI payload (e.g., max_tokens)
        requestJson.optInt("max_tokens", 0).let { maxTokens ->
            if (maxTokens > 0) requestBuilder.setMaxOutputTokens(maxTokens)
        }
        requestJson.optDouble("temperature", -1.0).let { temp ->
            if (temp >= 0) requestBuilder.setTemperature(temp.toFloat())
        }

        val request = requestBuilder.build()

        // NanoHTTPD threads allow safe runBlocking for the ML Kit ListenableFutures
        return runBlocking {
            try {
                val client = Prompt.getClient()

                // Check context length
                val tokenResponse = client.countTokens(request).await()
                val tokenLimit = client.getTokenLimit().await()

                if (tokenResponse.totalTokens > tokenLimit) {
                    return@runBlocking errorResponse(
                        Response.Status.BAD_REQUEST, 
                        "Context window exceeded. Limit is $tokenLimit tokens, but request was ${tokenResponse.totalTokens} tokens.", 
                        "context_length_exceeded"
                    )
                }

                // Send inference request to MLKit
                val result = client.generateContent(request).await()
                val generatedText = result.text ?: ""

                val responseJson = JSONObject().apply {
                    put("id", "chatcmpl-${UUID.randomUUID()}")
                    put("object", "chat.completion")
                    put("created", System.currentTimeMillis() / 1000)
                    put("model", "gemini-nano")
                    put("choices", JSONArray().apply {
                        put(JSONObject().apply {
                            put("index", 0)
                            put("message", JSONObject().apply {
                                put("role", "assistant")
                                put("content", generatedText.trim())
                            })
                            put("finish_reason", "stop")
                        })
                    })
                    // Optionally include usage stats
                    put("usage", JSONObject().apply {
                        put("prompt_tokens", tokenResponse.totalTokens)
                        put("total_tokens", tokenResponse.totalTokens) // Output tokens not synchronously counted prior, but you can estimate or omit
                    })
                }

                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
            } catch (e: Exception) {
                handleException(e)
            }
        }
    }

    private fun handleException(e: Exception): Response {
        // Check if the exception is a specific GenAiException
        if (e is GenAiException) {
            val (status, type, message) = when (e.errorCode) {
                GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> 
                    Triple(Response.Status.FORBIDDEN, "insufficient_quota", 
                        "App is in the background. Foreground required.")
                        
                GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> 
                    Triple(Response.Status.TOO_MANY_REQUESTS, "rate_limit_exceeded", 
                        "Battery usage quota exceeded for this app.")
                        
                GenAiException.ErrorCode.BUSY -> 
                    Triple(Response.Status.SERVICE_UNAVAILABLE, "server_overloaded", 
                        "The service is currently busy.")
                        
                GenAiException.ErrorCode.REQUEST_TOO_LARGE -> 
                    Triple(Response.Status.BAD_REQUEST, "context_length_exceeded", 
                        "Request is too large for the model.")
                        
                else -> 
                    Triple(Response.Status.INTERNAL_ERROR, "internal_error", 
                        "ML Kit error code: ${e.errorCode}")
            }
            return errorResponse(status, message, type)
        }

        // Handle generic non-MLKit exceptions
        return errorResponse(Response.Status.INTERNAL_ERROR, e.message ?: "Unknown error", "internal_error")
    }

    private fun errorResponse(status: Response.Status, message: String, type: String): Response {
        val errorJson = JSONObject().apply {
            put("error", JSONObject().apply {
                put("message", message)
                put("type", type)
            })
        }
        return newFixedLengthResponse(status, "application/json", errorJson.toString())
    }

    private fun badRequest(msg: String) = errorResponse(Response.Status.BAD_REQUEST, msg, "invalid_request_error")
    private fun notFound() = errorResponse(Response.Status.NOT_FOUND, "Not Found", "invalid_request_error")
    private fun methodNotAllowed() = errorResponse(Response.Status.METHOD_NOT_ALLOWED, "Method Not Allowed", "invalid_request_error")
}