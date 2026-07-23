package com.libmlkitproxy.proxy.handlers

import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.utils.io.toByteArray
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Handles OpenAI-compatible audio transcription requests at `/v1/audio/transcriptions`.
 *
 * Extracts input audio payloads from multipart HTTP requests, decodes them entirely in-memory
 * to 16-bit headerless PCM via Android [MediaCodec], and streams the audio to the MLKit
 * [SpeechRecognizer] for transcription.
 */
class SpeechHandler(
    private val call: ApplicationCall,
) : Handler {
    private val TAG = "LibMLKitProxy"

    override suspend fun handleRequest() {
        Log.i(TAG, "Processing audio transcription request...")

        // 1. Parse multipart request to extract audio bytes and response format
        val (audioBytes, responseFormat) = parseMultipartPayload()
        if (audioBytes == null || audioBytes.isEmpty()) {
            Log.w(TAG, "Missing or empty audio file payload in request")
            return ErrorHandler.badRequest(call, "Missing 'file' field in multipart payload")
        }

        // Decode input audio in memory to 16-bit headerless PCM
        val (pcmBytes, sampleRate) =
            try {
                decodeToPcm16(audioBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode audio to PCM 16: ${e.message}", e)
                return ErrorHandler.badRequest(call, "Failed to decode audio to PCM 16-bit: ${e.message}")
            }

        Log.i(TAG, "Decoded audio to headerless PCM 16-bit (${pcmBytes.size} bytes, $sampleRate Hz)")

        // Configure MLKit Speech Recognizer
        val options = SpeechRecognizerOptions.Builder()
        options.locale = Locale.US
        options.preferredMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED

        // Try to enable advanced speech recognition
        var speechRecognizer = SpeechRecognition.getClient(options.build())

        // Fall back to basic speech recognition if advanced is not supported
        if (speechRecognizer.checkStatus() != FeatureStatus.AVAILABLE) {
            options.preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC

            Log.i(TAG, "Advanced speech recognition not available, falling back to basic")
            speechRecognizer = SpeechRecognition.getClient(options.build())
        }

        var readSide: ParcelFileDescriptor? = null
        var writeSide: ParcelFileDescriptor? = null

        try {
            // Create an in-memory pipe to stream PCM audio to MLKit
            val pipe = ParcelFileDescriptor.createPipe()
            readSide = pipe[0]
            writeSide = pipe[1]

            // Write PCM bytes to the pipe on a background thread, paced to real-time audio speed.
            // MLKit is designed for microphone input and overflows if fed faster than playback rate.
            // We track an "audio clock" (how many ns of audio have been written) vs wall clock,
            // sleeping only the remaining gap between them. This keeps the pipe constantly near-full
            // while never outpacing MLKit's internal buffer — minimizing overall latency.
            val pipeWriteFd = writeSide
            val bytesPerSecond = sampleRate * 2L // 16-bit mono = 2 bytes per sample
            val chunkSize = (bytesPerSecond / 10).toInt() // ~100ms of audio per write
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipeWriteFd).use { os ->
                        val startNs = System.nanoTime()
                        var offset = 0

                        while (offset < pcmBytes.size) {
                            val end = minOf(offset + chunkSize, pcmBytes.size)
                            os.write(pcmBytes, offset, end - offset)
                            offset = end

                            // Audio clock: how many nanoseconds of audio have been written
                            val audioNs = offset * 1_000_000_000L / bytesPerSecond

                            // Sleep only the remaining gap between audio clock and wall clock.
                            // If the write() itself took long (pipe was full), sleepNs may be 0.
                            val elapsedNs = System.nanoTime() - startNs
                            val sleepNs = audioNs - elapsedNs
                            if (sleepNs > 0 && offset < pcmBytes.size) {
                                Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                            }
                        }
                        os.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing PCM bytes to pipe: ${e.message}", e)
                }
            }.start()

            // Create a transcription request pointing to the pipe
            val request: SpeechRecognizerRequest =
                speechRecognizerRequest {
                    audioSource = AudioSource.fromPfd(readSide)
                }

            val transcribedText = StringBuilder()

            Log.i(TAG, "Starting MLKit speech recognition...")
            speechRecognizer.startRecognition(request).collect { response ->
                when (response) {
                    // Ignore in-progress words
                    is SpeechRecognizerResponse.PartialTextResponse -> {}

                    // Commit confidently recognized words
                    is SpeechRecognizerResponse.FinalTextResponse -> {
                        val chunkText = response.text
                        if (!chunkText.isNullOrEmpty()) {
                            if (transcribedText.isNotEmpty()) {
                                transcribedText.append(" ")
                            }

                            // Append the latest transcription chunk
                            transcribedText.append(chunkText)
                        }
                    }

                    // Finish the speech recognition stream
                    is SpeechRecognizerResponse.CompletedResponse -> {
                        val resultText = transcribedText.toString().trim()
                        Log.d(TAG, "Speech recognition completed: $resultText")

                        // Return the result
                        val responseBody =
                            if (responseFormat == "text") {
                                resultText
                            } else {
                                mapOf("text" to resultText)
                            }

                        call.respond(HttpStatusCode.OK, responseBody)
                    }

                    is SpeechRecognizerResponse.ErrorResponse -> {
                        val error = response.e

                        // ERROR_TYPE_NO_SPEECH_DETECTED indicates the input stream was empty
                        if (error.message?.contains("ERROR_TYPE_NO_SPEECH_DETECTED") == true) {
                            Log.d(TAG, "No speech in request audio buffer")
                            call.respond(HttpStatusCode.OK, mapOf("text" to ""))
                        } else {
                            Log.e(TAG, "Speech recognition error: ${error.message}", error)
                            ErrorHandler.handleException(call, error)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Speech recognition error: ${e.message}", e)
            ErrorHandler.handleException(call, e)
        } finally {
            // Clean up recognition session and pipe resources
            try {
                speechRecognizer.stopRecognition()
                speechRecognizer.close()

                // Close the pipe reader, EOF
                readSide?.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Parses the incoming multipart form data to extract the audio file bytes and optional form fields.
     */
    private suspend fun parseMultipartPayload(): Pair<ByteArray?, String> {
        var audioBytes: ByteArray? = null
        var responseFormat = "json"

        try {
            // Iterate through and extract relevant fields
            val multipart = call.receiveMultipart()
            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FileItem -> {
                        if (part.name == "file" || audioBytes == null) {
                            audioBytes = part.provider().toByteArray()

                            Log.i(TAG, "Extracted audio file payload (${audioBytes.size} bytes)")
                        }
                    }

                    is PartData.FormItem -> {
                        if (part.name == "response_format") {
                            responseFormat = part.value
                        }
                    }

                    // Ignore binary fields
                    else -> {}
                }

                part.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse multipart request: ${e.message}", e)
        }

        return Pair(audioBytes, responseFormat)
    }

    /**
     * Decodes an arbitrary input audio format (MP3, WAV, AAC, M4A, etc.) in memory into 16-bit headerless PCM bytes.
     */

    /** Holds decoded 16-bit PCM audio data alongside the source sample rate. */
    private data class DecodedAudio(
        val pcmBytes: ByteArray,
        val sampleRate: Int,
    )

    private fun decodeToPcm16(audioBytes: ByteArray): DecodedAudio {
        val extractor =
            MediaExtractor().apply {
                setDataSource(ByteArrayMediaDataSource(audioBytes))
            }

        var audioTrackIndex = -1
        var mime = ""
        var format: MediaFormat? = null

        // Locate the first audio track in the media container
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val trackMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""

            // Ignore video tracks
            if (trackMime.startsWith("audio/")) {
                audioTrackIndex = i

                mime = trackMime
                format = trackFormat

                break
            }
        }

        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track found in input file")
        }

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        extractor.selectTrack(audioTrackIndex)

        // Initialize audio-only MediaCodec decoder for the target MIME type
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        return try {
            val outputStream = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val timeoutUs = 10_000L

            // Process input/output buffers until EOS is reached
            while (!outputDone) {
                // Queue audio samples in the decoder
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)
                        val sampleSize = if (buffer != null) extractor.readSampleData(buffer, 0) else -1

                        if (sampleSize < 0) {
                            // Once all input samples are injected, EOS
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            // Progress down the buffer, queuing input samples
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // Drain decoded audio data
                val outIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outIndex >= 0) {
                    codec.getOutputBuffer(outIndex)?.let { buffer ->
                        if (bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)

                            // Seek and read a chunk from the buffer
                            buffer.position(bufferInfo.offset)
                            buffer.get(chunk, 0, bufferInfo.size)

                            // Write the sample to the output stream
                            outputStream.write(chunk)
                        }
                    }

                    // Release and optionally terminate encoding
                    codec.releaseOutputBuffer(outIndex, false)
                    outputDone = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                }
            }

            DecodedAudio(outputStream.toByteArray(), sampleRate)
        } finally {
            runCatching {
                codec.stop()
                codec.release()
                extractor.release()
            }
        }
    }

    /**
     * In-memory [MediaDataSource] implementation for feeding a [ByteArray] to [MediaExtractor].
     */
    private class ByteArrayMediaDataSource(
        private val data: ByteArray,
    ) : MediaDataSource() {
        override fun readAt(
            position: Long,
            buffer: ByteArray,
            offset: Int,
            size: Int,
        ): Int {
            if (position >= data.size) return -1

            // Read bytes up until the specified offset or the end
            val remaining = data.size - position
            val toRead =
                if (size > remaining) {
                    remaining.toInt()
                } else {
                    size
                }

            // Copy into the provided buffer
            System.arraycopy(data, position.toInt(), buffer, offset, toRead)

            return toRead
        }

        override fun getSize(): Long = data.size.toLong()

        override fun close() {}
    }
}
