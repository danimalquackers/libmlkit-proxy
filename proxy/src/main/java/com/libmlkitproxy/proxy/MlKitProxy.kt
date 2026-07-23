package com.libmlkitproxy.proxy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.mlkit.common.MlKit
import com.google.mlkit.genai.common.FeatureStatus
import com.libmlkitproxy.api.MlKitProxyInterface
import com.libmlkitproxy.proxy.initializers.*
import kotlinx.coroutines.*
import kotlin.math.abs

class MLKitProxy : MlKitProxyInterface {
    private var server: OpenAIServer? = null
    private val TAG = "LibMLKitProxy"

    private val exceptionHandler =
        CoroutineExceptionHandler { _, exception ->
            Log.e(TAG, "Uncaught exception in proxy coroutine scope", exception)
        }

    // Create a background scope for the suspend functions
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override fun initialize(context: Context) {
        scope.launch {
            try {
                MlKit.initialize(context)
                Log.i(TAG, "ML Kit initialized manually successfully")
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit manual initialization exception: ${e.message}")
                return@launch
            }

            val initializers =
                listOf(
                    PromptInit(),
                    SpeechInit(),
                )

            var status: Int = FeatureStatus.AVAILABLE
            for (initializer in initializers) {
                val initStatus: Int = initializer.initialize(context)

                when (initStatus) {
                    FeatureStatus.AVAILABLE -> {
                        // No-op
                    }

                    // Typically indicates AICore is downloading the model in the background
                    FeatureStatus.DOWNLOADING -> {
                        if (status != FeatureStatus.UNAVAILABLE) {
                            status = FeatureStatus.DOWNLOADING
                        }
                    }

                    // Typically indicates hardware incompatibility or another error
                    FeatureStatus.UNAVAILABLE -> {
                        status = FeatureStatus.UNAVAILABLE
                    }

                    else -> {
                        Log.e(TAG, "Failed with an unknown initializer state: $status")
                    }
                }
            }

            // One or more models are downloading
            when (status) {
                FeatureStatus.AVAILABLE -> {
                    // If everything initialized correctly, start the server
                    val packageName = context.packageName

                    startServer(context, packageName)
                }

                FeatureStatus.DOWNLOADING -> {
                    val builder = AlertDialog.Builder(context)

                    // Display an alert box with instructions
                    builder.setTitle("libmlkit-proxy")
                    builder.setMessage(
                        "Downloading AI models in the background, please make sure you are " +
                            "connected to Wi-Fi and have sufficient battery and storage available",
                    )

                    builder.setPositiveButton("OK") { dialog, _ ->
                        dialog.dismiss()
                    }

                    builder.show()
                }

                FeatureStatus.UNAVAILABLE -> {
                    showToast(context, "MLKit is not available on this device")
                }
            }
        }
    }

    companion object {
        init {
            // TODO Find a better workaround than lsposed
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                try {
                    org.lsposed.hiddenapibypass.HiddenApiBypass
                        .addHiddenApiExemptions("")
                    Log.i("LibMLKitProxy", "Successfully exempted hidden APIs process-wide in static initializer")
                } catch (e: Throwable) {
                    Log.e("LibMLKitProxy", "Failed to exempt hidden APIs in static initializer", e)
                }
            }

            // Disable Netty native transports and unsafe operations for Android compatibility
            System.setProperty("io.netty.transport.noNative", "true")
            System.setProperty("io.netty.noUnsafe", "true")
            System.setProperty("io.netty.noReflection", "true")
            System.setProperty("io.netty.allocator.type", "unpooled")
        }
    }

    private fun startServer(
        context: android.content.Context,
        packageName: String,
    ) {
        val port = calculateDeterministicPort(packageName)
        server = OpenAIServer(port)

        try {
            server?.serve()

            showToast(context, "Listening on :$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server on port $port", e)

            showToast(context, "Failed to start server")
        }
    }

    private fun showToast(
        context: android.content.Context,
        message: String,
    ) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "libmlkit-proxy: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun calculateDeterministicPort(packageName: String): Int {
        val minPort = 1024
        val maxPort = 65535
        val hash = abs(packageName.hashCode())

        // Calculate a deterministic but likely unique port to listen on
        return minPort + (hash % (maxPort - minPort + 1))
    }
}
