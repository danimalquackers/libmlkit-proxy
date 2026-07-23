package com.libmlkitproxy.proxy.initializers

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import java.util.Locale

class SpeechInit : Initializer {
    val TAG = "LibMLKitProxy"

    override suspend fun initialize(context: Context): Int {
        var speechRecognizer: SpeechRecognizer? = null

        try {
            // Connect to the Speech Recognition API
            val options = SpeechRecognizerOptions.builder()
            speechRecognizer = SpeechRecognition.getClient(options.build())

            val status: Int = speechRecognizer.checkStatus()

            // Download the speech recognition model in the background
            if (status == FeatureStatus.DOWNLOADABLE) {
                Log.i(TAG, "Downloading speech recognition model...")

                speechRecognizer.download()
                return FeatureStatus.DOWNLOADING
            } else if (status == FeatureStatus.AVAILABLE) {
                Log.i(TAG, "Speech recognition API is available, continuing...")
            } else {
                Log.e(TAG, "Speech recognition API not available on this device. Status: $status")
            }

            return status
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check speech recognition API feature status", e)
            return FeatureStatus.UNAVAILABLE
        } finally {
            speechRecognizer?.close()
        }
    }
}
