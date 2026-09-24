package com.niki914.zafiro.app.ui.content

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.niki914.uikit.infra.component.settings.SettingsPageSpec
import com.niki914.uikit.infra.component.settings.SettingsRowAction
import com.niki914.uikit.infra.component.settings.SettingsRowSpec
import com.niki914.uikit.infra.component.settings.SettingsSectionLayout
import com.niki914.uikit.infra.component.settings.SettingsSectionSpec
import com.niki914.uikit.infra.component.settings.SettingsSpecPageContent
import com.niki914.zafiro.app.R
import com.niki914.zafiro.app.automation.AutomationHub
import com.niki914.zafiro.app.ui.nav.ZafiroSettingsGroup

/**
 * System Integration 设置页（v1.7.0 System-Integrated Autonomous Agent）。
 *
 * 四大能力的总控与状态面板：
 *  - Direct System API：contacts / calendar 运行时权限快捷授权；
 *  - Real-time Vision：无障碍服务状态（实时取帧的前置条件）；
 *  - File Orchestration：共享存储「所有文件访问」授权入口；
 *  - Event-Driven Autonomy：事件驱动触发器汇总 + 跳转自动化页。
 *
 * 工具本身的启停走 BuiltinTools 设置（per-tool 持久化），本页只管授权与状态。
 */
