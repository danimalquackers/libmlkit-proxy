package com.libmlkitproxy.proxy.initializers

import android.content.Context
import android.util.Log
import com.google.mlkit.common.MlKit
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PromptInit : Initializer {
    val TAG = "LibMLKitProxy"

    override suspend fun initialize(context: Context): Int {
        var generativeModel: GenerativeModel? = null

        try {
            // Wait for MLKit to become available
            generativeModel = Generation.getClient()

            // Determine the Prompt API enablement status
            var status: Int =
                try {
                    generativeModel.checkStatus()
                } catch (e: GenAiException) {
                    val FEATURE_UNAVAILABLE = 606
                    val STRUCTURED_OUTPUT_NOT_SUPPORTED = 648

                    // Allow silent failure if structured output is unavailable
                    if (
                        e.errorCode == FEATURE_UNAVAILABLE &&
                        e.message?.contains(STRUCTURED_OUTPUT_NOT_SUPPORTED.toString()) == true
                    ) {
                        Log.w(TAG, "Structured output not supported, but proceeding anyway")

                        // Default to downloadable to allow execution to continue
                        FeatureStatus.AVAILABLE
                    } else {
                        Log.e(TAG, "Prompt API not available: $e")

                        FeatureStatus.UNAVAILABLE
                    }
                }

            // Trigger a model download if needed
            if (status == FeatureStatus.DOWNLOADABLE) {
                Log.w(TAG, "Downloading prompt model...")

                generativeModel.download()
                return FeatureStatus.DOWNLOADING
            } else if (status == FeatureStatus.AVAILABLE) {
                Log.i(TAG, "Prompt API is available, continuing...")
            } else {
                Log.e(TAG, "Prompt API not available on this device. Status: $status")
            }

            return status
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Prompt API feature status", e)

            return FeatureStatus.UNAVAILABLE
        } finally {
            // Clean up resources if model check finishes
            generativeModel?.close()
        }
    }
}
