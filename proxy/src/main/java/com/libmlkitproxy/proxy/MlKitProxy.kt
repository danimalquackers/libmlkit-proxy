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
import com.google.mlkit.common.MlKit
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.libmlkitproxy.api.MlKitProxyInterface
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

    companion object {
        init {
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

    override fun initialize(context: Context) {
        try {
            MlKit.initialize(context)
            Log.i(TAG, "ML Kit initialized manually successfully")
        } catch (e: Exception) {
            Log.w(TAG, "ML Kit manual initialization exception: ${e.message}")
        }

        scope.launch {
            var generativeModel: GenerativeModel? = null

            try {
                // Wait for MLKit to become available
                generativeModel = Generation.getClient()

                // Determine the MLKit enablement status
                var status = generativeModel.checkStatus()
                val packageName = context.packageName

                // Trigger a model download if needed
                if (status == FeatureStatus.DOWNLOADABLE) {
                    Log.w(TAG, "ML Kit Model downloadable. Initiating background download...")
                    showToast(context, "Downloading AI model in background...")

                    generativeModel.download().collect { downloadStatus ->
                        var totalBytes = 0L
                        when (downloadStatus) {
                            is DownloadStatus.DownloadStarted -> {
                                totalBytes = downloadStatus.bytesToDownload
                            }
                            is DownloadStatus.DownloadProgress -> {
                                val bytes = downloadStatus.totalBytesDownloaded

                                if (totalBytes > 0) {
                                    Log.i(TAG, "Download progress: ${bytes / totalBytes}%")
                                } else {
                                    Log.i(TAG, "Download progress: $bytes bytes")
                                }
                            }
                            is DownloadStatus.DownloadCompleted -> {
                                Log.i(TAG, "Download finished. Starting server...")
                                showToast(context, "Download complete")
                            }
                            is DownloadStatus.DownloadFailed -> {
                                Log.e(TAG, "Model download failed. Cannot start server.")
                            }
                        }
                    }
                }

                // Wait if the model was requested by another app or hasn't finished downloading
                if (status == FeatureStatus.DOWNLOADING) {
                    showToast(context, "Model is already downloading")

                    while (generativeModel.checkStatus() == FeatureStatus.DOWNLOADING) {
                        Log.w(TAG, "ML Kit Model download already in progress")
                        delay(30000) // Poll every 30 seconds
                    }
                }

                // Refresh the status, it should have changed by now
                status = generativeModel.checkStatus()

                if (status == FeatureStatus.AVAILABLE) {
                    Log.i(TAG, "ML Kit is available, starting server...")

                    startServer(context, packageName)
                } else {
                    Log.e(TAG, "ML Kit GenAI not available on this device. Status: $status")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check ML Kit feature status", e)
            } finally {
                // Clean up resources if model check finishes
                if (generativeModel != null) {
                    generativeModel.close()
                }
            }
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

            Log.i(TAG, "Proxy server for $packageName started on port $port")
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
