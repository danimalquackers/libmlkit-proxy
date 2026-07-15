package com.libmlkitproxy

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class AssetExtractor {
    companion object {
        private val TAG = "LibMLKitProxy"

        public fun extractProxyPayload(context: Context, assetFileName: String): File {
            // Use a private directory so other apps cannot tamper with executable code
            val outputDir = context.getDir("proxy_payloads", Context.MODE_PRIVATE)
            val outputFile = File(outputDir, assetFileName)

            // Skip extraction if it already exists
            // TODO: Implement a more robust version/checksum verification
            if (outputFile.exists()) {
                return outputFile
            }

            try {
                context.getAssets().open(assetFileName).use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 8192)
                    }
                }

                outputFile.setReadOnly()
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting proxy payload", e)
            }
            
            return outputFile
        }


        fun extractNativeLibs(apkFile: File, nativeLibDir: File) {
            if (!nativeLibDir.exists()) nativeLibDir.mkdirs()

            // Determine the device's primary architecture (e.g., arm64-v8a)
            val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: return

            ZipFile(apkFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    
                    // Only extract .so files that match the device's architecture
                    if (entry.name.startsWith("lib/$primaryAbi/") && entry.name.endsWith(".so")) {
                        val libName = File(entry.name).name
                        val libFile = File(nativeLibDir, libName)
                        
                        // Skip extraction if already present to save I/O time
                        if (libFile.exists() && libFile.length() == entry.size) continue

                        zip.getInputStream(entry).use { input ->
                            libFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }
            }
        }
    }
}
