package com.niki914.zafiro.chat.agentic.livevision

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** 采集到的一帧实时屏幕（无障碍 YAML 树 + 可选截屏图）。 */
data class LiveVisionFrame(
    val yaml: String,
    val version: String,
    val nodeCount: Int,
    val capturedAtMs: Long,
    val captureLatencyMs: Long,
    val imagePath: String? = null,
    val trigger: String,
)

/**
 * Dynamic Real-time Vision 控制器（v1.7.0 Capability 2）。
 *
 * 将静态截图升级为连续感知流：
 *  - 监听无障碍 UI 事件（AccessibilityController.lastUiEventTime），界面变化时
 *    自动去抖采集新帧（结构树必采，像素截屏尽力而为）；
 *  - UI 静止时按 [PERIODIC_CAPTURE_MS] 周期兜底刷新，保证帧持续流动；
 *  - 会话自限时长（[MAX_SESSION_MS]），超时自动关闭，避免常驻耗电。
 *
 * Agent 通过 live_screen 工具读取最新帧；采集全程复用既有
 * AccessibilityController / TerminalSessionPool 通道，零新权限。
 */
object LiveVisionController {

    private const val LOG_TAG = "niki914_nexus_LiveVision"
    private const val DEBOUNCE_MS = 800L
    private const val STALE_FRESH_MS = 1_200L
    private const val PERIODIC_CAPTURE_MS = 12_000L
    private const val MAX_SESSION_MS = 10 * 60 * 1000L
    private const val MAX_FRAMES = 5
    /** A5：Context 等待上限；超时放弃像素截屏（best-effort 语义）。 */
    private const val CONTEXT_WAIT_TIMEOUT_MS = 5_000L
    private const val SCREENSHOT_TIMEOUT_MS = 15_000L

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount

    private var sessionJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private val lastCaptureAtMs = AtomicLong(0)
    private val recentFrames = ArrayDeque<LiveVisionFrame>()

    fun start() {
        if (_active.value) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        sessionScope = scope
        synchronized(recentFrames) { recentFrames.clear() }
        _frameCount.value = 0
        lastCaptureAtMs.set(0)
        _active.value = true
        sessionJob = scope.launch { runSession() }
        Logger.i(LOG_TAG, "live vision session started")
    }

    fun stop() {
        if (!_active.value) return
        _active.value = false
        sessionJob?.cancel()
        sessionJob = null
        sessionScope?.let { scope ->
            runCatching { scope.cancel() }
        }
        sessionScope = null
        Logger.i(LOG_TAG, "live vision session stopped frames=$_frameCount")
    }

    /** 若无活动会话则自动开启（live_screen 工具调用时的按需入口）。 */
    fun ensureSession() {
        if (!_active.value) start()
    }

    /**
     * 读取最新帧；[forceFresh] 为 true 或帧已过期（>[STALE_FRESH_MS]）时同步补采一帧。
     * 会话未开启时返回 null（工具层负责给出引导信息）。
     */
    suspend fun latestFrame(forceFresh: Boolean = false): LiveVisionFrame? {
        if (!_active.value) return null
        val latest = synchronized(recentFrames) { recentFrames.lastOrNull() }
        val ageMs = latest?.let { System.currentTimeMillis() - it.capturedAtMs } ?: Long.MAX_VALUE
        return if (latest == null || forceFresh || ageMs > STALE_FRESH_MS) {
            captureNow(trigger = if (latest == null) "first-frame" else "stale-refresh")
                ?: latest
        } else {
            latest
        }
    }

    fun recentFrameCount(): Int = synchronized(recentFrames) { recentFrames.size }

    // ------------------------------------------------------------ session loop

    private suspend fun runSession() {
        val startedAt = System.currentTimeMillis()
        var lastSeenUiEvent = AccessibilityController.lastUiEventTime
        var lastPeriodicMs = 0L
        try {
            while (coroutineContext.isActive && _active.value) {
                val now = System.currentTimeMillis()
                if (now - startedAt > MAX_SESSION_MS) {
                    Logger.i(LOG_TAG, "session timeout, auto stop")
                    stop()
                    return
                }

                val uiEvent = AccessibilityController.lastUiEventTime
                val uiChanged = uiEvent != lastSeenUiEvent
                if (uiChanged) {
                    lastSeenUiEvent = uiEvent
                    if (now - lastCaptureAtMs.get() >= DEBOUNCE_MS) {
                        captureNow(trigger = "ui-change")
                    }
                    lastPeriodicMs = now
                } else if (now - lastPeriodicMs >= PERIODIC_CAPTURE_MS) {
                    captureNow(trigger = "periodic")
                    lastPeriodicMs = now
                }
                delay(250)
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "session loop ended reason=${t.message}")
            if (_active.value) stop()
        }
    }

    /** 采集一帧：无障碍结构树必采，截屏图尽力而为；失败返回 null 不抛异常。 */
    suspend fun captureNow(trigger: String): LiveVisionFrame? {
        if (!_active.value) return null
        val startedNs = System.nanoTime()
        val snapshot = try {
            AccessibilityController.captureScreen().getOrNull()
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "captureScreen failed reason=${t.message}")
            null
        } ?: return null

        val latencyMs = (System.nanoTime() - startedNs) / 1_000_000
        val imagePath = try {
            captureScreenshotPath()
        } catch (t: Throwable) {
            null
        }

        val frame = LiveVisionFrame(
            yaml = snapshot.yaml,
            version = snapshot.version,
            nodeCount = snapshot.nodeCount,
            capturedAtMs = System.currentTimeMillis(),
            captureLatencyMs = latencyMs,
            imagePath = imagePath,
            trigger = trigger,
        )
        synchronized(recentFrames) {
            recentFrames.addLast(frame)
            while (recentFrames.size > MAX_FRAMES) recentFrames.removeFirst()
        }
        lastCaptureAtMs.set(frame.capturedAtMs)
        _frameCount.value = _frameCount.value + 1
        return frame
    }

    /**
     * 尽力而为的像素截屏（root → shizuku `screencap`）；均不可用时返回 null，
     * 不影响结构树帧的产出（与 ScreenshotBuiltin 同一降级模式）。
     */
    private suspend fun captureScreenshotPath(): String? = withContext(Dispatchers.IO) {
        // A5：await(runCatching) 只兜异常不兜超时 —— provide 永不到时仍会永久挂起。
        // 改为带超时的等待，拿不到 Context 就放弃像素截屏（结构树帧照常产出）。
        val context = com.niki914.xposed.api.util.ContextProvider.await(CONTEXT_WAIT_TIMEOUT_MS)
            ?: return@withContext null
        val cacheDir = File(context.cacheDir, "live_vision").apply { mkdirs() }
        // 清理上一帧，避免缓存目录无限增长
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
        val rawFile = File(cacheDir, "live_${System.currentTimeMillis()}.png")
        for (identity in listOf("root", "shizuku")) {
            val outcome = TerminalSessionPool.openAndExecute(
                identity = identity,
                cwd = null,
                command = "screencap -p ${rawFile.absolutePath}",
                timeoutMs = SCREENSHOT_TIMEOUT_MS,
            )
            val session = (outcome as? TerminalCommandOutcome.Success)?.session
                ?: (outcome as? TerminalCommandOutcome.Timeout)?.session
            if (session != null) runCatching { TerminalSessionPool.close(session) }
            if (outcome is TerminalCommandOutcome.Success &&
                outcome.result.exitCode == 0 &&
                rawFile.exists() &&
                rawFile.length() > 0
            ) {
                return@withContext rawFile.absolutePath
            }
        }
        runCatching { rawFile.delete() }
        null
    }
}
