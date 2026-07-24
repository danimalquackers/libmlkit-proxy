package com.libmlkitproxy.proxy.handlers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.CountTokensResponse
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerateContentResponse
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.SystemInstruction
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.lang.StringBuilder
import java.util.UUID
import kotlin.math.min

class ChatHandler(
    private val call: ApplicationCall,
) : Handler {
    val TAG = "LibMLKitProxy"
    val generativeModel = Generation.getClient()

    override suspend fun handleRequest() {
        // Parse the request body
        val body =
            try {
                call.receive<Map<String, Any>>()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse request body as JSON: ${e.message}", e)
                return ErrorHandler.badRequest(call, "Missing or malformed request body: ${e.message}")
            }

        // Many parameters can be ignored, like the model name
        @Suppress("UNCHECKED_CAST")
        val messages =
            body["messages"] as? List<Map<String, Any>>
                ?: run {
                    Log.w(TAG, "Request body is missing 'messages' array, body: $body")

                    return ErrorHandler.badRequest(call, "Missing 'messages' array")
                }

        val (systemInstruction, objects) = parseContent(messages) ?: return

        // Passthru generation parameters
        var maxTokensOpt = body["max_tokens"] as? Int ?: 4096
        val temperatureOpt = body["temperature"] as? Double ?: 0.0

        Log.i(TAG, "Preparing GenerateContentRequest for MLKit...")

        // Generate a list of content (system instructions, images, text)
        val parts =
            Pair(
                systemInstruction,
                objects,
            )

        try {
            // Create a temporary request for context limit checking (since resources like bitmaps are recycled)
            val tokenRequest = createRequest(parts, false)

            val tokenResponse = preflightRequest(tokenRequest)
            val maxTokens = generativeModel.getTokenLimit()
            if (tokenResponse.totalTokens > maxTokens) {
                val errorMessage = "Context window exceeded. Limit is $maxTokens tokens, but request was ${tokenResponse.totalTokens} tokens."

                Log.w(
                    TAG,
                    errorMessage,
                )
                return ErrorHandler.badRequest(call, errorMessage)
            }

            // Build the request
            val request = createRequest(parts)

            // Apply optional parameters
            val remainingTokens = 4096 - tokenResponse.totalTokens
            request.maxOutputTokens =
                if (maxTokensOpt > 0) {
                    min(maxTokensOpt, remainingTokens)
                } else {
                    remainingTokens
                }
            request.temperature =
                if (temperatureOpt >= 0.0f && temperatureOpt <= 1.0f) {
                    temperatureOpt.toFloat()
                } else {
                    0.0f
                }

            // Execute inferencing
            Log.i(TAG, "Generating content response...")
            val result = generativeModel.generateContent(request.build())
            val candidate = result.candidates.firstOrNull()

            // Log the stop reason for debugging
            when (candidate?.finishReason) {
                // No-op for successful generation
                Candidate.FinishReason.STOP -> {}

                Candidate.FinishReason.MAX_TOKENS -> {
                    Log.w(TAG, "Generation stopped due to response token exhaustion, response may be truncated")
                }

                Candidate.FinishReason.OTHER -> {
                    Log.d(TAG, "Generation stopped for an unknown reason")
                }
            }

            // Calculate the number of response tokens
            val generatedText = candidate?.text ?: ""
            val dummyRequest = createRequest(Pair(null, listOf(generatedText)))
            val dummyTokens = preflightRequest(dummyRequest)

            // Convert the result into properly-formatted JSON
            val response = createResponse(generatedText, Pair(tokenResponse.totalTokens, dummyTokens.totalTokens))

            // Return the generated text
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            ErrorHandler.handleException(call, e)
        } finally {
            // Free bitmap resources
            for (obj in objects) {
                if (obj is Bitmap) {
                    obj.recycle()
                }
            }

            // Free up hardware resources for the host app when request finishes
            generativeModel.close()
        }
    }

    private suspend fun parseContent(messages: List<Map<String, Any>>): Pair<String?, List<Any>>? {
        // Start creating an MLKit prompt
        val systemInstructionBuilder = StringBuilder()
        val objects = mutableListOf<Any>()

        try {
            // Parse the full context and handle multi-modal blocks
            for (i in 0 until messages.size) {
                @Suppress("UNCHECKED_CAST")
                val msg = messages[i]

                // Parse the agent/user role
                val role = msg["role"]?.toString()?.uppercase() ?: "user"

                // Parse the content
                val contentObj = msg["content"]

                if (contentObj is String) {
                    if (role == "SYSTEM") {
                        systemInstructionBuilder.appendLine(contentObj)
                    } else {
                        objects.add("$role: $contentObj")
                    }
                } else if (contentObj is List<*>) {
                    // Append additional content
                    @Suppress("UNCHECKED_CAST")
                    for (part in contentObj as List<Map<String, Any>>) {
                        val type = part["type"]?.toString()?.lowercase() ?: ""

                        if (type == "image_url") {
                            // Extract image prompts
                            @Suppress("UNCHECKED_CAST")
                            val urlObj = part["image_url"] as? Map<String, Any> ?: continue
                            val urlString = urlObj["url"]?.toString() ?: ""

                            if (urlString.startsWith("data:image")) {
                                try {
                                    // Convert Base64-encoded images
                                    val base64Data = urlString.substringAfter(",")
                                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

                                    // Save the image, overwriting any previous images if needed
                                    val parsedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                                    objects.add(parsedBitmap)

                                    Log.i(TAG, "Attaching bitmap to prompt")
                                } catch (e: Exception) {
                                    for (obj in objects) {
                                        if (obj is Bitmap) {
                                            obj.recycle()
                                        }
                                    }
                                    Log.e(TAG, "Failed to decode base64 image_url: ${e.message}", e)
                                    ErrorHandler.badRequest(call, "Failed to decode base64 image_url: ${e.message}")
                                    return null
                                }
                            } else {
                                Log.e(TAG, "Unsupported image url: $urlString")
                            }
                        } else if (type == "text") {
                            val parsedText = part["text"]?.toString()

                            if (parsedText != null && parsedText.isNotEmpty()) {
                                objects.add("$role: $parsedText")
                            }
                        } else {
                            Log.w(TAG, "Unsupported content type: $type")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            for (obj in objects) {
                if (obj is Bitmap) {
                    obj.recycle()
                }
            }

            Log.e(TAG, "Failed to parse messages content: ${e.message}", e)
            ErrorHandler.badRequest(call, "Malformed messages payload: ${e.message}")

            return null
        }

        // Finalize the prompt strings
        val systemInstruction = systemInstructionBuilder.toString()

        return Pair(systemInstruction, objects)
    }

    private fun createRequest(
        content: Pair<String?, List<Any>>,
        cachePrefix: Boolean = true,
    ): GenerateContentRequest.Builder {
        val contentBuilder = Content.Builder()
        val (systemInstruction, objects) = content

        var hasBitmap = false

        // Convert the input objects to multimodal parts
        val previewPrompt = StringBuilder()
        for (part in objects) {
            when (part) {
                is String -> {
                    contentBuilder.text(part)

                    previewPrompt.appendLine(part)
                }

                is Bitmap -> {
                    contentBuilder.image(part.copy(part.config, true))
                    hasBitmap = true

                    previewPrompt.appendLine("[Image]")
                }

                else -> {
                    Log.w(TAG, "Unsupported content type: $part")
                }
            }
        }

        // Add the system instruction if there is one
        val hasSystemInstruction = systemInstruction != null && systemInstruction.isNotBlank()
        if ((!cachePrefix && hasSystemInstruction) || (hasSystemInstruction && hasBitmap)) {
            Log.d(TAG, "SYSTEM:\n$systemInstruction\n$previewPrompt")

            contentBuilder.addPart(SystemInstruction(systemInstruction))
        } else {
            Log.d(TAG, previewPrompt.toString())
        }

        // Create a GenerateContentRequest
        val builder = GenerateContentRequest.Builder(contentBuilder.build())

        // Enable prefix caching for single-mode requests
        if (cachePrefix && hasSystemInstruction && !hasBitmap) {
            Log.i(TAG, "Using prefix caching for this request")
            builder.promptPrefix = PromptPrefix(systemInstruction)
        }

        return builder
    }

    private suspend fun preflightRequest(tokenRequest: GenerateContentRequest.Builder): CountTokensResponse =
        generativeModel.countTokens(tokenRequest.build())

    private fun createResponse(
        generatedText: String,
        tokens: Pair<Int, Int>,
    ): Map<String, Any> {
        // Clean up result (remove code blocks if needed)
        val text = sanitizeResponse(generatedText)

        Log.d(TAG, "Generated text: $text")

        val (promptTokens, outputTokens) = tokens

        val response =
            mapOf(
                "id" to "mlkit-${UUID.randomUUID()}",
                "object" to "chat.completion",
                "created" to System.currentTimeMillis() / 1000,
                "model" to "gemini-nano",
                "choices" to
                    listOf(
                        mapOf(
                            "index" to 0,
                            "message" to
                                mapOf(
                                    "role" to "assistant",
                                    "content" to text,
                                ),
                            "finish_reason" to "stop",
                        ),
                    ),
                // Inject the pre-flight token math into the OpenAI usage block
                "usage" to
                    mapOf(
                        "prompt_tokens" to promptTokens,
                        "total_tokens" to promptTokens + outputTokens,
                    ),
            )

        return response
    }

    private fun sanitizeResponse(responseText: String): String {
        // Remove markdown code wrappers like ```json
        val startCodeBlock = Regex("```\\w*?\\n")
        val endCodeBlock = Regex("\\n```$")
        var sanitizedText = responseText.replace(startCodeBlock, "").replace(endCodeBlock, "")

        return sanitizedText.trim()
    }
}
