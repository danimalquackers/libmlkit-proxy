package com.libmlkitproxy.proxy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Content
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.Part
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.math.min

class OpenAIServer(
    private val port: Int,
) {
    private val TAG = "LibMLKitProxy"

    fun serve() {
        try {
            Log.i(TAG, "Creating Ktor Netty server instance on port $port...")
            val serverInstance =
                embeddedServer(
                    Netty,
                    configure = {
                        connector { port = this@OpenAIServer.port }

                        // Run server with HTTP/2 cleartext
                        enableHttp2 = true
                        enableH2c = true
                    },
                ) {
                    install(ContentNegotiation) { gson { setPrettyPrinting() } }

                    routing {
                        // List models (stub)
                        get("/v1/models") { handleModels(call) }

                        // // Stateless completions with limited context
                        // post("/v1/completions") {
                        //     handleChatCompletions(call)
                        // }

                        // Multi-turn conversational chat
                        post("/v1/chat/completions") { handleChatCompletions(call) }
                    }
                }

            // Monitor application ready event
            serverInstance.environment.monitor.subscribe(io.ktor.server.application.ServerReady) {
                Log.i(TAG, "Ktor Netty server is READY and listening on port $port")
            }

            Log.i(TAG, "Starting Ktor Netty server asynchronously...")
            serverInstance.start(wait = false)
            Log.i(TAG, "Ktor Netty server start() call returned.")
        } catch (t: Throwable) {
            Log.e(TAG, "Exception/Error during Netty server initialization or startup", t)
        }
    }

    private suspend fun handleModels(call: ApplicationCall) {
        val currentTime = System.currentTimeMillis() / 1000

        val models =
            listOf(
                "mlkit-truncate",
                "mlkit-compress",
                "mlkit-truncate-sanitize",
                "mlkit-compress-sanitize",
            )

        // Return a stub model for apps that need one
        val json =
            mapOf(
                "object" to "list",
                "data" to
                    models.map { modelId ->
                        mapOf(
                            "id" to modelId,
                            "object" to "model",
                            "created" to currentTime,
                            "owned_by" to "google",
                        )
                    },
            )

        call.respond(HttpStatusCode.OK, json)
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        Log.i(TAG, "Received POST /v1/chat/completions request")

        // Parse the request body
        val body =
            try {
                call.receive<Map<String, Any>>()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse request body as JSON: ${e.message}", e)
                return badRequest(call, "Missing or malformed request body: ${e.message}")
            }

        Log.i(TAG, "Parsed request body successfully. Body: $body")

        // Many parameters can be ignored, like the model name
        val messages =
            body["messages"] as? List<Map<String, Any>>
                ?: run {
                    Log.w(TAG, "Request body is missing 'messages' array, body: $body")
                    return badRequest(call, "Missing 'messages' array")
                }

        // Start creating an MLKit prompt
        val promptBuilder = java.lang.StringBuilder()
        var parsedBitmap: Bitmap? = null

        try {
            // Parse the full context and handle multi-modal blocks
            for (i in 0 until messages.size) {
                val msg = messages[i] as? Map<String, Any> ?: continue

                // Parse the agent/user role
                val role = msg["role"]?.toString()?.uppercase() ?: "user"

                // Parse the content
                val contentObj = msg["content"]

                var textContent = ""

                if (contentObj is String) {
                    textContent = contentObj
                } else if (contentObj is List<*>) {
                    // Append additional content
                    for (part in contentObj) {
                        val partMap = part as? Map<String, Any> ?: continue
                        val type = partMap["type"]?.toString()?.lowercase() ?: ""

                        if (type == "image_url") {
                            // Extract image prompts
                            val urlObj = partMap["image_url"] as? Map<String, Any> ?: continue
                            val urlString = urlObj["url"]?.toString() ?: ""

                            if (urlString.startsWith("data:image")) {
                                try {
                                    // Convert Base64-encoded images
                                    val base64Data = urlString.substringAfter(",")
                                    val decodedBytes = Base64.decode(base64Data, Base64.DEFAULT)

                                    // Save the image, overwriting any previous images if needed
                                    parsedBitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)

                                    Log.i(TAG, "Attaching bitmap to prompt")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to decode base64 image_url: ${e.message}", e)
                                    return badRequest(call, "Failed to decode base64 image_url: ${e.message}")
                                }
                            } else {
                                Log.e(TAG, "Unsupported image url: $urlString")
                            }
                        } else if (type == "text") {
                            textContent += partMap["text"]?.toString() ?: ""
                            textContent += "\n"
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse messages content: ${e.message}", e)
            return badRequest(call, "Malformed messages payload: ${e.message}")
        }

        // Append the assistant role to continue generation
        promptBuilder.append("ASSISTANT: ")
        val finalContext = promptBuilder.toString()

        Log.d(TAG, "Final context: $finalContext")

        // Passthru generation parameters
        var maxTokensOpt = body["max_tokens"] as? Int ?: 256
        val temperatureOpt = body["temperature"] as? Double ?: 0.0

        // Clamp output tokens due to limited context window
        maxTokensOpt = min(maxTokensOpt, 256)

        Log.i(TAG, "Preparing GenerateContentRequest for MLKit...")

        // Generate a list of content (system instructions, images, text)
        val parts =
            Triple(
                null,
                parsedBitmap,
                finalContext,
            )

        val generativeModel = Generation.getClient()

        try {
            // Create a temporary request for context limit checking (since resources like bitmaps are recycled)
            val tokenRequest = createRequest(parts)

            // Preflight token check
            val tokenResponse = generativeModel.countTokens(tokenRequest.build())
            val maxTokens = generativeModel.getTokenLimit()
            if (tokenResponse.totalTokens > maxTokens) {
                Log.w(
                    TAG,
                    "Context window exceeded. Limit: $maxTokens, Request: ${tokenResponse.totalTokens}",
                )
                return badRequest(
                    call,
                    "Context window exceeded. Limit is $maxTokens tokens, but request was ${tokenResponse.totalTokens} tokens.",
                )
            }

            // Build the request
            val request = createRequest(parts)

            // Apply optional parameters
            if (maxTokensOpt > 0) {
                request.maxOutputTokens = maxTokensOpt
            }
            if (temperatureOpt >= 0) {
                request.temperature = temperatureOpt.toFloat()
            }

            // Execute inferencing
            Log.i(TAG, "Generating content response...")
            val result = generativeModel.generateContent(request.build())

            // Extract text from the Candidate list
            var generatedText = result.candidates.firstOrNull()?.text ?: ""

            // Clean up result (remove code blocks if needed)
            generatedText = sanitizeResponse(generatedText)

            Log.d(TAG, "Generated text: $generatedText")

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
                                        "content" to generatedText,
                                    ),
                                "finish_reason" to "stop",
                            ),
                        ),
                    // Inject the pre-flight token math into the OpenAI usage block
                    "usage" to
                        mapOf(
                            "prompt_tokens" to tokenResponse.totalTokens,
                            "total_tokens" to tokenResponse.totalTokens,
                        ),
                )

            // Log the final JSON response
            Log.d(TAG, "Full JSON response: $response")

            // Convert the JSON object into an in-memory binary blob to avoid blocking
            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            handleException(call, e)
        } finally {
            // Discard the original bitmap resource
            parsedBitmap?.recycle()

            // Free up hardware resources for the host app when request finishes
            generativeModel.close()
        }
    }

    private fun createRequest(content: Triple<String?, Bitmap?, String>): GenerateContentRequest.Builder {
        GenerationConfig.Builder()
        val contentBuilder = Content.Builder()
        val (systemInstruction, bitmap, text) = content

        // Add the system instruction if there is one
        if (systemInstruction != null) {
            contentBuilder.addPart(SystemInstruction(systemInstruction))
        }

        // Add the image if there is one
        if (bitmap != null) {
            contentBuilder.addPart(ImagePart(bitmap.copy(bitmap.config, true)))
        }

        // Add the text
        contentBuilder.addPart(TextPart(text))

        // Create a GenerateContentRequest
        val builder = GenerateContentRequest.Builder(contentBuilder.build())
        return builder
    }

    private fun sanitizeResponse(responseText: String): String {
        // Remove markdown code wrappers like ```json
        val startCodeBlock = Regex("```\\w*?\\n")
        val endCodeBlock = Regex("\\n```$")
        var sanitizedText = responseText.replace(startCodeBlock, "").replace(endCodeBlock, "")

        return sanitizedText.trim()
    }

    private suspend fun handleException(
        call: ApplicationCall,
        e: Exception,
    ) {
        Log.e(TAG, "Error generating content", e)

        if (e is GenAiException) {
            val (status, type, message) =
                when (e.errorCode) {
                    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED ->
                        Triple(
                            HttpStatusCode.Forbidden,
                            "insufficient_quota",
                            "App is in the background. Foreground required.",
                        )
                    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED ->
                        Triple(
                            HttpStatusCode.TooManyRequests,
                            "rate_limit_exceeded",
                            "Battery usage quota exceeded for this app.",
                        )
                    GenAiException.ErrorCode.BUSY ->
                        Triple(
                            HttpStatusCode.ServiceUnavailable,
                            "server_overloaded",
                            "The service is currently busy.",
                        )
                    GenAiException.ErrorCode.REQUEST_TOO_LARGE ->
                        Triple(
                            HttpStatusCode.BadRequest,
                            "context_length_exceeded",
                            "Request context window is too large.",
                        )
                    else ->
                        Triple(
                            HttpStatusCode.InternalServerError,
                            "internal_error",
                            "MLKit error code: ${e.errorCode}",
                        )
                }
            return errorResponse(call, status, message, type)
        }

        return errorResponse(
            call,
            HttpStatusCode.InternalServerError,
            e.message ?: "Unknown error",
            "internal_error",
        )
    }

    private suspend fun errorResponse(
        call: ApplicationCall,
        status: HttpStatusCode,
        message: String,
        type: String,
    ) {
        Log.e(TAG, "Returning HTTP $status: $message (type: $type)")
        val errorJson = mapOf("error" to mapOf("message" to message, "type" to type))

        call.respondText(errorJson.toString(), ContentType.Application.Json, status)
    }

    // Stub helpers for errors
    private suspend fun badRequest(
        call: ApplicationCall,
        msg: String,
    ) = errorResponse(call, HttpStatusCode.BadRequest, msg, "invalid_request_error")
}
