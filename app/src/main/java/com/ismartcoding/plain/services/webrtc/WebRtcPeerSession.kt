package com.ismartcoding.plain.services.webrtc

import com.ismartcoding.lib.helpers.CoroutinesHelper.coIO
import com.ismartcoding.lib.helpers.JsonHelper
import com.ismartcoding.lib.logcat.LogCat
import com.ismartcoding.plain.enums.ScreenMirrorMode
import com.ismartcoding.plain.web.websocket.WebRtcSignalingMessage
import com.ismartcoding.plain.web.websocket.WebSocketHelper
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a single WebRTC peer connection for one client.
 * TCP candidates are enabled for automatic UDP→TCP fallback.
 */
class WebRtcPeerSession(
    val clientId: String,
    private val peerConnectionFactory: PeerConnectionFactory,
    private val videoTrack: VideoTrack,
    private val audioTrack: AudioTrack?,
    private val computeTargetBitrateKbps: () -> Int,
    private val computeStartBitrateKbps: () -> Int,
    private val getTargetFps: () -> Int,
    private val getMode: () -> ScreenMirrorMode,
    private val getStunServers: () -> String,
) {
    private var peerConnection: PeerConnection? = null
    private var videoSender: RtpSender? = null
    private val remoteDescriptionSet = AtomicBoolean(false)
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    /**
     * Default STUN servers (accessible in China)
     */
    private val DEFAULT_STUN_SERVERS = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun3.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer(),
    )

    /**
     * Parse STUN servers from string (comma separated)
     */
    private fun parseIceServers(stunServersStr: String): List<PeerConnection.IceServer> {
        if (stunServersStr.isBlank()) return DEFAULT_STUN_SERVERS
        return try {
            stunServersStr.split(",").mapNotNull { serverStr ->
                val trimmed = serverStr.trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                try {
                    PeerConnection.IceServer.builder(trimmed).createIceServer()
                } catch (e: Exception) {
                    try {
                        PeerConnection.IceServer.builder("stun:$trimmed").createIceServer()
                    } catch (e2: Exception) {
                        null
                    }
                }
            }.ifEmpty { DEFAULT_STUN_SERVERS }
        } catch (e: Exception) {
            DEFAULT_STUN_SERVERS
        }
    }

    fun createPeerConnectionAndOffer() {
        releasePeerConnection()
        val iceServers = parseIceServers(getStunServers())
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        }
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, createObserver())
        videoSender = peerConnection?.addTrack(videoTrack, listOf("screen_stream"))
        audioTrack?.let { peerConnection?.addTrack(it, listOf("screen_stream")) }
        updateVideoBitrate()
        createOffer()
    }

    fun handleAnswer(sdp: String) {
        val pc = peerConnection ?: return
        if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            LogCat.d("webrtc: [$clientId] ignoring stale answer (signalingState=${pc.signalingState()})")
            return
        }
        remoteDescriptionSet.set(false)
        pendingIceCandidates.clear()
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                remoteDescriptionSet.set(true)
                drainPendingIceCandidates()
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun handleIceCandidate(message: WebRtcSignalingMessage) {
        val pc = peerConnection ?: return
        val candidate = IceCandidate(message.sdpMid, message.sdpMLineIndex ?: 0, message.candidate)
        if (remoteDescriptionSet.get()) pc.addIceCandidate(candidate)
        else pendingIceCandidates.add(candidate)
    }

    fun updateVideoBitrate() {
        val sender = videoSender ?: return
        val params = sender.parameters
        val encodings = params.encodings
        if (encodings.isEmpty()) return
        val maxKbps = computeTargetBitrateKbps()
        val startKbps = computeStartBitrateKbps()
        val encoding = encodings[0]
        encoding.maxBitrateBps = maxKbps * 1000
        encoding.maxFramerate = getTargetFps()
        when (getMode()) {
            ScreenMirrorMode.SMOOTH -> encoding.minBitrateBps = startKbps * 800
            else -> encoding.minBitrateBps = startKbps * 500
        }
        params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
        sender.parameters = params
    }

    fun getStats(callback: (availableBitrateKbps: Long, packetLossPercent: Double, rttMs: Double) -> Unit) {
        val pc = peerConnection ?: return
        pc.getStats { report ->
            var availableBitrate = 0L
            var packetsLost = 0L
            var packetsSent = 0L
            var rtt = 0.0
            for (stats in report.statsMap.values) {
                when (stats.type) {
                    "candidate-pair" -> if (stats.members["state"] == "succeeded") {
                        (stats.members["availableOutgoingBitrate"] as? Number)?.let {
                            availableBitrate = (it.toDouble() / 1000).toLong()
                        }
                        (stats.members["currentRoundTripTime"] as? Number)?.let {
                            rtt = it.toDouble() * 1000
                        }
                    }
                    "outbound-rtp" -> if (stats.members["kind"] == "video") {
                        (stats.members["packetsSent"] as? Number)?.let { packetsSent = it.toLong() }
                    }
                    "remote-inbound-rtp" -> if (stats.members["kind"] == "video") {
                        (stats.members["packetsLost"] as? Number)?.let { packetsLost = it.toLong() }
                    }
                }
            }
            val lossPercent = if (packetsSent > 0) (packetsLost.toDouble() / (packetsSent + packetsLost) * 100) else 0.0
            callback(availableBitrate, lossPercent, rtt)
        }
    }

    fun release() = releasePeerConnection()

    private fun releasePeerConnection() {
        remoteDescriptionSet.set(false)
        pendingIceCandidates.clear()
        peerConnection?.close(); peerConnection = null
        videoSender = null
    }

    private fun createObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            LogCat.d("webrtc: [$clientId] ICE candidate: ${candidate.sdp}")
            sendToClient(WebRtcSignalingMessage(
                type = "ice_candidate", candidate = candidate.sdp,
                sdpMid = candidate.sdpMid, sdpMLineIndex = candidate.sdpMLineIndex,
            ))
        }
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            LogCat.d("webrtc: [$clientId] connection state: $newState")
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(r: Boolean) = Unit
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
        override fun onIceCandidatesRemoved(c: Array<IceCandidate>) = Unit
        override fun onAddStream(s: org.webrtc.MediaStream) = Unit
        override fun onRemoveStream(s: org.webrtc.MediaStream) = Unit
        override fun onDataChannel(dc: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onTrack(t: org.webrtc.RtpTransceiver) = Unit
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(description: SessionDescription) {
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        sendToClient(WebRtcSignalingMessage(type = "offer", sdp = description.description))
                    }
                }, description)
            }
        }, MediaConstraints())
    }

    private fun drainPendingIceCandidates() {
        val pc = peerConnection ?: return
        pendingIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    private fun sendToClient(message: WebRtcSignalingMessage) {
        val json = JsonHelper.jsonEncode(message)
        coIO {
            try {
                WebSocketHelper.sendSignalingToClientAsync(clientId, json)
            } catch (ex: Exception) {
                LogCat.e("webrtc: failed to send signaling (${message.type}) to $clientId: ${ex.message}")
            }
        }
    }
}
