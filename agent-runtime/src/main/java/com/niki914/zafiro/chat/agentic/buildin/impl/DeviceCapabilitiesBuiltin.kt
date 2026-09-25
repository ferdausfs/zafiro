package com.niki914.zafiro.chat.agentic.buildin.impl

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import com.niki914.libterm.TerminalFailure
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.shell.TerminalCommandOutcome
import com.niki914.zafiro.chat.agentic.shell.TerminalSessionPool
import com.niki914.zafiro.chat.agentic.shell.TerminalToolResponse.stdoutText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * device_capabilities 工具（v2.0.0 Jarvis Mode）。
 *
 * 一站式能力体检 + 静默自修：
 *  - check   —— 探测全部能力（root / Shizuku / 无障碍 / 悬浮窗 / 通知 / 存储 /
 *    运行时权限 / 电池豁免 / 精确闹钟），返回结构化 JSON；
 *  - autofix —— 在 check 之上，若有特权 shell（root 优先，Shizuku 兜底），
 *    直接用 shell 替用户把能授的权限全部授掉（pm grant / appops / settings put /
 *    dumpsys deviceidle），再逐项复核，剩下真正需要人工的项给出精确指引。
 *
 * 设计动机：此前 agent 动辄以 "system blocked it / permission missing" 收场 ——
 * 因为权限缺口只能在各工具失败时才暴露。本工具让 agent 在行动前一次拿到全部
 * 能力状态并自动补齐，从源头消灭"被系统挡住"类回复。
 *
 * 授权命令文本与 libs/permission-manager 的 ShellGrants 保持一致（该对象是
 * internal，跨模块不引用，命令语义收敛在两处注释互指）。
 */
