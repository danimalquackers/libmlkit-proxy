package com.libmlkitproxy.proxy

import android.util.Log
import com.libmlkitproxy.proxy.handlers.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

class OpenAIServer(
    private val port: Int,
) {
    private val TAG = "LibMLKitProxy"

    fun serve() {
        try {
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
                        get("/v1/models") {
                            ModelHandler(call).handleRequest()
                        }

                        // // Stateless completions with limited context
                        // post("/v1/completions") {
                        //     handleChatCompletions(call)
                        // }

                        // Multi-turn conversational chat
                        post("/v1/chat/completions") {
                            Log.i(TAG, "Received POST /v1/chat/completions request")

                            ChatHandler(call).handleRequest()
                        }

                        // Voice transcription service
                        post("/v1/audio/transcriptions") {
                            Log.i(TAG, "Received POST /v1/audio/transcriptions")

                            SpeechHandler(call).handleRequest()
                        }
                    }
                }

            // Start server engine without blocking the calling thread
            serverInstance.start(wait = false)
            Log.i(TAG, "MLKit proxy server is listening on port $port")
        } catch (t: Throwable) {
            Log.e(TAG, "Exception/Error during proxy server initialization or startup", t)
        }
    }
}
