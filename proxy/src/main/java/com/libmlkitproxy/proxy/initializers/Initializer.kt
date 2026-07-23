package com.libmlkitproxy.proxy.initializers

import android.content.Context
import com.google.mlkit.genai.common.FeatureStatus

interface Initializer {
    @FeatureStatus
    suspend fun initialize(context: Context): Int
}
