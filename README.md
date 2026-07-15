# libmlkit-proxy

A lightweight, zero-touch Android library that exposes an OpenAI-compatible HTTP server backed by Google's on-device AI models. Designed for seamless injection into existing APKs via apktool, LSPosed, or source inclusion.

## Features

* **Zero-Touch Setup:** Auto-initializes via a `ContentProvider`. No code changes required in the host app.
* **Always-On, Safe Background Server:** The local Ktor server runs continuously but defers OS constraint enforcement (like background execution blocks and battery quotas) directly to the native ML Kit SDK, gracefully returning API errors to the client.
* **OpenAI Compatible:** Drop-in replacement for clients expecting `https://api.openai.com/v1/`.

## Supported Endpoints

* `GET /v1/models` - Returns available on-device models.
* `POST /v1/chat/completions` - Powered by ML Kit GenAI Prompt API (Gemini Nano).
* `POST /v1/audio/transcriptions` - Powered by ML Kit GenAI Speech Recognition API.

## ⚠️ Limitations & Hardware Requirements

**Device Support**

This library requires a device that supports Android AICore with the required models downloaded (e.g., Pixel 8/9/10 series, Galaxy S24 series). Unsupported devices will return an API error.

**Context Window & Token Limits**

Gemini Nano has a strict context window. The Prompt API typically caps inputs at ~4000 tokens and is not designed for long-form output (responses over 512 tokens may fail).

**Foreground Execution Only**

Android heavily restricts NPU/CPU usage for AI inference. The host application **must be in the foreground** during inference. If a client calls the proxy while the host app is in the background, the server will return an `HTTP 403 Forbidden` error.

**Battery Usage**

The MLKit API may return an error if the application has made too many requests within a day, to preserve battery life. The proxy will return an `HTTP 429 Too Many Requests` if this occurs. This limit is enforced per application.

**Unsupported Features**

* **No Embeddings:** No vector embedding endpoints (`/v1/embeddings`).
* **No Image Generation:** DALL-E endpoints (`/v1/images/generations`) are unsupported.
* **Tool Calling / JSON Mode:** Not natively supported by the baseline on-device models.
