package com.niki914.permission

import android.content.ComponentName
import com.niki914.logging.Logger

/**
 * shell 通道共用的授权命令（RootShell / Shizuku 共用，命令文本与 merge 逻辑只此一份）。
 * [run] 返回 null = 进程创建/执行失败（Shizuku 侧）；RootShell 侧永不 null，传 `{ cmd -> run(cmd) }` 即可。
 */
internal object ShellGrants {

    suspend fun grantOverlay(
        run: suspend (String) -> ShellOutcome?,
        packageName: String,
    ): PermissionState {
        val command = "appops set $packageName SYSTEM_ALERT_WINDOW allow"
        val outcome = run(command)
        Logger.d(TAG, "exec [$command] exit=${outcome?.exitCode} stdoutLines=${outcome?.stdout?.size}")
        return if (outcome?.isSuccess == true) PermissionState.GRANTED else PermissionState.FAILED
    }

    suspend fun grantAccessibility(
        run: suspend (String) -> ShellOutcome?,
        service: ComponentName?,
    ): PermissionState {
        val svc = requireNotNull(service) {
            "accessibilityService is required for Permission.ACCESSIBILITY"
        }
        val get = run("settings get secure enabled_accessibility_services")
            ?: return PermissionState.FAILED
        val merged = mergeServices(get.stdout, svc)
        val put1 = run("settings put secure enabled_accessibility_services $merged")
            ?: return PermissionState.FAILED
        if (!put1.isSuccess) return PermissionState.FAILED
        val put2 = run("settings put secure accessibility_enabled 1")
            ?: return PermissionState.FAILED
        return if (put2.isSuccess) PermissionState.GRANTED else PermissionState.FAILED
    }

    /**
     * 通知授权：POST_NOTIFICATIONS 是运行时权限（API 33+），shell 能代授权。
     *
     * 两条命令都试：`pm grant` 走运行时权限表，`appops set POST_NOTIFICATION` 走通知开关，
     * 不同 ROM 对二者的接受程度不一致。命令退出码不等于系统里的权限状态，因此每条命令后都用
     * [verify] 复查真实状态收尾。
     *
     * 返回 DENIED_BY_USER = 命令被接受但系统仍未授权（链继续降级到弹窗）；
     * 返回 FAILED = 两条命令都没跑起来（进程创建失败）。
     */
    suspend fun grantNotification(
        run: suspend (String) -> ShellOutcome?,
        packageName: String,
        verify: () -> PermissionState,
    ): PermissionState {
        val commands = listOf(
            "pm grant $packageName android.permission.POST_NOTIFICATIONS",
            "appops set $packageName POST_NOTIFICATION allow",
        )
        var anyAccepted = false
        for (command in commands) {
            val outcome = run(command) ?: continue
            anyAccepted = anyAccepted || outcome.isSuccess
            Logger.d(TAG, "exec [$command] exit=${outcome.exitCode} stdoutLines=${outcome.stdout.size}")
            if (verify() == PermissionState.GRANTED) return PermissionState.GRANTED
        }
        return if (anyAccepted) PermissionState.DENIED_BY_USER else PermissionState.FAILED
    }

    /**
     * Phase 2 看门狗：电池优化白名单（root/Shizuku 静默链）。
     *
     * 三条命令逐一执行：
     *  - dumpsys deviceidle whitelist +pkg —— 系统级电池优化豁免表（等价用户在
     *    设置里选「不优化」，PowerManager.isIgnoringBatteryOptimizations 随之变 true）
     *  - appops RUN_IN_BACKGROUND allow —— API 26-27 的后台运行 op
     *  - appops RUN_ANY_IN_BACKGROUND allow —— API 28+ 的后台运行 op
     *
     * ROM 差异：部分 One UI 版本会拒收某一条 appops（exit != 0），但不影响
     * 其它命令生效 —— 主白名单命令（dumpsys）成功 + 任一 appops 成功即算成。
     *
     * @return 是否已取得有效白名单（进程内无法直接验证时保守返回命令接受度）
     */
    suspend fun whitelistBatteryOptimizations(
        run: suspend (String) -> ShellOutcome?,
        packageName: String,
    ): Boolean {
        val commands = listOf(
            "dumpsys deviceidle whitelist +$packageName",
            "appops set $packageName RUN_IN_BACKGROUND allow",
            "appops set $packageName RUN_ANY_IN_BACKGROUND allow",
        )
        var accepted = 0
        for (command in commands) {
            val outcome = run(command) ?: return false // 进程创建失败：本通道不可用
            Logger.d(TAG, "exec [$command] exit=${outcome.exitCode}")
            if (outcome.isSuccess) accepted++
        }
        return accepted >= 2
    }

    private fun mergeServices(stdout: List<String>, service: ComponentName): String =
        stdout.joinToString("").trim()
            .takeUnless { it.isBlank() || it == "null" }
            ?.split(":")
            .orEmpty()
            .filter { it.isNotBlank() }
            .plus(service.flattenToShortString())
            .distinct()
            .joinToString(":")

    private const val TAG = "ShellGrants"
}
