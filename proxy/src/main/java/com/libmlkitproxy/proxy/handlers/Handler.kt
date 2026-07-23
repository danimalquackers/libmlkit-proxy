package com.libmlkitproxy.proxy.handlers

interface Handler {
    suspend fun handleRequest()
}
