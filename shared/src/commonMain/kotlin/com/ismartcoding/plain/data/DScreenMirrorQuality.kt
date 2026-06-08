package com.ismartcoding.plain.data

import com.ismartcoding.plain.enums.ScreenMirrorMode
import com.ismartcoding.plain.enums.ScreenMirrorTransport
import kotlinx.serialization.Serializable



@Serializable
data class DScreenMirrorQuality(
    val mode: ScreenMirrorMode = ScreenMirrorMode.AUTO,
    val transport: ScreenMirrorTransport = ScreenMirrorTransport.WEBRTC,
    val resolution: Int = 1080,
)