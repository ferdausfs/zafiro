package com.niki914.zafiro.chat.agentic.buildin.impl

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.niki914.zafiro.chat.agentic.samsung.SamsungDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * samsung 工具（v1.8.0 Samsung-Optimized Agent）。
 *
 * One UI 专属能力探针与快捷入口：
 *  - status：One UI 版本 / 导航方式 / 深色模式 / 省电模式 / 电池豁免 / 三星生态应用
 *  - nav_mode：当前导航方案 + One UI 手势速查（实时视觉操作界面的前置知识）
 *  - apps：已安装的三星生态应用（label + 版本）
 *  - open_battery_settings：直达 One UI 电池/设备维护页（深度睡眠应用防线）
 *  - storage_profile：三星标准媒体目录画像（DCIM/Screenshots/Recordings…）
 *
 * 全部只读（open_battery_settings 只打开系统页面，不改任何设置），免新权限。
 */
class SamsungBuiltin : BuiltinTool() {

    override val name: String = "samsung"

    override val description: String = """
Samsung/One UI device intelligence — instant API probes for Samsung phones,
no UI automation needed. Actions:
- status: One UI version, navigation scheme, dark mode, power saving, battery
  exemption state, available Samsung ecosystem apps (Device Care, Edge panels,
  Bixby, Modes and Routines...).
- nav_mode: active navigation scheme + One UI gesture cheat-sheet to use with
  screen automation (home/recents/back gestures, edge panels, split screen).
- apps: list installed Samsung apps with labels and versions.
- open_battery_settings: deep-link One UI battery / Device Care so the user can
  exempt Zafiro from battery optimization (Deep sleeping apps defense).
- storage_profile: map Samsung standard media folders (DCIM/Camera,
  Screenshots, Download, Recordings...) with existence and file counts.
On a Samsung device, call status first when a task touches One UI behavior.
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

            when (args.action) {
                ACTION_STATUS -> readStatus(context)
                ACTION_NAV_MODE -> readNavMode(context)
                ACTION_APPS -> readSamsungApps(context, args.limit)
                ACTION_OPEN_BATTERY -> openBatterySettings(context)
                ACTION_STORAGE_PROFILE -> readStorageProfile(context)
                else -> BuiltinToolResult.failure(
                    code = "UNKNOWN_ACTION",
                    message = "Unknown action '${args.action}'.",
                    hint = "Valid actions: $ACTION_STATUS, $ACTION_NAV_MODE, $ACTION_APPS, " +
                            "$ACTION_OPEN_BATTERY, $ACTION_STORAGE_PROFILE."
                )
            }
        }

    // --------------------------------------------------------------- status

    private fun readStatus(context: Context): BuiltinToolResult {
        val samsungApps = SamsungDevice.availableSamsungApps(context)
        return BuiltinToolResult.success(
            message = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, One UI " +
                    "${SamsungDevice.oneUiVersion()}, ${SamsungDevice.navigationMode(context)} nav.",
            data = buildJsonObject {
                put("is_samsung", SamsungDevice.isSamsungManufacturer)
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("one_ui_version", SamsungDevice.oneUiVersion())
                put("navigation_mode", SamsungDevice.navigationMode(context))
                put("dark_mode", SamsungDevice.isDarkMode(context))
                put("power_saving", SamsungDevice.isPowerSaving(context))
                put("battery_optimization_ignored", SamsungDevice.isIgnoringBatteryOptimizations(context))
                put("device_care_available", SamsungDevice.deviceCareAvailable(context))
                put("samsung_apps", kotlinx.serialization.json.JsonArray(
                    samsungApps.map { kotlinx.serialization.json.JsonPrimitive(it) }))
            },
            hint = "battery_optimization_ignored=false means One UI may deep-sleep Zafiro's " +
                    "proactive service; use open_battery_settings to guide the user to exempt it."
        )
    }

    // ------------------------------------------------------------- nav_mode

    private fun readNavMode(context: Context): BuiltinToolResult {
        val mode = SamsungDevice.navigationMode(context)
        val edgePanels = SamsungDevice.packageVisible(
            context, SamsungDevice.knownPackages["edge_panels"]!!
        )
        val guidance = when (mode) {
            SamsungDevice.NAV_MODE_GESTURE ->
                "Swipe up from bottom = home; swipe up and hold = recents; swipe inward " +
                        "from either side edge = back. Split screen / pop-up view: open " +
                        "recents, tap the app icon above the card. Edge panels (if enabled): " +
                        "swipe inward from the edge handle."
            SamsungDevice.NAV_MODE_THREE_BUTTON ->
                "Recents = square key, home = circle key, back = triangle key. Split screen: " +
                        "open recents, tap the app icon, choose 'Open in split screen view'."
            SamsungDevice.NAV_MODE_TWO_BUTTON ->
                "Back and home are buttons; recents is a swipe up on the pill. Split screen: " +
                        "recents, tap the app icon, 'Open in split screen view'."
            else ->
                "Navigation scheme unknown; observe the bottom bar in the live frame before " +
                        "issuing gesture or button commands."
        }
        return BuiltinToolResult.success(
            message = "One UI navigation: $mode (${if (edgePanels) "edge panels available" else "no edge panels"}).",
            data = buildJsonObject {
                put("mode", mode)
                put("edge_panels", edgePanels)
                put("one_ui_version", SamsungDevice.oneUiVersion())
                put("guidance", guidance)
            },
            hint = "Use guidance with live_screen + screen_operation gestures; verify each step " +
                    "against the live frame because One UI animations shift targets."
        )
    }

    // ----------------------------------------------------------------- apps

    private fun readSamsungApps(context: Context, limit: Int): BuiltinToolResult {
        val pm = context.packageManager
        val apps = mutableListOf<JsonObject>()
        try {
            val packages = if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
            for (info in packages) {
                if (apps.size >= limit) break
                val pkg = info.packageName
                if (!(pkg.startsWith("com.samsung") || pkg.startsWith("com.sec"))) continue
                val label = info.applicationInfo?.let { appInfo ->
                    try {
                        pm.getApplicationLabel(appInfo).toString()
                    } catch (t: Throwable) {
                        pkg
                    }
                } ?: pkg
                apps += buildJsonObject {
                    put("package", pkg)
                    put("label", label)
                    put("version", info.versionName ?: "unknown")
                }
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "samsung apps query failed: ${t.message}")
            return BuiltinToolResult.failure(
                code = "QUERY_FAILED",
                message = "Installed-package query failed: ${t.message ?: "unknown error"}.",
                hint = "Package visibility may be restricted; rely on status instead."
            )
        }
        return BuiltinToolResult.success(
            message = "Found ${apps.size} Samsung/SEC package(s) visible to Zafiro.",
            data = buildJsonObject {
                put("count", apps.size)
                put("apps", kotlinx.serialization.json.JsonArray(apps))
            },
            hint = "Only launcher-visible or explicitly queried Samsung packages are listed " +
                    "(Android package visibility rules)."
        )
    }

    // -------------------------------------------------- open_battery_settings

    private fun openBatterySettings(context: Context): BuiltinToolResult {
        val tried = mutableListOf<String>()

        // 1) Device Care battery page（多数 One UI 版本）
        try {
            val intent = Intent().setComponent(
                ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                )
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return BuiltinToolResult.success(
                message = "Opened One UI Device Care → Battery.",
                data = buildJsonObject { put("path", "device_care_battery") },
                hint = "Ask the user: Battery → Background usage limits → Deep sleeping apps, " +
                        "then remove Zafiro from the list and disable 'Put unused apps to sleep'."
            )
        } catch (t: Throwable) {
            tried.add("device_care_battery")
        }

        // 2) Device Care 主页
        try {
            val launch = context.packageManager.getLaunchIntentForPackage("com.samsung.android.lool")
            if (launch != null) {
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return BuiltinToolResult.success(
                    message = "Opened One UI Device Care (main page).",
                    data = buildJsonObject { put("path", "device_care_main") },
                    hint = "Ask the user: Battery → Background usage limits → Deep sleeping apps."
                )
            }
        } catch (t: Throwable) {
            tried.add("device_care_main")
        }

        // 3) 系统电池优化设置
        try {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return BuiltinToolResult.success(
                message = "Opened Android battery optimization settings (Device Care unavailable).",
                data = buildJsonObject { put("path", "battery_optimization") },
                hint = "Ask the user to find Zafiro in 'All apps' and select 'Don't optimize'."
            )
        } catch (t: Throwable) {
            tried.add("battery_optimization")
        }

        return BuiltinToolResult.failure(
            code = "OPEN_FAILED",
            message = "Could not open any battery settings page (tried: ${tried.joinToString()}).",
            hint = "Tell the user to open Settings manually → Battery → Background usage limits."
        )
    }

    // ----------------------------------------------------- storage_profile

    private fun readStorageProfile(context: Context): BuiltinToolResult {
        val root = Environment.getExternalStorageDirectory()
        if (root == null || !root.canRead()) {
            return BuiltinToolResult.failure(
                code = "STORAGE_ACCESS_DENIED",
                message = "Shared storage is not readable (All-files access not granted?).",
                hint = "Guide the user to Zafiro Settings → System Integration → All files access."
            )
        }
        val folders = SAMSUNG_FOLDERS.map { relative ->
            val dir = java.io.File(root, relative)
            buildJsonObject {
                put("path", "${root.absolutePath}/$relative")
                put("exists", dir.isDirectory)
                if (dir.isDirectory) {
                    val children = dir.listFiles()
                    val files = children?.filter { it.isFile }.orEmpty()
                    put("files", files.size)
                    put("sub_dirs", children?.count { it.isDirectory } ?: 0)
                    put("bytes", files.sumOf { it.length() })
                }
            }
        }
        val present = folders.count {
            (it["exists"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
        }
        return BuiltinToolResult.success(
            message = "Samsung storage profile: $present/${folders.size} standard media folders present.",
            data = buildJsonObject {
                put("root", root.absolutePath)
                put("folders", kotlinx.serialization.json.JsonArray(folders))
            },
            hint = "Cloud-synced Samsung Cloud / Gallery sync content is NOT on disk — only " +
                    "local files appear. Use file_manager (sort_folder mode 'samsung') to organize."
        )
    }

    // -------------------------------------------------------------- helpers

    private fun parseArguments(argumentsJson: String): SamsungArguments {
        val obj = if (argumentsJson.isBlank()) JsonObject(emptyMap())
        else try {
            Json.parseToJsonElement(argumentsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (t: Throwable) {
            JsonObject(emptyMap())
        }
        return SamsungArguments(
            action = obj.string("action").ifBlank { "" },
            limit = obj.string("limit").toIntOrNull()?.coerceIn(1, 100) ?: 30,
        )
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private data class SamsungArguments(val action: String, val limit: Int)

    private companion object {
        private const val LOG_TAG = "niki914_nexus_SamsungBuiltin"

        private const val ACTION_STATUS = "status"
        private const val ACTION_NAV_MODE = "nav_mode"
        private const val ACTION_APPS = "apps"
        private const val ACTION_OPEN_BATTERY = "open_battery_settings"
        private const val ACTION_STORAGE_PROFILE = "storage_profile"

        /** 三星/One UI 标准媒体目录（相对共享存储根）。 */
        private val SAMSUNG_FOLDERS = listOf(
            "DCIM/Camera",
            "DCIM/Screenshots",
            "Pictures/Screenshots",
            "Download",
            "Documents",
            "Pictures",
            "Music",
            "Movies",
            "Recordings",
            "Voice Recorder",
            "Android/media",
        )

        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["status", "nav_mode", "apps", "open_battery_settings", "storage_profile"],
      "description": "Which Samsung/One UI capability to query."
    },
    "limit": {
      "type": "integer",
      "description": "apps only: max packages to return (1-100, default 30)."
    }
  },
  "required": ["action"]
}
        """.trimIndent()
    }
}
