package com.ismartcoding.plain.enums

/**
 * Transport protocol for screen mirroring.
 * WebRTC: Default, uses ICE/STUN/TURN for NAT traversal
 * WebTransport: Lower latency using QUIC/HTTP3, better for Docker/NAT networks
 */
enum class ScreenMirrorTransport {
    WEBRTC,      // Default, uses WebRTC with ICE
    WEBTRANSPORT // Low latency via QUIC/HTTP3
}