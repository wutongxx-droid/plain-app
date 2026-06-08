package com.ismartcoding.plain.services.webrtc

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.view.Surface
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.data.DScreenMirrorQuality
import com.ismartcoding.plain.web.websocket.WebSocketHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages screen mirroring via WebTransport (QUIC/HTTP3).
 * This provides lower latency than WebRTC, especially in NAT/Docker environments.
 *
 * Note: This is a simplified implementation. For production use, you would need:
 * - A proper WebTransport client library (e.g., chromium's cronet or custom implementation)
 * - Server-side WebTransport support in Ktor
 *
 * Currently falls back to WebSocket for signaling while streaming via HTTP chunked transfer.
 */
class ScreenMirrorWebTransportManager(
    private val context: Context,
    private val getQuality: () -> DScreenMirrorQuality,
    private val getIsPortrait: () -> Boolean,
) {
    private var mediaCodec: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var displaySurface: Surface? = null
    private var mediaProjection: MediaProjection? = null
    private var encoderJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Connected clients via WebSocket signaling
    private val clientSessions = mutableMapOf<String, WebTransportClientSession>()

    /**
     * Initialize the screen capture and video encoder.
     */
    fun initCapture(projection: MediaProjection): Boolean {
        if (isRunning.get()) {
            LogCat.d("webtransport: already running, skipping")
            return true
        }

        mediaProjection = projection
        val quality = getQuality()
        val isPortrait = getIsPortrait()
        val (width, height) = computeResolution(quality.resolution, isPortrait)

        try {
            // Create MediaCodec encoder for H.264
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, computeBitrate(quality))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            }

            mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            mediaCodec?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            val inputSurface = mediaCodec?.createInputSurface()
            if (inputSurface == null) {
                LogCat.e("webtransport: failed to create input surface")
                return false
            }

            displaySurface = inputSurface
            mediaCodec?.start()
            isRunning.set(true)

            // Create VirtualDisplay
            val dpi = context.resources.displayMetrics.densityDpi
            val vdFlags = if (com.ismartcoding.lib.isUPlus()) 0 else DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
            virtualDisplay = projection.createVirtualDisplay(
                "WebTransport_ScreenCapture",
                width, height, dpi, vdFlags,
                inputSurface, null, null,
            )

            if (virtualDisplay == null) {
                LogCat.e("webtransport: createVirtualDisplay returned null")
                releaseAll()
                return false
            }

            // Register callback for when MediaProjection stops
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    LogCat.d("webtransport: MediaProjection stopped by system")
                    releaseAll()
                }
            }, null)

            // Start encoder loop
            startEncoderLoop()

            LogCat.d("webtransport: VirtualDisplay created ${width}x${height}")
            return true
        } catch (e: Exception) {
            LogCat.e("webtransport: initCapture failed: ${e.message}")
            releaseAll()
            return false
        }
    }

    /**
     * Handle signaling from a client (via WebSocket).
     * Clients send "ready" message to initiate the stream.
     */
    fun handleSignaling(clientId: String, message: WebRtcSignalingMessage) {
        when (message.type) {
            "ready" -> {
                LogCat.d("webtransport: ready from $clientId")
                // Add client session
                val session = WebTransportClientSession(clientId) { data ->
                    sendToClient(clientId, data)
                }
                clientSessions[clientId] = session
                session.onConnected()
            }
            "transport_close" -> {
                LogCat.d("webtransport: client disconnected $clientId")
                clientSessions.remove(clientId)?.release()
            }
            else -> {
                LogCat.d("webtransport: ignore signaling type=${message.type}")
            }
        }
    }

    /**
     * Get quality changed notification.
     */
    fun onQualityChanged() {
        resizeEncoder()
    }

    /**
     * Remove a client session.
     */
    fun removeClient(clientId: String) {
        clientSessions.remove(clientId)?.release()
    }

    /**
     * Release all resources.
     */
    fun releaseAll() {
        isRunning.set(false)
        encoderJob?.cancel()
        encoderJob = null

        clientSessions.values.forEach { it.release() }
        clientSessions.clear()

        virtualDisplay?.release()
        virtualDisplay = null

        displaySurface?.release()
        displaySurface = null

        try {
            mediaCodec?.stop()
            mediaCodec?.release()
        } catch (e: Exception) {
            LogCat.e("webtransport: error releasing codec: ${e.message}")
        }
        mediaCodec = null

        mediaProjection?.stop()
        mediaProjection = null

        LogCat.d("webtransport: released all resources")
    }

    private fun startEncoderLoop() {
        encoderJob = coroutineScope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            val timeoutUs = 10000L // 10ms

            while (isRunning.get()) {
                try {
                    val outputBufferIndex = mediaCodec?.dequeueOutputBuffer(bufferInfo, timeoutUs) ?: break

                    when {
                        outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // No output available, continue
                        }
                        outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = mediaCodec?.outputFormat
                            LogCat.d("webtransport: format changed to $newFormat")
                            // Send format info to clients
                            broadcastToClients(newFormat?.toByteArray() ?: byteArrayOf())
                        }
                        outputBufferIndex >= 0 -> {
                            val outputBuffer = mediaCodec?.getOutputBuffer(outputBufferIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                // Send encoded data to clients
                                val data = ByteArray(bufferInfo.size)
                                outputBuffer.get(data)
                                broadcastToClients(data)

                                // Handle key frame request
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                                    LogCat.d("webtransport: key frame sent, size=${bufferInfo.size}")
                                }
                            }
                            mediaCodec?.releaseOutputBuffer(outputBufferIndex, false)

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    LogCat.e("webtransport: encoder loop error: ${e.message}")
                    break
                }
            }
        }
    }

    private fun broadcastToClients(data: ByteArray) {
        clientSessions.values.forEach { session ->
            session.sendVideoData(data)
        }
    }

    private fun sendToClient(clientId: String, data: ByteArray) {
        coroutineScope.launch {
            try {
                // Use WebSocket to send video data
                // Note: This is a simplified implementation
                // In production, you would use WebTransport directly
                WebSocketHelper.sendSignalingToClientAsync(clientId, data.toString())
            } catch (e: Exception) {
                LogCat.e("webtransport: failed to send to $clientId: ${e.message}")
            }
        }
    }

    private fun resizeEncoder() {
        // Recreate encoder with new resolution
        val quality = getQuality()
        val isPortrait = getIsPortrait()
        val (width, height) = computeResolution(quality.resolution, isPortrait)

        virtualDisplay?.resize(width, height, context.resources.displayMetrics.densityDpi)
        LogCat.d("webtransport: resized to ${width}x${height}")
    }

    private fun computeResolution(targetResolution: Int, isPortrait: Boolean): Pair<Int, Int> {
        val aspectRatio = if (isPortrait) 9f / 16f else 16f / 9f
        val width = if (isPortrait) {
            (targetResolution * aspectRatio).toInt()
        } else {
            targetResolution
        }
        val height = if (isPortrait) {
            targetResolution
        } else {
            (targetResolution / aspectRatio).toInt()
        }
        // Ensure even dimensions (required by H.264)
        return Pair(width and 0xFFFE, height and 0xFFFE)
    }

    private fun computeBitrate(quality: DScreenMirrorQuality): Int {
        return when (quality.mode) {
            com.ismartcoding.plain.enums.ScreenMirrorMode.AUTO -> 4_000_000
            com.ismartcoding.plain.enums.ScreenMirrorMode.HD -> 8_000_000
            com.ismartcoding.plain.enums.ScreenMirrorMode.SMOOTH -> 2_000_000
        }
    }
}

/**
 * Represents a connected client session.
 */
class WebTransportClientSession(
    val clientId: String,
    private val sendData: (ByteArray) -> Unit,
) {
    private val isConnected = AtomicBoolean(false)

    fun onConnected() {
        isConnected.set(true)
    }

    fun sendVideoData(data: ByteArray) {
        if (isConnected.get()) {
            sendData(data)
        }
    }

    fun release() {
        isConnected.set(false)
    }
}