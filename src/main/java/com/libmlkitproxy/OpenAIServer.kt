package com.libmlkitproxy

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.min

class OpenAIServer(private val context: Context, port: Int) : NanoHTTPD("127.0.0.1", port) {
    private val TAG = "LibMLKitProxy"

    override fun serve(session: IHTTPSession): Response {
        return try {
            Log.i(TAG, "Request to ${session.uri}")

            when (session.uri) {
                // TODO Add support for transcriptions
                "/v1/models" -> if (session.method == Method.GET) handleModels() else methodNotAllowed()
                "/v1/chat/completions" -> if (session.method == Method.POST) handleChatCompletions(session) else methodNotAllowed()
                else -> notFound()
            }
        } catch (e: Exception) {
            handleException(e)
        }
    }

    private fun handleModels(): Response {
        // Return a stub model for apps that need one
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
        // Parse the request
        val map = HashMap<String, String>()
        session.parseBody(map)

        // Parse the request body
        val bodyString = map["postData"] ?: return badRequest("Missing request body")

        // Many parameters can be ignored, like the model name
        val requestJson = JSONObject(bodyString)
        val messages = requestJson.optJSONArray("messages") ?: return badRequest("Missing 'messages' array")

        // Start creating an MLKit prompt
        val promptBuilder = java.lang.StringBuilder()
        var parsedBitmap: Bitmap? = null

        // Parse the full context and handle multi-modal blocks
        for (i in 0 until messages.length()) {
            val msg = messages.getJSONObject(i)

            // Parse the agent/user role
            val role = msg.optString("role", "user").uppercase()

            // Parse the content
            val contentObj = msg.opt("content")

            var textContent = ""

            if (contentObj is String) {
                textContent = contentObj
            } else if (contentObj is JSONArray) {
                // Append additional content
                for (j in 0 until contentObj.length()) {
                    val part = contentObj.getJSONObject(j)
                    val type = part.optString("type")

                    if (type == "image_url") {
                        // Extract image prompts
                        val urlObj = part.optJSONObject("image_url")
                        val urlString = urlObj?.optString("url") ?: ""

                        if (urlString.startsWith("data:image")) {
                            try {
                                // Convert Base64-encoded images
                                val base64Data = urlString.substringAfter(",")
                                val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

                                // Save the image, overwriting any previous images if needed
                                parsedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                                Log.i(TAG, "Attaching bitmap to prompt")
                            } catch (e: Exception) {
                                return badRequest("Failed to decode base64 image_url")
                            }
                        }
                    } else if (type == "text") {
                        textContent += part.optString("text") + "\n"
                    } else {
                        Log.w(TAG, "Unsupported content type: $type")
                    }
                }
            }

            // Concatenate text parts into one
            if (textContent.isNotBlank()) {
                promptBuilder.append("$role: $textContent\n")
            }
        }

        // Append the assistant role to continue generation
        promptBuilder.append("ASSISTANT: ")
        val finalContext = promptBuilder.toString()

        Log.d(TAG, "Final context: $finalContext")

        // Passthru generation parameters
        var maxTokensOpt = requestJson.optInt("max_tokens", 256)
        val temperatureOpt = requestJson.optDouble("temperature", 0.0)

        // Clamp output tokens due to limited context window
        maxTokensOpt = min(maxTokensOpt, 256)

        Log.i(TAG, "Preparing GenerateContentRequest for MLKit...")

        // Build the request using the exact Beta 2 DSL signatures
        val request = if (parsedBitmap != null) {
            generateContentRequest(ImagePart(parsedBitmap!!), TextPart(finalContext)) {
                if (maxTokensOpt > 0) { maxOutputTokens = maxTokensOpt }
                if (temperatureOpt >= 0) { temperature = temperatureOpt.toFloat() }
            }
        } else {
            generateContentRequest(TextPart(finalContext)) {
                if (maxTokensOpt > 0) { maxOutputTokens = maxTokensOpt }
                if (temperatureOpt >= 0) { temperature = temperatureOpt.toFloat() }
            }
        }

        return runBlocking {
            val generativeModel = Generation.getClient()

            try {
                // Preflight token check 
                val tokenResponse = generativeModel.countTokens(request)
                val maxTokens = generativeModel.getTokenLimit()
                if (tokenResponse.totalTokens > maxTokens) {
                    return@runBlocking errorResponse(
                        Response.Status.BAD_REQUEST, 
                        "Context window exceeded. Limit is $maxTokens tokens, but request was ${tokenResponse.totalTokens} tokens.", 
                        "context_length_exceeded"
                    )
                }

                // Execute inferencing
                Log.i(TAG, "Generating content response...")
                val result = generativeModel.generateContent(request)

                // Extract text from the Candidate list
                val generatedText = result.candidates.firstOrNull()?.text ?: ""
                Log.d(TAG, "Generated text: $generatedText")

                val responseJson = JSONObject().apply {
                    put("id", "mlkit-${UUID.randomUUID()}")
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

                    // Inject the pre-flight token math into the OpenAI usage block
                    put("usage", JSONObject().apply {
                        put("prompt_tokens", tokenResponse.totalTokens)
                        put("total_tokens", tokenResponse.totalTokens) 
                    })
                }

                newFixedLengthResponse(Response.Status.OK, "application/json", responseJson.toString())
            } catch (e: Exception) {
                handleException(e)
            } finally {
                // Free up hardware resources for the host app when request finishes
                generativeModel.close()
            }
        }
    }

    private fun handleException(e: Exception): Response {
        Log.e(TAG, "Error generating content", e)

        if (e is GenAiException) {
            val (status, type, message) = when (e.errorCode) {
                GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> 
                    Triple(Response.Status.FORBIDDEN, "insufficient_quota", "App is in the background. Foreground required.")
                GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> 
                    Triple(Response.Status.TOO_MANY_REQUESTS, "rate_limit_exceeded", "Battery usage quota exceeded for this app.")
                GenAiException.ErrorCode.BUSY -> 
                    Triple(Response.Status.SERVICE_UNAVAILABLE, "server_overloaded", "The service is currently busy.")
                GenAiException.ErrorCode.REQUEST_TOO_LARGE -> 
                    Triple(Response.Status.BAD_REQUEST, "context_length_exceeded", "Request context window is too large.")
                else -> 
                    Triple(Response.Status.INTERNAL_ERROR, "internal_error", "MLKit error code: ${e.errorCode}")
            }
            return errorResponse(status, message, type)
        }

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

    // Stub methods for NanoHTTPD
    private fun badRequest(msg: String) = errorResponse(Response.Status.BAD_REQUEST, msg, "invalid_request_error")
    private fun notFound() = errorResponse(Response.Status.NOT_FOUND, "Not Found", "invalid_request_error")
    private fun methodNotAllowed() = errorResponse(Response.Status.METHOD_NOT_ALLOWED, "Method Not Allowed", "invalid_request_error")
}