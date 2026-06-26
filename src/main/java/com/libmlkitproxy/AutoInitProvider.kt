package com.libmlkitproxy

import android.content.ContentProvider
import android.content.ContentValues
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
import kotlinx.coroutines.*
import kotlin.math.abs

class AutoInitProvider : ContentProvider() {
    private var server: OpenAIServer? = null
    private val TAG = "LibMLKitProxy"

    // Create a background scope for the suspend functions
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            scope.launch {
                var generativeModel: GenerativeModel? = null

                try {
                    // Wait for MLKit to become available
                    generativeModel = Generation.getClient()

                    // Determine the MLKit enablement status
                    var status = generativeModel.checkStatus()
                    val packageName = ctx.packageName

                    // Trigger a model download if needed
                    if (status == FeatureStatus.DOWNLOADABLE) {
                        Log.w(TAG, "ML Kit Model downloadable. Initiating background download...")
                        showToast(ctx, "Downloading AI model in background...")
                        
                        generativeModel.download().collect { downloadStatus ->
                            var totalBytes = 0L
                            when (downloadStatus) {
                                is DownloadStatus.DownloadStarted -> {
                                    totalBytes = downloadStatus.bytesToDownload
                                }
                                is DownloadStatus.DownloadProgress -> {
                                    val bytes = downloadStatus.totalBytesDownloaded;

                                    Log.i(TAG, "Download progress: ${bytes/totalBytes}%")
                                }
                                is DownloadStatus.DownloadCompleted -> {
                                    Log.i(TAG, "Download finished. Starting server...")
                                    showToast(ctx, "Download complete")
                                }
                                is DownloadStatus.DownloadFailed -> {
                                    Log.e(TAG, "Model download failed. Cannot start server.")
                                }
                            }
                        }
                    }

                    // Wait if the model was requested by another app or hasn't finished downloading
                    if (status == FeatureStatus.DOWNLOADING) {
                        showToast(ctx, "Model is already downloading")

                        while (generativeModel.checkStatus() == FeatureStatus.DOWNLOADING) {
                            Log.w(TAG, "ML Kit Model download already in progress")
                            delay(30000) // Poll every 30 seconds
                        }
                    }

                    // Refresh the status, it should have changed by now
                    status = generativeModel.checkStatus()

                    if (status == FeatureStatus.AVAILABLE) {
                        startServer(ctx, packageName)
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
        return true
    }

    private fun startServer(ctx: android.content.Context, packageName: String) {
        val port = calculateDeterministicPort(packageName)
        server = OpenAIServer(ctx, port)

        try {
            server?.start()

            Log.i(TAG, "Proxy server for $packageName started dynamically on port $port")
            showToast(ctx, "Listening on :$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server on port $port", e)
            showToast(ctx, "Failed to start server")
        }
    }

    private fun showToast(ctx: android.content.Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, "libmlkit-proxy: $message", Toast.LENGTH_LONG).show()
        }
    }

    private fun calculateDeterministicPort(packageName: String): Int {
        val minPort = 1024
        val maxPort = 65535
        val hash = abs(packageName.hashCode())

        // Calculate a deterministic but likely unique port to listen on
        return minPort + (hash % (maxPort - minPort + 1))
    }

    // Stub methods for ContentProvider
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}