class DeviceCapabilitiesBuiltin(
    private val openAndExecute: suspend (String, String?, String, Long) -> TerminalCommandOutcome =
        { identity, cwd, command, timeoutMs ->
            TerminalSessionPool.openAndExecute(identity, cwd, command, timeoutMs)
        },
    private val probeTimeoutMs: Long = 15_000L,
    private val commandTimeoutMs: Long = 10_000L,
) : BuiltinTool() {

    override val name: String = "device_capabilities"

    override val description: String = """
Check AND auto-fix every device capability in one call — privileged shell (root/shizuku),
accessibility service, overlay, notifications, contacts/calendar/location permissions,
all-files storage, battery-optimization whitelist, exact alarms. Actions:
- check:   probe all capabilities and return their status (no changes).
- autofix: probe, then SILENTLY grant everything a privileged shell can grant
           (runtime permissions, overlay, accessibility service, battery whitelist),
           re-verify, and report exactly what still needs the user.
Call autofix BEFORE any system-level operation, and ALWAYS after any tool fails with a
permission/authorization error — do not tell the user "the system blocked it" without
running autofix first in that turn. Note: the first root probe may pop the Magisk/su
authorization dialog once; that is expected and grants persistent shell access.
    """.trimIndent()

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
        withContext(Dispatchers.IO) {
            val args = parseArguments(request.argumentsJson)
            val context = try {
                ContextProvider.await().applicationContext
            } catch (t: Throwable) {
                null
            } ?: return@withContext BuiltinToolResult.failure(
                code = "CONTEXT_UNAVAILABLE",
                message = "Application context is not initialized yet.",
                hint = "Retry shortly; the host app may still be starting."
            )

            val report = probePrivileged()
            val capabilities = probeCapabilities(context)
            val privileged = report.rootOk || report.shizukuOk
            val privilegedIdentity = when {
                report.rootOk -> IDENTITY_ROOT
                report.shizukuOk -> IDENTITY_SHIZUKU
                else -> null
            }

            var applied: List<String> = emptyList()
            var failedFixes: List<String> = emptyList()
            if (args.autofix && privilegedIdentity != null) {
                val outcome = autoFix(context, privilegedIdentity, report)
                applied = outcome.applied
                failedFixes = outcome.failed
            }

            // 自修后以系统真实状态复核（而非命令退出码）
            val verified = if (args.autofix && privilegedIdentity != null) {
                probeCapabilities(context)
            } else {
                capabilities
            }
            val needsUser = needsUserSteps(report, verified, context)
            val summary = buildSummary(args.autofix, privilegedIdentity, applied, verified, needsUser)

            val data = buildJsonObject {
                put("privileged_identity", privilegedIdentity ?: "none")
                put("root", report.rootState)
                put("shizuku", report.shizukuState)
                put("capabilities", capabilityJson(verified))
                if (args.autofix) {
                    put("autofix_applied", buildJsonObject {
                        applied.forEach { put(it, true) }
                    })
                    if (failedFixes.isNotEmpty()) {
                        put("autofix_failed", buildJsonObject {
                            failedFixes.forEach { put(it, true) }
                        })
                    }
                }
                put("needs_user", buildJsonObject {
                    needsUser.forEachIndexed { index, step -> put("step_${index + 1}", step) }
                })
            }

            Logger.i(
                TAG,
                "action=${args.action} privileged=$privilegedIdentity applied=${applied.size} " +
                        "needsUser=${needsUser.size}",
            )
            BuiltinToolResult.success(message = summary, data = data)
        }

    // ------------------------------------------------------------- probes

    private data class PrivilegedReport(
        val rootState: String,
        val shizukuState: String,
        val rootOk: Boolean,
        val shizukuOk: Boolean,
    )

    private suspend fun probePrivileged(): PrivilegedReport {
        val root = probeIdentity(IDENTITY_ROOT, expectedUid = "0")
        val shizuku = probeIdentity(IDENTITY_SHIZUKU, expectedUid = "2000")
        return PrivilegedReport(
            rootState = root.state,
            shizukuState = shizuku.state,
            rootOk = root.ok,
            shizukuOk = shizuku.ok,
        )
    }

    private data class IdentityProbe(val ok: Boolean, val state: String)

    private suspend fun probeIdentity(identity: String, expectedUid: String): IdentityProbe {
        return try {
            when (val outcome = openAndExecute(identity, null, "id -u", probeTimeoutMs)) {
                is TerminalCommandOutcome.Success ->
                    if (outcome.result.stdoutText().trim() == expectedUid) {
                        IdentityProbe(ok = true, state = "granted")
                    } else {
                        IdentityProbe(ok = false, state = "unexpected_output")
                    }

                is TerminalCommandOutcome.Timeout ->
                    IdentityProbe(ok = false, state = "authorization_timeout")

                is TerminalCommandOutcome.Failure -> IdentityProbe(
                    ok = false,
                    state = when (outcome.failure) {
                        is TerminalFailure.BackendUnavailable -> "unavailable"
                        is TerminalFailure.AuthorizationDenied -> "denied"
                        is TerminalFailure.AuthorizationFailed -> "denied"
                        else -> "failed"
                    },
                )

                else -> IdentityProbe(ok = false, state = "failed")
            }
        } catch (t: Throwable) {
            Logger.w(TAG, "probe $identity error: ${t.message}")
            IdentityProbe(ok = false, state = "failed")
        }
    }

    private data class CapabilitySnapshot(
        val accessibility: Boolean,
        val overlay: Boolean,
        val notifications: Boolean,
        val allFiles: Boolean,
        val contacts: Boolean,
        val calendar: Boolean,
        val location: Boolean,
        val batteryWhitelist: Boolean,
        val exactAlarms: Boolean,
    )

    private fun probeCapabilities(context: Context): CapabilitySnapshot {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val alarmManager =
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return CapabilitySnapshot(
            accessibility = context.isAccessibilityServiceEnabled(),
            overlay = Settings.canDrawOverlays(context),
            notifications = notificationManager.areNotificationsEnabled(),
            allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.isPermissionGranted(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            },
            contacts = context.isPermissionGranted(android.Manifest.permission.READ_CONTACTS),
            calendar = context.isPermissionGranted(android.Manifest.permission.READ_CALENDAR),
            location = context.isPermissionGranted(android.Manifest.permission.ACCESS_FINE_LOCATION),
            batteryWhitelist = powerManager.isIgnoringBatteryOptimizations(context.packageName),
            exactAlarms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alarmManager.canScheduleExactAlarms()
            } else {
                true
            },
        )
    }

    // ------------------------------------------------------------- autofix

    private data class FixOutcome(val applied: List<String>, val failed: List<String>)

    private suspend fun autoFix(
        context: Context,
        identity: String,
        report: PrivilegedReport,
    ): FixOutcome {
        val pkg = context.packageName
        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()

        suspend fun attempt(label: String, vararg commands: String) {
            var accepted = 0
            for (command in commands) {
                val outcome = try {
                    openAndExecute(identity, null, command, commandTimeoutMs)
                } catch (t: Throwable) {
                    Logger.w(TAG, "fix [$command] error: ${t.message}")
                    null
                }
                if (outcome is TerminalCommandOutcome.Success) accepted++
            }
            // ROM 差异：个别 appops 可能被拒收，只要命令进程能跑就算通道可用，
            // 真实结果由调用方之后的 re-verify 决定
            if (accepted > 0) applied += label else failed += label
        }

        attempt(
            "contacts",
            "pm grant $pkg android.permission.READ_CONTACTS",
        )
        attempt(
            "calendar",
            "pm grant $pkg android.permission.READ_CALENDAR",
        )
        attempt(
            "location",
            "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
        )
        attempt(
            "notifications",
            "pm grant $pkg android.permission.POST_NOTIFICATIONS",
            "appops set $pkg POST_NOTIFICATION allow",
        )
        attempt(
            "overlay",
            "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        )
        attempt(
            "all_files",
            "appops set $pkg MANAGE_EXTERNAL_STORAGE allow",
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            attempt(
                "exact_alarms",
                "appops set $pkg SCHEDULE_EXACT_ALARM allow",
            )
        }
        attempt(
            "battery_whitelist",
            "dumpsys deviceidle whitelist +$pkg",
            "appops set $pkg RUN_IN_BACKGROUND allow",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow",
        )

        // 无障碍服务：读当前值 → 合并本应用服务 → 写回（与 ShellGrants.grantAccessibility 同语义）
        if (!context.isAccessibilityServiceEnabled()) {
            val current = execText(identity, "settings get secure enabled_accessibility_services")
            val merged = (listOf(current)
                .flatMap { it.split(":") }
                .map(String::trim)
                .filter { it.isNotBlank() && it != "null" } +
                    accessibilityComponent(context).flattenToShortString())
                .distinct()
                .joinToString(":")
            attempt(
                "accessibility",
                "settings put secure enabled_accessibility_services $merged",
                "settings put secure accessibility_enabled 1",
            )
        }

        return FixOutcome(applied = applied, failed = failed)
    }

    private suspend fun execText(identity: String, command: String): String {
        return try {
            val outcome = openAndExecute(identity, null, command, commandTimeoutMs)
            if (outcome is TerminalCommandOutcome.Success) outcome.result.stdoutText().trim() else ""
        } catch (t: Throwable) {
            ""
        }
    }

    // ------------------------------------------------------------- reporting

    private fun needsUserSteps(
        report: PrivilegedReport,
        caps: CapabilitySnapshot,
        context: Context,
    ): List<String> {
        val steps = mutableListOf<String>()
        if (!report.rootOk && !report.shizukuOk) {
            steps += when {
                report.shizukuState == "unavailable" ->
                    "No privileged shell: open the Shizuku app and tap Start " +
                            "(Developer options > Wireless debugging), or grant root in Magisk. " +
                            "Then run device_capabilities autofix again — everything else is " +
                            "granted automatically."
                else ->
                    "No privileged shell: root was denied and Shizuku is not authorized. " +
                            "Grant root in Magisk, or start Shizuku and allow Zafiro, then rerun autofix."
            }
        }
        if (!caps.accessibility) {
            steps += "Accessibility service is still off: open Settings > Accessibility > " +
                    "Zafiro and enable it (shell grant can be rejected by some One UI builds)."
        }
        if (!caps.allFiles) {
            steps += "All-files access is still off: Settings > Apps > Zafiro > " +
                    "Permissions > Files and media > Allow management of all files."
        }
        if (!caps.exactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            steps += "Exact alarms are not permitted: Settings > Apps > Zafiro > " +
                    "Alarms & reminders > Allow."
        }
        return steps
    }

    private fun buildSummary(
        autofix: Boolean,
        privilegedIdentity: String?,
        applied: List<String>,
        caps: CapabilitySnapshot,
        needsUser: List<String>,
    ): String {
        val missing = missingCapabilities(caps)
        return if (!autofix) {
            buildString {
                append("Capability check: ")
                append("privileged shell=${privilegedIdentity ?: "none"}. ")
                append(if (missing.isEmpty()) "Everything is granted." else "Missing: ${missing.joinToString()}.")
                append(" Run action=autofix to grant what a privileged shell can.")
            }
        } else {
            buildString {
                append("Autofix finished with ${privilegedIdentity ?: "no"} privileged shell. ")
                if (applied.isNotEmpty()) {
                    append("Granted via shell: ${applied.joinToString()}. ")
                }
                append(
                    if (missing.isEmpty() && needsUser.isEmpty()) {
                        "All capabilities are now available."
                    } else {
                        "Still missing: ${(missing + needsUser).joinToString("; ")}."
                    }
                )
            }
        }
    }

    private fun missingCapabilities(caps: CapabilitySnapshot): List<String> {
        val missing = mutableListOf<String>()
        if (!caps.accessibility) missing += "accessibility"
        if (!caps.overlay) missing += "overlay"
        if (!caps.notifications) missing += "notifications"
        if (!caps.allFiles) missing += "all_files"
        if (!caps.contacts) missing += "contacts"
        if (!caps.calendar) missing += "calendar"
        if (!caps.location) missing += "location"
        if (!caps.batteryWhitelist) missing += "battery_whitelist"
        if (!caps.exactAlarms) missing += "exact_alarms"
        return missing
    }

    private fun capabilityJson(caps: CapabilitySnapshot): JsonObject = buildJsonObject {
        put("accessibility", caps.accessibility)
        put("overlay", caps.overlay)
        put("notifications", caps.notifications)
        put("all_files", caps.allFiles)
        put("contacts", caps.contacts)
        put("calendar", caps.calendar)
        put("location", caps.location)
        put("battery_whitelist", caps.batteryWhitelist)
        put("exact_alarms", caps.exactAlarms)
    }

    // ------------------------------------------------------------- helpers

    private fun Context.isPermissionGranted(permission: String): Boolean =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun Context.isAccessibilityServiceEnabled(): Boolean {
        val component = accessibilityComponent(this)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(":").any { entry ->
            entry == component.flattenToShortString() ||
                    entry == component.flattenToString() ||
                    (entry.substringBefore("/") == packageName &&
                            entry.substringAfter("/", "").let { cls ->
                                cls == ACCESSIBILITY_CLASS || cls == ACCESSIBILITY_CLASS_SHORT
                            })
        }
    }

    private fun accessibilityComponent(context: Context): ComponentName =
        ComponentName(context.packageName, ACCESSIBILITY_CLASS)

    private data class ToolArgs(val action: String) {
        val autofix: Boolean get() = action == ACTION_AUTOFIX
    }

    private fun parseArguments(argumentsJson: String): ToolArgs {
        val action = try {
            kotlinx.serialization.json.Json.parseToJsonElement(argumentsJson)
                .let { it as? JsonObject }
                ?.get("action")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
                .ifBlank { ACTION_CHECK }
        } catch (t: Throwable) {
            ACTION_CHECK
        }
        return ToolArgs(
            action = if (action in setOf(ACTION_CHECK, ACTION_AUTOFIX)) action else ACTION_CHECK
        )
    }

    companion object {
        private const val TAG = "DeviceCapabilitiesBuiltin"
        private const val ACTION_CHECK = "check"
        private const val ACTION_AUTOFIX = "autofix"
        private const val IDENTITY_ROOT = "root"
        private const val IDENTITY_SHIZUKU = "shizuku"
        private const val ACCESSIBILITY_CLASS =
            "com.niki914.zafiro.mod.feat.ZafiroAccessibilityService"
        private const val ACCESSIBILITY_CLASS_SHORT = ".mod.feat.ZafiroAccessibilityService"

        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["check", "autofix"],
      "description": "check = probe only (default); autofix = probe + silently grant everything a privileged shell can grant."
    }
  },
  "required": []
}
        """.trim()
    }
}
