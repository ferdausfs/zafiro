package com.niki914.zafiro.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors
import com.niki914.logging.Logger
import com.niki914.permission.Permission
import com.niki914.permission.PermissionState
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.app.automation.AutomationHub
import com.niki914.zafiro.app.automation.BackgroundTaskHub
import com.niki914.zafiro.app.automation.ServiceWatchdogWorker
import com.niki914.zafiro.app.automation.TimeTriggerWorker
import com.niki914.zafiro.app.conversation.ConversationPersister
import com.niki914.zafiro.app.conversation.ConversationRepo
import com.niki914.zafiro.app.overlay.ToolPermissionOverlay
import com.niki914.zafiro.chat.agentic.accessibility.AccessibilityController
import com.niki914.zafiro.chat.agentic.python.PyRuntime
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionCoordinator
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionRequest
import com.niki914.zafiro.chat.agentic.shell.ToolPermissionResponse
import com.niki914.zafiro.repo.UpdateCheckHolder
import com.niki914.zafiro.repo.XRepo
import com.niki914.zafiro.runtime.createAppRuntimeBridge
import com.niki914.zafiro.settings.RuntimeEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application(), androidx.work.Configuration.Provider {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Phase 2：WorkManager 按需初始化（默认 initializer 已在 manifest 移除）。
     * 只在主进程首次 getInstance 时装配；:python 进程不使用 WorkManager，
     * 与「主进程专属初始化」的整体策略一致。
     */
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // 日志 debug 门控：release 构建 DEBUG/VERBOSE 全停，仅 INFO+ 输出
        Logger.setDebugProvider { BuildConfig.DEBUG }
        // 非主进程（目前只有 `:python`）不初始化主进程状态：上下文与持久化只属于主进程
        //（否则 ContextProvider 从未 provide，PyRuntime.warmUp 会永远挂起）
        if (!isMainProcess()) return
        ContextProvider.provide(applicationContext)
        XRepo.init(this.applicationContext)
        ConversationRepo.init(this.applicationContext)
        // T3：消息级增量持久化器（观察 LLMController 当前会话快照流，
        // 独立于 UI 生命周期——回合可能在宿主后台跑，ViewModel 已销毁时仍落盘）
        ConversationPersister.start(applicationScope)
        RuntimeEnvironment.install(createAppRuntimeBridge())
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        DynamicColors.applyToActivitiesIfAvailable(this)
        applicationScope.launch {
            UpdateCheckHolder.runOnce(BuildConfig.VERSION_NAME)
        }
        applicationScope.launch {
            XRepo.tryPutDefaultSettings()
        }
        applicationScope.launch {
            XRepo.skills.seedDefaults()
        }
        applicationScope.launch {
            XRepo.seedPyTools()
        }
        applicationScope.launch {
            PyRuntime.warmUp()
        }

        // 主动自动化中枢：事件源 → 触发器匹配 → Agent 唤醒（主进程专属）
        AutomationHub.init(applicationContext, applicationScope)
        // Phase 2：时间触发器 WorkManager 周期兑底（AlarmManager 之外的第三层）
        TimeTriggerWorker.ensureScheduled(applicationContext)
        // Phase 2：服务看门狗（FGS 意外死亡后的重启 + 静默电池白名单）
        ServiceWatchdogWorker.ensureScheduled(applicationContext)
        // 后台任务中枢：回合与 UI 生命周期解耦（FGS 保活 + 完成通知）
        BackgroundTaskHub.init(applicationContext, applicationScope)
        // PDF 文本提取引擎（通用文档上传，com.tom-roush:pdfbox-android）
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)

        ToolPermissionCoordinator.backgroundConfirmationHandler = { request ->
            handleBackgroundConfirmation(this, request)
        }
        // 全部权限走 PermissionManager：ensureService 的门面注入（主 App 进程）。
        AccessibilityController.permissions = PermissionHolder.get(this)
    }

    private suspend fun handleBackgroundConfirmation(
        context: Context,
        request: ToolPermissionRequest,
    ): ToolPermissionResponse {
        // 挂起式等链路结果，不占线程；取消（Activity 销毁）时不吞，交由调用方协程处理
        val result = PermissionHolder.get(context).request(Permission.OVERLAY)
        if (result.finalState != PermissionState.GRANTED) {
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }
        // 窗口加不上（权限被收回等）≠ 用户拒绝：失败走 DENIED_UNAVAILABLE
        val allowed = try {
            ToolPermissionOverlay.show(context, request)
        } catch (_: Throwable) {
            return ToolPermissionResponse.DENIED_UNAVAILABLE
        }
        return if (allowed) {
            ToolPermissionResponse.ALLOWED
        } else {
            ToolPermissionResponse.DENIED_BY_USER
        }
    }

    /**
     * 是否主进程。进程名优先读 `/proc/self/cmdline`（内核直接给出命令行，
     * 不依赖框架侧的内存状态），`getMyMemoryState` 仅作兜底；
     * 两者都取不到进程名时按主进程处理——宁可多初始化，不能让主进程缺初始化。
     */
    private fun isMainProcess(): Boolean {
        val fromProc = runCatching {
            File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .decodeToString()
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        val name = fromProc ?: ActivityManager.RunningAppProcessInfo().also {
            ActivityManager.getMyMemoryState(it)
        }.processName?.takeIf { it.isNotEmpty() }
        return name == null || name == packageName
    }

}
