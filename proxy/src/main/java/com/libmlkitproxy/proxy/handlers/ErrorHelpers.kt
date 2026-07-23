package com.libmlkitproxy.proxy.handlers

import android.util.Log
import com.google.mlkit.genai.common.GenAiException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

class ErrorHandler {
    companion object {
        val TAG = "LibMLKitProxy"

        suspend fun handleException(
            call: ApplicationCall,
            e: Exception,
        ) {
            Log.e(TAG, "Error generating content", e)

            if (e is GenAiException) {
                val (status, type, message) =
                    when (e.errorCode) {
                        GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> {
                            Triple(
                                HttpStatusCode.Forbidden,
                                "insufficient_quota",
                                "App is in the background. Foreground required.",
                            )
                        }

                        GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> {
                            Triple(
                                HttpStatusCode.TooManyRequests,
                                "rate_limit_exceeded",
                                "Battery usage quota exceeded for this app.",
                            )
                        }

                        GenAiException.ErrorCode.BUSY -> {
                            Triple(
                                HttpStatusCode.ServiceUnavailable,
                                "server_overloaded",
                                "The service is currently busy.",
                            )
                        }

                        GenAiException.ErrorCode.REQUEST_TOO_LARGE -> {
                            Triple(
                                HttpStatusCode.BadRequest,
                                "context_length_exceeded",
                                "Request context window is too large.",
                            )
                        }

                        GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR -> {
                            Triple(
                                HttpStatusCode.BadRequest,
                                "invalid_request_error",
                                "Request did not pass policy check.",
                            )
                        }

                        else -> {
                            Triple(
                                HttpStatusCode.InternalServerError,
                                "internal_error",
                                "MLKit error code: ${e.errorCode}",
                            )
                        }
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

        // Stub helpers for 400 errors
        suspend fun badRequest(
            call: ApplicationCall,
            msg: String,
        ) = errorResponse(call, HttpStatusCode.BadRequest, msg, "invalid_request_error")

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
    }
}
