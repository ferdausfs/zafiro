package com.niki914.zafiro.app.automation

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import com.niki914.logging.Logger
import java.io.File

/**
 * 监听公共 Download 目录的新文件（CREATE / CLOSE_WRITE / MOVED_TO），
 * 交给 [AutomationHub] 匹配 FILE_DOWNLOAD 触发器。
 * 自包含递归监听为 API 29+ 特性；此处仅监听顶层目录（满足分类场景）。
 */
class DownloadObserver(
    context: Context,
    private val onNewFile: (String) -> Unit,
) {

    // C2：真实 Download 目录解析。此前硬编码 /storage/emulated/0/Download ——
    // 多用户设备（Second user / 部分工作资料宿主）上当前用户的 external
    // storage 根并不一定是 emulated/0；Environment API 解析的是「本进程所属
    // 用户」的 Download 目录，跨用户场景天然正确（工作资料内的文件本进程
    // 本就不可见，不属于本监听器的职责范围）。
    private val dir: File = resolveDownloadDir()
    private val watchDir: String = dir.absolutePath

    private var observer: FileObserver? = null

    /** C2：启动结果显式化 —— 目录缺失/无法监听返回 false，Hub 据此决定重试。 */
    fun start(): Boolean {
        if (!dir.exists() || !dir.isDirectory) {
            Logger.w(TAG, "download dir missing: $watchDir")
            return false
        }
        stop()
        val mask = FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        observer = object : FileObserver(watchDir, mask) {
            override fun onEvent(event: Int, path: String?) {
                if (path == null) return
                when (event) {
                    CREATE, CLOSE_WRITE, MOVED_TO -> {
                        try {
                            onNewFile(File(watchDir, path).absolutePath)
                        } catch (t: Throwable) {
                            Logger.w(TAG, "onEvent dispatch failed reason=${t.message}")
                        }
                    }
                }
            }
        }.also { it.startWatching() }
        Logger.i(TAG, "watching $watchDir")
        return true
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    companion object {
        private const val TAG = "niki914_nexus_DownloadObs"

        fun downloadDirAvailable(): Boolean = resolveDownloadDir().let { it.exists() && it.isDirectory }

        /**
         * C2：Environment API 优先（当前用户语义），老路径兜底（个别 ROM 在
         * 多用户挂载点上行为不一，保留旧行为的可用性）。
         */
        @Suppress("DEPRECATION")
        private fun resolveDownloadDir(): File {
            val resolved = runCatching {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }.getOrNull()
            return if (resolved != null && resolved.absolutePath.isNotBlank()) {
                resolved
            } else {
                File("/storage/emulated/0/Download")
            }
        }
    }
}
