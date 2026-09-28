package com.niki914.zafiro.app.cloud

import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRegistry
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.logging.Logger

/**
 * 云端动作执行器：把大脑下发的动作映射到手机本地内置工具。
 *
 * 白名单 = 只允许「手机作为双手」的工具；刻意排除：
 *  - execute_python / py_meta_tools：重资源（Python 进程），云端大脑自己有 web 工具；
 *  - screenshot / view_image：图片回传协议 v1 不含图像；
 *  - token_vault：机密绝不送服务器；
 *  - load_skill：技能属手机端会话概念。
 * 安全门（ToolPermissionCoordinator / autonomousExecution）在工具内部仍然生效。
 */
object CloudBrainActionExecutor {

    private const val LOG_TAG = "niki914_nexus_CloudBrainExecutor"

    /** 云端可下发的工具白名单。 */
    val ALLOWED_TOOLS: Set<String> = setOf(
        "terminal",
        "launch_app",
        "open_uri",
        "notify",
        "find_installed_apps",
        "screen_operation_accessibility",
        "screen_operation_shell",
        "memory",
        "todo_write",
    )

    private val registry = BuiltinToolRegistry.default()

    /** 工具是否允许云端下发（在白名单且真实存在）。 */
    fun isAllowed(toolName: String): Boolean {
        if (toolName !in ALLOWED_TOOLS) return false
        return registry.find(toolName) != null
    }

    /** 白名单工具的元数据（随任务提交给服务端，用于 function calling 定义）。 */
    fun toolSpecs(allowedTools: List<String>): List<CloudToolSpec> {
        return allowedTools.mapNotNull { name ->
            val tool = registry.find(name) ?: return@mapNotNull null
            CloudToolSpec(
                name = tool.name,
                description = tool.description,
                schemaJson = tool.inputSchemaJson.orEmpty(),
            )
        }
    }

    /** 执行单个云端动作，永不抛出（失败转为 ok=false 的结果）。 */
    suspend fun execute(action: CloudAction): CloudActionResult {
        if (!isAllowed(action.tool)) {
            return CloudActionResult(
                id = action.id,
                ok = false,
                message = "tool not allowed on device: ${action.tool}",
            )
        }
        val tool = registry.find(action.tool)
            ?: return CloudActionResult(action.id, false, "tool missing: ${action.tool}")
        return try {
            val result = tool.invoke(BuiltinToolRequest(name = action.tool, argumentsJson = action.argumentsJson))
            Logger.i(
                LOG_TAG,
                "action id=${action.id} tool=${action.tool} ok=${result.ok} code=${result.code}",
            )
            CloudActionResult(
                id = action.id,
                ok = result.ok,
                message = result.message.take(MAX_RESULT_MESSAGE),
            )
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "action id=${action.id} tool=${action.tool} threw ${t.message}")
            CloudActionResult(
                id = action.id,
                ok = false,
                message = (t.message ?: t.javaClass.simpleName).take(MAX_RESULT_MESSAGE),
            )
        }
    }

    private const val MAX_RESULT_MESSAGE = 4000
}
