package com.niki914.zafiro.repo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.niki914.zafiro.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * v2.1.2 in-app 自更新：下载 release APK（带进度/可取消）→ PackageInstaller 会话安装
 * → 系统状态回调映射为用户可读的错误（签名不一致 / 空间不足 / 被拦截…）。
 * 此前更新 = 跳浏览器手动下载安装，是"每次更新都装不上"的根源（文件下错/下载损坏）。
 *
 * 线程模型：自有 scope（不随页面销毁），状态经 StateFlow 暴露，UI 任意时刻可订阅。
 */
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState
    data class Downloading(val receivedBytes: Long, val totalBytes: Long) : UpdateInstallState
    /** 系统确认弹窗已交出（STATUS_PENDING_USER_ACTION 已 start）或等待用户确认 */
    data object Installing : UpdateInstallState
    data class Failed(
        @StringRes val reasonRes: Int,
        val detail: String,
    ) : UpdateInstallState
}

object UpdateInstaller {

    private val _state = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val state: StateFlow<UpdateInstallState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeCall: okhttp3.Call? = null
    private val cancelled = AtomicBoolean(false)
    private var receiver: BroadcastReceiver? = null

    /** APK 合理下限；实际包 29MB+，小于此值视为下载损坏 */
    internal const val MIN_APK_BYTES: Long = 10L * 1024 * 1024

    private const val ACTION_INSTALL_STATUS =
        "com.niki914.zafiro.repo.UpdateInstaller.INSTALL_STATUS"

    fun start(context: Context, url: String, fileName: String) {
        val current = _state.value
        if (current is UpdateInstallState.Downloading || current is UpdateInstallState.Installing) return
        cancelled.set(false)
        val appContext = context.applicationContext
        scope.launch {
            runCatching { downloadAndInstall(appContext, url, fileName) }
                .onFailure { t ->
                    if (cancelled.get()) {
                        _state.value = UpdateInstallState.Idle
                    } else {
                        _state.value = UpdateInstallState.Failed(
                            mapDownloadFailure(t),
                            t.message.orEmpty().take(200),
                        )
                    }
                }
        }
    }

    /** 仅取消下载；系统安装确认阶段不可取消（由系统 UI 管理） */
    fun cancel() {
        cancelled.set(true)
        activeCall?.cancel()
        if (_state.value is UpdateInstallState.Downloading) {
            _state.value = UpdateInstallState.Idle
        }
    }

    private suspend fun downloadAndInstall(context: Context, url: String, fileName: String) {
        val dir = File(context.filesDir, "updates").apply { mkdirs() }
        // 清掉历史版本残留，只保留本次目标
        dir.listFiles()?.forEach { if (it.name != fileName) it.delete() }
        val part = File(dir, "$fileName.part")
        val out = File(dir, fileName)

        if (!(out.exists() && out.length() >= MIN_APK_BYTES)) {
            streamDownload(url, part, out)
        }
        installApk(context, out)
    }

    private suspend fun streamDownload(url: String, part: File, out: File) {
        _state.value = UpdateInstallState.Downloading(0, 0)
        part.delete()
        // 大文件下载：clone 出更长超时的 client（SharedHttp base 保持不动，符合其使用约定）
        val client = SharedHttp.client.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val call = client.newCall(Request.Builder().url(url).build())
        activeCall = call
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("empty body")
            val total = body.contentLength()
            var lastPct = -1
            body.byteStream().use { input ->
                FileOutputStream(part).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    var received = 0L
                    while (true) {
                        if (cancelled.get()) throw IOException("cancelled")
                        val n = input.read(buf)
                        if (n == -1) break
                        fos.write(buf, 0, n)
                        received += n
                        // 1% 粒度刷新，避免 64KB 一刷的无效重组
                        if (total > 0) {
                            val pct = (received * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = UpdateInstallState.Downloading(received, total)
                            }
                        }
                    }
                    fos.flush()
                    fos.fd.sync()
                }
            }
        }
        if (part.length() < MIN_APK_BYTES) {
            part.delete()
            throw IOException("apk too small: ${part.length()}")
        }
        if (!part.renameTo(out)) {
            out.delete()
            if (!part.renameTo(out)) throw IOException("rename failed")
        }
    }

    private fun installApk(context: Context, apk: File) {
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            // UI 层通常已提前引导授权；这里兜底提示而非静默失败
            _state.value = UpdateInstallState.Failed(
                R.string.update_need_install_permission,
                "canRequestPackageInstalls=false",
            )
            return
        }
        val installer = pm.packageInstaller
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        )
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("zafiro_update.apk", 0, apk.length()).use { sink ->
                apk.inputStream().use { src -> src.copyTo(sink) }
                session.fsync(sink)
            }
            // 每次安装一个随机 action：即便 receiver exported 也无法被第三方伪造
            val action = "$ACTION_INSTALL_STATUS.${UUID.randomUUID()}"
            val rcv = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = handleStatus(c, i)
            }
            ContextCompat.registerReceiver(
                context, rcv, IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiver = rcv
            // 系统要回填 EXTRA_STATUS / EXTRA_INTENT → 必须 FLAG_MUTABLE
            val pi = PendingIntent.getBroadcast(
                context,
                sessionId,
                Intent(action).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            session.commit(pi.intentSender)
        } catch (t: Throwable) {
            runCatching { session.abandon() }
            unregister(context)
            _state.value = UpdateInstallState.Failed(
                R.string.update_dialog_fail_generic,
                t.message.orEmpty().take(200),
            )
            return
        } finally {
            session.close()
        }
        _state.value = UpdateInstallState.Installing
    }

    private fun handleStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                // 弹窗交给系统；我们的状态停在 Installing
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // 替换安装后进程即被杀，无需处理
            else -> {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                _state.value = UpdateInstallState.Failed(
                    mapInstallFailure(status),
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(200),
                )
                unregister(context)
            }
        }
    }

    private fun unregister(context: Context) {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    /**
     * PackageInstaller 失败码 → 用户可读文案（纯函数，单测覆盖）。
     * INCOMPATIBLE 几乎只有一种来源：签名不一致（装过旧签名版本），给出一次性卸载指引。
     */
    internal fun mapInstallFailure(status: Int): Int = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED -> R.string.update_dialog_fail_blocked
        PackageInstaller.STATUS_FAILURE_ABORTED -> R.string.update_dialog_fail_aborted
        PackageInstaller.STATUS_FAILURE_INVALID -> R.string.update_dialog_fail_invalid
        PackageInstaller.STATUS_FAILURE_CONFLICT -> R.string.update_dialog_fail_conflict
        PackageInstaller.STATUS_FAILURE_STORAGE -> R.string.update_dialog_fail_storage
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> R.string.update_dialog_fail_incompatible
        else -> R.string.update_dialog_fail_generic
    }

    internal fun mapDownloadFailure(t: Throwable): Int = when {
        t.message == "cancelled" -> R.string.update_dialog_fail_aborted
        t.message?.startsWith("HTTP 4") == true -> R.string.update_dialog_fail_invalid
        else -> R.string.update_dialog_fail_network
    }
}
