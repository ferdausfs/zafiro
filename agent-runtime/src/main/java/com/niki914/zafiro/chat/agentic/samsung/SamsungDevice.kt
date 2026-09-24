package com.niki914.zafiro.chat.agentic.samsung

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.niki914.logging.Logger

/**
 * v1.8.0 Samsung-Optimized Agent：三星设备 / One UI 探测层。
 *
 * 无需任何新权限，全部走公共 API：
 *  - Build.MANUFACTURER 判定三星
 *  - SystemProperties 反射读取 One UI 版本（ro.build.version.oneui）
 *  - Settings.Secure "navigation_mode" 读取导航方式（手势 / 三键 / 两键）
 *  - PackageManager 包可见性探测三星生态应用（Device Care、Edge Panel、Bixby…）
 *
 * 注意：Android 11+ 包可见性受 <queries> 限制，Manifest 中已声明关键三星包。
 */
object SamsungDevice {

    private const val LOG_TAG = "niki914_nexus_SamsungDevice"

    /** 三星设备判定（与品牌无关，严格看 manufacturer）。 */
    val isSamsungManufacturer: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    // ---------------------------------------------------------- One UI 版本

    /**
     * One UI 版本号（如 "6.1"）；无法判定时返回 "unknown"。
     * ro.build.version.oneui 编码为 MMmmpp（6.1 → 60100）。
     */
    fun oneUiVersion(): String {
        val raw = systemProperty("ro.build.version.oneui")
            .ifBlank { systemProperty("ro.config.build.version.oneui") }
        val code = raw.trim().toIntOrNull() ?: return "unknown"
        if (code <= 0) return "unknown"
        val major = code / 10_000
        val minor = (code % 10_000) / 100
        return "$major.$minor"
    }

    private fun systemProperty(key: String): String = try {
        val clazz = Class.forName("android.os.SystemProperties")
        val getter = clazz.getMethod("get", String::class.java)
        (getter.invoke(null, key) as? String).orEmpty()
    } catch (t: Throwable) {
        Logger.d(LOG_TAG, "systemProperty $key failed: ${t.message}")
        ""
    }

    // ---------------------------------------------------------- 导航方式

    /** Settings.Secure "navigation_mode" 值（API 29+；低版本返回 unknown）。 */
    fun navigationMode(context: Context): String {
        return try {
            when (
                Settings.Secure.getInt(
                    context.contentResolver, KEY_NAVIGATION_MODE, -1
                )
            ) {
                NAV_MODE_GESTURE_VALUE -> NAV_MODE_GESTURE
                NAV_MODE_TWO_BUTTON_VALUE -> NAV_MODE_TWO_BUTTON
                NAV_MODE_THREE_BUTTON_VALUE -> NAV_MODE_THREE_BUTTON
                else -> NAV_MODE_UNKNOWN
            }
        } catch (t: Throwable) {
            NAV_MODE_UNKNOWN
        }
    }

    // ---------------------------------------------------------- 包可见性

    /** 探测一个包是否可见/已安装（受 <queries> 影响，未声明的包可能误报不可见）。 */
    fun packageVisible(context: Context, packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (t: Throwable) {
        false
    }

    /** 三星生态已知应用（id → 包名）；presence 探测 + 深链入口。 */
    val knownPackages: Map<String, String> = mapOf(
        "device_care" to "com.samsung.android.lool",
        "device_security" to "com.samsung.android.sm.core",
        "edge_panels" to "com.samsung.android.app.cocktailbarservice",
        "modes_routines" to "com.samsung.android.app.routines",
        "bixby" to "com.samsung.android.bixby.agent",
        "smart_things" to "com.samsung.android.oneconnect",
        "my_files" to "com.sec.android.app.myfiles",
        "game_booster" to "com.samsung.android.game.gos",
        "samsung_account" to "com.osp.app.signin",
        "galaxy_store" to "com.sec.android.app.samsungapps",
        "samsung_keyboard" to "com.samsung.android.honeyboard",
        "camera" to "com.sec.android.app.camera",
    )

    /** 可见的三星生态应用 id 列表。 */
    fun availableSamsungApps(context: Context): List<String> =
        knownPackages.filterValues { packageVisible(context, it) }.keys.toList()

    /** One UI 设备维护（Device Care）是否可用 —— 深度睡眠应用管理的入口。 */
    fun deviceCareAvailable(context: Context): Boolean =
        packageVisible(context, knownPackages["device_care"]!!)

    // ---------------------------------------------------------- 电源/电池

    /** One UI 省电模式当前是否开启。 */
    fun isPowerSaving(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode
            ?: false
    } catch (t: Throwable) {
        false
    }

    /** 本应用是否已被豁免电池优化（One UI 杀后台防线）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------- 深色模式

    /** One UI 深色模式是否开启。 */
    fun isDarkMode(context: Context): Boolean = try {
        (context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
    } catch (t: Throwable) {
        false
    }

    // ---------------------------------------------------------- constants

    private const val KEY_NAVIGATION_MODE = "navigation_mode"
    private const val NAV_MODE_GESTURE_VALUE = 2
    private const val NAV_MODE_TWO_BUTTON_VALUE = 1
    private const val NAV_MODE_THREE_BUTTON_VALUE = 0

    const val NAV_MODE_GESTURE = "gesture"
    const val NAV_MODE_TWO_BUTTON = "two_button"
    const val NAV_MODE_THREE_BUTTON = "three_button"
    const val NAV_MODE_UNKNOWN = "unknown"
}
