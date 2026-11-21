package com.example.phonecontrol.controller

import org.json.JSONObject

/**
 * 画质档位：控制端选择，通过文本通道下发给被控端。
 *
 * longEdge = 0 表示由被控端按自身屏幕决定（默认上限 1280）。
 * bitrateKbps = 0 表示由被控端按分辨率自动推算。
 */
enum class CaptureQuality(
    val longEdge: Int,
    val bitrateKbps: Int
) {
    AUTO(0, 0),
    LOW(854, 2_000),
    MEDIUM(1280, 4_000),
    HIGH(1920, 7_000),
    ULTRA(2560, 12_000)
}

/** 采集帧率档位。数值越低带宽和延迟越小。 */
enum class CaptureFps(val fps: Int) {
    ECO(15),
    STANDARD(30),
    SMOOTH(60)
}

/** 画面在控制端的排布方式。 */
enum class VideoFit(val mode: VideoFitMode, val forcedRatio: Float) {
    FIT(VideoFitMode.FIT, 0f),
    FILL(VideoFitMode.FILL, 0f),
    STRETCH(VideoFitMode.STRETCH, 0f),
    RATIO_4_3(VideoFitMode.FIT, 4f / 3f),
    RATIO_16_9(VideoFitMode.FIT, 16f / 9f)
}

/** 一次下发给被控端的采集参数。 */
data class VideoProfile(
    val quality: CaptureQuality,
    val fps: CaptureFps
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", "profile")
        .put("longEdge", quality.longEdge)
        .put("fps", fps.fps)
        .put("bitrateKbps", quality.bitrateKbps)
}
