package com.libmlkitproxy

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import com.libmlkitproxy.api.MLKitProxyInterface
import com.libmlkitproxy.AssetExtractor
import dalvik.system.DelegateLastClassLoader

class ProxyLoader : ContentProvider() {
    private val TAG = "LibMLKitProxy"

    override fun onCreate(): Boolean {
        Log.i(TAG, "This app contains LibMLKitProxy, an OpenAI-compatible MLKit proxy server")

        context?.let { ctx ->
            // Launch on IO thread to prevent blocking the Main Thread during app launch
            Thread {
                try {
                    Log.i(TAG, "Extracting ML Kit proxy payload...")
                    
                    // Extract the AAR from assets to internal storage
                    val dexFile = AssetExtractor.extractProxyPayload(ctx, "libmlkit-proxy.apk")

                    // Prepare directories for DexClassLoader
                    val optimizedDir = ctx.getDir("dex_opt", Context.MODE_PRIVATE)
                    
                    // Define directory for native .so libraries if you extract them
                    val nativeLibDir = ctx.getDir("proxy_libs", Context.MODE_PRIVATE)

                    // Extract native .so libraries
                    AssetExtractor.extractNativeLibs(dexFile, nativeLibDir)
                    
                    // Get the Host's classloader (which contains the shared MLKitProxyInterface)
                    val hostClassLoader = MLKitProxyInterface::class.java.classLoader

                    // Use DelegateLastClassLoader to prioritize the Proxy's dependencies over the Host's
                    val proxyClassLoader = dalvik.system.DelegateLastClassLoader(
                        dexFile.absolutePath,
                        nativeLibDir.absolutePath, // Path for extracted .so files
                        hostClassLoader            // Fallback for the shared API interface
                    )

                    // Load and instantiate
                    val proxyImplClass = proxyClassLoader.loadClass("com.libmlkitproxy.proxy.MLKitProxyImplementation")
                    val proxyInstance = proxyImplClass.getDeclaredConstructor().newInstance() as MLKitProxyInterface

                    // Hand off control to the dynamically loaded code
                    proxyInstance.initialize(ctx)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load dynamic ML Kit proxy", e)
                }
            }.start()
        }
        return true
    }

    // Stub methods for ContentProvider
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}