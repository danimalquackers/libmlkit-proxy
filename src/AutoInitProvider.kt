package com.libmlkitproxy

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.google.mlkit.genai.prompt.Prompt
import com.google.mlkit.genai.common.FeatureStatus
import kotlin.math.abs

class AutoInitProvider : ContentProvider() {
    private var server: OpenAIServer? = null
    private val TAG = "LibMLKitProxy"

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            val client = Prompt.getClient()
            
            // Asynchronously check if Gemini Nano is supported and downloaded
            client.checkFeatureStatus().addOnCompleteListener { task ->
                val packageName = ctx.packageName
                
                if (task.isSuccessful) {
                    val status = task.result
                    if (status == FeatureStatus.AVAILABLE) {
                        startServer(ctx, packageName)
                    } else if (status == FeatureStatus.DOWNLOAD_REQUIRED) {
                        Log.w(TAG, "ML Kit Model download required. Initiating download...")

                        // Trigger the MLKit model download in the background
                        client.download().collect { downloadStatus ->
                            when (downloadStatus) {
                                is DownloadStatus.Downloading -> Log.i(TAG, "Downloading ML Kit Model: ${downloadStatus.progress}%")
                                is DownloadStatus.Completed -> Log.i(TAG, "ML Kit Model downloaded successfully, please restart the app")
                                is DownloadStatus.Failed -> Log.e(TAG, "Failed to download ML Kit Model", downloadStatus.exception)
                                is DownloadStatus.Cancelled -> Log.w(TAG, "ML Kit Model download cancelled")
                            }
                        }

                        showToast(ctx, "Downloading AI model in background...")
                    } else {
                        Log.e(TAG, "ML Kit GenAI not available on this device. Status: $status")
                    }
                } else {
                    Log.e(TAG, "Failed to check ML Kit feature status", task.exception)
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
            showToast(ctx, "libmlkit-proxy ($packageName): Listening on :$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy server on port $port", e)
        }
    }

    private fun showToast(ctx: android.content.Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun calculateDeterministicPort(packageName: String): Int {
        val minPort = 1024
        val maxPort = 65535
        val hash = abs(packageName.hashCode())
        return minPort + (hash % (maxPort - minPort + 1))
    }

    // ... standard ContentProvider overrides returning null/0
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}