@Composable
fun SystemIntegrationSettingsContent(
    onOpenAutomation: () -> Unit,
) {
    val context = LocalContext.current

    var contactsGranted by remember { mutableStateOf(false) }
    var calendarGranted by remember { mutableStateOf(false) }
    var allFilesGranted by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(false) }
    var armedTriggers by remember { mutableStateOf(0) }

    fun refreshStatus() {
        contactsGranted = context.isPermissionGranted(Manifest.permission.READ_CONTACTS)
        calendarGranted = context.isPermissionGranted(Manifest.permission.READ_CALENDAR)
        locationGranted = context.isPermissionGranted(Manifest.permission.ACCESS_FINE_LOCATION)
        allFilesGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.isPermissionGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        accessibilityEnabled = context.isAccessibilityServiceEnabled()
        armedTriggers = AutomationHub.armedTriggerCount.value
    }

    LaunchedEffect(Unit) { refreshStatus() }

    // 从系统设置页返回时刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshStatus()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshStatus() }

    val sections = mutableListOf<SettingsSectionSpec>()

    // ---- Cap 1: Direct System API Integration
    sections += SettingsSectionSpec(
        layout = SettingsSectionLayout.GroupedCard,
        rows = listOf(
            SettingsRowSpec.Action(
                id = "sys.contacts",
                title = stringResource(R.string.sys_perm_contacts),
                summary = stringResource(
                    if (contactsGranted) R.string.sys_perm_ok
                    else R.string.sys_perm_missing
                ),
            ),
            SettingsRowSpec.Action(
                id = "sys.calendar",
                title = stringResource(R.string.sys_perm_calendar),
                summary = stringResource(
                    if (calendarGranted) R.string.sys_perm_ok
                    else R.string.sys_perm_missing
                ),
            ),
            SettingsRowSpec.Message(
                title = stringResource(R.string.sys_api_desc),
                verticalPadding = 10.dp,
            ),
        ),
    )

    // ---- Cap 2: Dynamic Real-time Vision
    sections += SettingsSectionSpec(
        layout = SettingsSectionLayout.GroupedCard,
        rows = listOf(
            SettingsRowSpec.Action(
                id = "sys.vision_accessibility",
                title = stringResource(R.string.sys_vision_accessibility),
                summary = stringResource(
                    if (accessibilityEnabled) R.string.sys_vision_accessibility_ok
                    else R.string.sys_vision_accessibility_missing
                ),
            ),
            SettingsRowSpec.Message(
                title = stringResource(R.string.sys_vision_desc),
                verticalPadding = 10.dp,
            ),
        ),
    )

    // ---- Cap 4: Global File System Orchestration
    sections += SettingsSectionSpec(
        layout = SettingsSectionLayout.GroupedCard,
        rows = listOf(
            SettingsRowSpec.Action(
                id = "sys.all_files",
                title = stringResource(R.string.sys_files_all_access),
                summary = stringResource(
                    if (allFilesGranted) R.string.sys_perm_ok
                    else R.string.sys_files_all_access_missing
                ),
            ),
            SettingsRowSpec.Message(
                title = stringResource(R.string.sys_files_desc),
                verticalPadding = 10.dp,
            ),
        ),
    )

    // ---- Cap 3: Event-Driven Proactive Autonomy
    sections += SettingsSectionSpec(
        layout = SettingsSectionLayout.GroupedCard,
        rows = listOf(
            SettingsRowSpec.Action(
                id = "sys.location",
                title = stringResource(R.string.sys_perm_location),
                summary = stringResource(
                    if (locationGranted) R.string.sys_perm_ok
                    else R.string.sys_perm_missing
                ),
            ),
            SettingsRowSpec.Navigation(
                id = "sys.automation",
                title = stringResource(R.string.sys_events_open_automation),
                summary = stringResource(R.string.sys_events_armed_count, armedTriggers),
            ),
        ),
    )

    SettingsSpecPageContent(
        spec = SettingsPageSpec(
            description = stringResource(R.string.sys_page_description),
            sections = sections,
        ),
        onAction = { action ->
            when (action) {
                is SettingsRowAction.Click -> when (action.id) {
                    "sys.contacts" -> requestPermissions(
                        permissionLauncher,
                        contactsGranted,
                        Manifest.permission.READ_CONTACTS,
                    )

                    "sys.calendar" -> requestPermissions(
                        permissionLauncher,
                        calendarGranted,
                        Manifest.permission.READ_CALENDAR,
                    )

                    "sys.location" -> requestPermissions(
                        permissionLauncher,
                        locationGranted,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )

                    "sys.vision_accessibility" -> openAccessibilitySettings(context)
                    "sys.all_files" -> openAllFilesAccessSettings(context)
                    else -> Unit
                }

                is SettingsRowAction.Navigate -> when (action.id) {
                    "sys.automation" -> onOpenAutomation()
                    "sys.contacts" -> requestPermissions(
                        permissionLauncher,
                        contactsGranted,
                        Manifest.permission.READ_CONTACTS,
                    )

                    "sys.calendar" -> requestPermissions(
                        permissionLauncher,
                        calendarGranted,
                        Manifest.permission.READ_CALENDAR,
                    )

                    "sys.location" -> requestPermissions(
                        permissionLauncher,
                        locationGranted,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    )

                    "sys.vision_accessibility" -> openAccessibilitySettings(context)
                    "sys.all_files" -> openAllFilesAccessSettings(context)
                }

                else -> Unit
            }
        },
    )
}

// ------------------------------------------------------------------ helpers

private fun Context.isPermissionGranted(permission: String): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(this, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

private fun Context.isAccessibilityServiceEnabled(): Boolean {
    val component = ComponentName(this, "com.niki914.zafiro.mod.feat.ZafiroAccessibilityService")
    val enabled = Settings.Secure.getString(
        contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(":").any { entry ->
        entry == component.flattenToShortString() ||
                entry == component.flattenToString() ||
                (entry.substringBefore("/") == packageName &&
                        entry.substringAfter("/", "").let { cls ->
                            cls == "com.niki914.zafiro.mod.feat.ZafiroAccessibilityService" ||
                                    cls == ".mod.feat.ZafiroAccessibilityService"
                        })
    }
}

private fun requestPermissions(
    launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    alreadyGranted: Boolean,
    vararg permissions: String,
) {
    if (alreadyGranted) return
    runCatching { launcher.launch(permissions.map { it }.toTypedArray()) }
}

private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        // 极少数 ROM 无此页面
    }
}

private fun openAllFilesAccessSettings(context: Context) {
    try {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (t: Throwable) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
