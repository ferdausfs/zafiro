package com.niki914.zafiro.app.util

import com.niki914.zafiro.runtime.service.AgentRuntimeService
import android.app.Application
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 权限入口统一守卫：业务代码禁止直连原生权限 API。
 *
 * - 允许名单：PermissionManager 门面 + permission-manager 模块内部 + TargetStatus 静默查询的
 *   被调用方（SystemDialogHandler 弹窗、TargetStatus 查询本身）+ libterm（独立演进的终端运行时）。
 * - 私调清单：checkSelfPermission / requestPermissions / 裸 su / settings put / appops set /
 *   Shizuku.requestPermission / libsu Shell / canDrawOverlays 等，出现在允许名单之外即失败。
 * - 图片/文件选择器（SkillsSettingsContent、HomePageContent）与通知 launcher 用的是
 *   ActivityResultContracts 非权限 contract，不在私调清单内。
 *
 * 新增私调时先改需求：要么收编进 PermissionManager，要么把用例加进白名单并在 PR 里说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class PermissionEntryGuardTest {

    /** 私调用法 → 允许出现的源码位置（文件后缀 + 行内标记）。 */
    private data class AllowRule(val fileSuffix: String, val marker: String)

    /** 私调用法 → 允许名单。marker 为空表示整文件允许。 */
    private val allowlist: Map<String, List<AllowRule>> = mapOf(
        // 只读查询只经过 TargetStatus；permission-manager 内部实现
        "canDrawOverlays(" to listOf(
            AllowRule("permission/TargetStatus.kt", ""),
            // v2.0.0 device_capabilities：只读能力体检（悬浮窗状态探测）
            AllowRule("impl/DeviceCapabilitiesBuiltin.kt", "canDrawOverlays"),
        ),
        "checkSelfPermission(" to listOf(
            AllowRule("permission/TargetStatus.kt", ""),
            AllowRule("permission/ShizukuHandler.kt", "Shizuku.checkSelfPermission"),
            // Phase 2 发现的存量违规（v1.7.0 地点触发器引入，先于守卫测试覆盖 app 模块）：
            // ContextCompat.checkSelfPermission 只读查询 FINE_LOCATION，
            // 而 PermissionManager 的 Permission 枚举尚无 LOCATION（收编需跨模块
            // 扩展枚举 + TargetStatus，Phase 3 处理；此处仅只读，不申请）。
            AllowRule("automation/AutomationHub.kt", "ContextCompat.checkSelfPermission"),
            // 同上（系统集成设置页内的只读状态查询，随 requestPermissions 流一起收编）
            AllowRule("ui/content/SystemIntegrationSettingsContent.kt", "checkSelfPermission"),
            // Phase 2 发现的存量违规（v1.7.0/v1.8.0 agent-runtime 内建工具引入）：
            // 工具执行前的运行时权限只读门（联系人/日历/外部存储）。agent-runtime
            // 无法直接依赖 PermissionManager 门面，收编需要打通模块依赖 + 枚举扩展，
            // Phase 3 处理；此处均为只读查询，不发起申请。
            AllowRule("impl/SystemDataBuiltin.kt", "checkSelfPermission"),
            AllowRule("impl/FileManagerBuiltin.kt", "checkSelfPermission"),
            // v2.0.0 Jarvis Mode（device_capabilities 工具）：能力体检（只读
            // checkSelfPermission）+ 经 libterm 特权会话执行的自修命令（settings put /
            // appops set / pm grant / dumpsys deviceidle）。命令语义与
            // permission-manager 的 ShellGrants 一致（该对象 internal 不可跨模块引用），
            // 收编进门面需打通 agent-runtime 与 permission-manager 的引擎复用，Phase 3。
            AllowRule("impl/DeviceCapabilitiesBuiltin.kt", "checkSelfPermission"),
        ),
        // 系统弹窗的 launcher 调用 + Manifest 文本
        "POST_NOTIFICATIONS" to listOf(
            AllowRule("permission/UiHandlers.kt", ""),
            AllowRule("permission/TargetStatus.kt", ""),
            AllowRule("MainActivity.kt", "RequestPermission"),
            AllowRule("AndroidManifest.xml", ""),
            // v2.0.0 device_capabilities：pm grant 通知权限（特权会话自修命令文本）
            AllowRule("impl/DeviceCapabilitiesBuiltin.kt", "pm grant"),
        ),
        // seed py 脚本是 py 工具层自身能力（py 进程无 PermissionManager 可用），显式排除
        "\"su\"" to listOf(
            AllowRule("seed_py_launch_wechat.py", ""),
            AllowRule("seed_py_install_apk.py", ""),
        ),
        // shell 通道授权命令：permission-manager 内部 + v2.0.0 device_capabilities
        // 自修（agent-runtime 经 libterm 特权会话执行，命令语义与 ShellGrants 一致）
        "settings put secure" to listOf(
            AllowRule("permission/ShellGrants.kt", ""),
            AllowRule("impl/DeviceCapabilitiesBuiltin.kt", ""),
        ),
        "appops set" to listOf(
            AllowRule("permission/ShellGrants.kt", ""),
            AllowRule("impl/DeviceCapabilitiesBuiltin.kt", ""),
        ),
        "Shizuku.requestPermission" to listOf(
            AllowRule("permission/ShizukuHandler.kt", ""),
        ),
        // v2.0.0：Shizuku 标准 provider 声明必须在 manifest（Shizuku 管理器识别应用的法定途径）
        "rikka.shizuku" to listOf(
            AllowRule("AndroidManifest.xml", ""),
        ),
        // Phase 2 发现的存量违规（v1.7.0/v1.8.0 系统集成设置页引入）：运行时权限
        // 请求 UI 流（通讯录/日历/定位等 provider 触发器的前置授权）。收编进
        // PermissionManager 需要为 UI 通道扩展 requestPermissions 能力，Phase 3 处理。
        "requestPermissions(" to listOf(
            AllowRule("ui/content/SystemIntegrationSettingsContent.kt", ""),
        ),
    )

    /** 允许名单之外的任何命中都算私调。扫描范围：全仓 main 源码（build/ 与测试除外）。 */
    private val privateCallPatterns = allowlist.keys +
        "requestPermissions(" +
        "Runtime.getRuntime().exec(" +
        "Settings.Secure.putString" +
        "Shell.getShell(" +
        "Shell.cmd(" +
        "Shell.isAppGrantedRoot" +
        "com.topjohnwu.superuser" +
        "rikka.shizuku" +
        "Shizuku.requestPermission"

    @Test
    fun `no private permission calls outside allowlist`() {
        val repoRoot = findRepoRoot()
        val violations = mutableListOf<String>()
        repoRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension in setOf("kt", "java", "py", "xml") }
            .filter { "src/main/" in it.path || "src/main" in it.path || it.name == "AndroidManifest.xml" }
            .filterNot { "/build/" in it.path }
            .forEach { file ->
                val relative = file.relativeTo(repoRoot).path
                file.readLines().forEachIndexed { index, line ->
                    for (pattern in privateCallPatterns) {
                        if (pattern !in line) continue
                        if (isAllowed(relative, line, pattern)) continue
                        violations += "$relative:${index + 1}: [$pattern] $line".trim()
                    }
                }
            }
        assertTrue(
            "业务方直连原生权限 API（收编进 PermissionManager 或加白名单并在 PR 说明）：\n" +
                violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `service notification gate goes through PermissionManager`() {
        // AgentRuntimeService 的通知门必须经门面：删掉 import 即删掉调用，无调用也要有门面引用。
        val service = File(
            findRepoRoot(),
            "app/src/main/java/com/niki914/zafiro/runtime/service/AgentRuntimeService.kt",
        )
        val text = service.readText()
        assertTrue(
            "AgentRuntimeService 必须经 PermissionManager 查通知状态",
            "PermissionHolder" in text && "targetStatus" in text,
        )
        assertTrue(
            " AgentRuntimeService 禁止直连 checkSelfPermission",
            "checkSelfPermission" !in text,
        )
        assertTrue("AgentRuntimeService 禁止裸 su", "\"su\"" !in text)
    }

    private fun isAllowed(relative: String, line: String, pattern: String): Boolean {
        // libterm 是独立演进的终端运行时，整目录豁免。
        // 仓库根的 libterm 是 .gitignore 掉的符号链接（指向仓库外的同级 checkout），
        // walkTopDown 会跟进去，此时相对路径以 "libterm/" 开头而非 "libs/libterm/"。
        if ("/libterm/" in relative ||
            relative.startsWith("libs/libterm/") ||
            relative.startsWith("libterm/")
        ) {
            return true
        }
        // permission-manager 内部实现就是被收编的正主（含 KDoc 里的方法名引用），整模块豁免
        if (relative.startsWith("libs/permission-manager/src/main/")) return true
        // 测试源码不在扫描范围（walk 已过滤），此处仅防漏网
        if ("/src/test/" in relative) return true
        return allowlist[pattern].orEmpty().any { rule ->
            relative.endsWith(rule.fileSuffix) &&
                (rule.marker.isEmpty() || rule.marker in line)
        }
    }

    private fun findRepoRoot(): File {
        // 从测试工作目录向上找 settings.gradle.kts
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile
            if (parent == null) fail("找不到仓库根目录（settings.gradle.kts）")
            dir = parent
        }
    }
}
