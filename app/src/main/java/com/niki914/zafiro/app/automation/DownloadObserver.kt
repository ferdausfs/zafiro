package com.niki914.zafiro.app.automation

import android.content.Context
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

    private val dir: File = File("/storage/emulated/0/Download")
    private val watchDir: String = dir.absolutePath

    private var observer: FileObserver? = null

    fun start() {
        if (!dir.exists() || !dir.isDirectory) {
            Logger.w(TAG, "download dir missing: $watchDir")
            return
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
    }

    fun stop() {
        observer?.stopWatching()
        observer = null
    }

    companion object {
        private const val TAG = "niki914_nexus_DownloadObs"

        fun downloadDirAvailable(): Boolean =
            File("/storage/emulated/0/Download").exists()
    }
}
