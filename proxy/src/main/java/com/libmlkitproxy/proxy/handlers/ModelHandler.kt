package com.libmlkitproxy.proxy.handlers

import android.util.Log
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class ModelHandler(
    private val call: ApplicationCall,
) : Handler {
    val TAG = "LibMLKitProxy"

    override suspend fun handleRequest() {
        val currentTime = System.currentTimeMillis() / 1000

        val models =
            listOf(
                "mlkit",
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
}
