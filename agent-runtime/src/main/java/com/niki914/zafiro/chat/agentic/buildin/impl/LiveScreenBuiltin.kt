package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.livevision.LiveVisionController
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * live_screen 工具（v1.7.0 Dynamic Real-time Vision）：
 * 读取实时视觉会话的最新屏幕帧 —— 无障碍结构树（必含）+ 像素截屏（尽力而为）。
 *
 * 与 screenshot 的区别：screenshot 是一次性静态抓取；live_screen 背后是
 * LiveVisionController 连续感知流（UI 变化自动采帧 + 周期兜底），
 * 处理动画、游戏、高速变化的界面时用 live_screen 每次都拿"此刻"的画面。
 *
 * 结果契约：
 *  - data.screen = { version, node_count, captured_at, age_ms, capture_latency_ms, trigger, frames }
 *  - data.image  = { path, mime_type, ... }（可选，LocalToolExecutor 自动升格为视觉输入）
 *  - data.yaml   = 屏幕结构树（与 screen_operation_accessibility 的 token 寻址兼容）
 */
class LiveScreenBuiltin : BuiltinTool() {

    override val name: String = "live_screen"

    override val description: String = """
Read the CURRENT device screen state from the real-time vision stream: the accessibility
tree (YAML, token-addressable) plus an optional screenshot image. Unlike screenshot (a
one-shot static capture), live_screen is backed by a continuous capture session that
auto-refreshes as the UI changes — use it for dynamic or animated interfaces (games,
videos, transitions) where a static snapshot may be stale, or any time you need the
freshest possible view before acting. Set fresh=true to force a brand-new capture.
    """.trimIndent()

    override val defaultEnabled: Boolean = false

    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult {
        val fresh = parseFreshFlag(request.argumentsJson)

        if (!LiveVisionController.active.value) {
            LiveVisionController.ensureSession()
        }

        val frame = LiveVisionController.latestFrame(forceFresh = fresh)
            ?: return BuiltinToolResult.failure(
                code = "LIVE_VISION_UNAVAILABLE",
                message = "Real-time vision produced no frame; the accessibility service is likely not enabled.",
                hint = "Ask the user to enable Zafiro's accessibility service (or root/shizuku for screenshots), " +
                        "then retry. Fallback: use the screenshot tool if a privileged shell exists."
            )

        val timeText = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(frame.capturedAtMs))
        val ageMs = System.currentTimeMillis() - frame.capturedAtMs

        val data = buildJsonObject {
            put(
                "screen",
                buildJsonObject {
                    put("version", frame.version)
                    put("node_count", frame.nodeCount)
                    put("captured_at", timeText)
                    put("age_ms", ageMs)
                    put("capture_latency_ms", frame.captureLatencyMs)
                    put("trigger", frame.trigger)
                    put("frames_captured", LiveVisionController.frameCount.value)
                }
            )
            put("yaml", frame.yaml)
            frame.imagePath?.let { path ->
                put(
                    "image",
                    buildJsonObject {
                        put("path", path)
                        put("mime_type", "image/png")
                    }
                )
            }
        }

        return BuiltinToolResult.success(
            message = "Live screen frame captured at $timeText " +
                    "(${frame.nodeCount} nodes, ${ageMs}ms old, trigger=${frame.trigger}).",
            data = data,
            hint = "Use the {version}_{i} tokens from data.yaml with screen_operation_accessibility " +
                    "to interact with on-screen elements.",
        )
    }

    private fun parseFreshFlag(argumentsJson: String): Boolean {
        if (argumentsJson.isBlank()) return false
        return try {
            val obj = Json.parseToJsonElement(argumentsJson) as? JsonObject ?: return false
            obj["fresh"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
        } catch (t: Throwable) {
            false
        }
    }

    private companion object {
        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "fresh": {
      "type": "boolean",
      "description": "Force a brand-new capture instead of returning the latest cached frame. Default false."
    }
  },
  "required": []
}
        """.trimIndent()
    }
}
