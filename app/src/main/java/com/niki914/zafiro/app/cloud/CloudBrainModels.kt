package com.niki914.zafiro.app.cloud

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cloud Brain 线协议（app ↔ Cloudflare Worker），字段名 snake_case。
 *
 * 交互流程：
 *  1. POST /v1/tasks           提交任务 → 返回会话快照
 *  2. GET  /v1/sessions/{id}   长轮询快照（?cursor=&wait= 秒）
 *  3. 快照里 pendingActions = 需要手机本地执行的动作（terminal / launch_app / ...）
 *  4. POST /v1/sessions/{id}/results 回传动作结果，大脑继续推理
 *  5. status=completed 时 reply 即最终答复
 */

@Serializable
data class CloudTaskSubmit(
    @SerialName("device_id") val deviceId: String,
    val task: String,
    /** 事件上下文（通知标题/正文、文件路径等），大脑据此决策。 */
    val context: String = "",
    /** 手机端允许执行的内置工具白名单。 */
    @SerialName("allowed_tools") val allowedTools: List<String> = emptyList(),
    /** 白名单工具的元数据（名称/描述/JSON Schema），服务端据此生成 function calling 定义。 */
    val tools: List<CloudToolSpec> = emptyList(),
    /** 大脑最多推理步数，防失控。 */
    @SerialName("max_steps") val maxSteps: Int = 20,
)

@Serializable
data class CloudToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema 字符串（BuiltinTool.inputSchemaJson 同构）；空 = 无参数约束。 */
    @SerialName("schema_json") val schemaJson: String = "",
)

@Serializable
data class CloudAction(
    val id: String,
    /** 手机端内置工具名（terminal、launch_app、notify…）或服务端工具（web_search…）。 */
    val tool: String,
    /** 工具入参 JSON（BuiltinToolRequest.argumentsJson 同构）。 */
    @SerialName("arguments_json") val argumentsJson: String,
)

@Serializable
data class CloudActionResult(
    val id: String,
    val ok: Boolean,
    val message: String,
)

@Serializable
data class CloudResultsPost(
    val results: List<CloudActionResult>,
)

/** 发件箱 POST_RESULTS 载荷：结果必须绑定到具体会话。 */
@Serializable
data class CloudResultsEnvelope(
    @SerialName("session_id") val sessionId: String,
    val results: List<CloudActionResult>,
)

/** 大脑事件流（日志/UI 展示用）。 */
@Serializable
data class CloudEvent(
    /** step | reply | error */
    val kind: String,
    val text: String,
)

@Serializable
data class CloudSessionSnapshot(
    @SerialName("session_id") val sessionId: String,
    /** running | waiting_phone | completed | failed */
    val status: String,
    /** 增量游标：下次 poll 带上，返回 cursor 之后的新事件/新动作。 */
    val cursor: Long = 0,
    @SerialName("pending_actions") val pendingActions: List<CloudAction> = emptyList(),
    val reply: String? = null,
    val error: String? = null,
    @SerialName("new_events") val newEvents: List<CloudEvent> = emptyList(),
) {
    fun isTerminalStatus(): Boolean = status == STATUS_COMPLETED || status == STATUS_FAILED

    companion object {
        const val STATUS_RUNNING = "running"
        const val STATUS_WAITING_PHONE = "waiting_phone"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}

/** Worker 连通性探测结果（设置页「测试连接」）。 */
data class CloudBrainPing(
    val ok: Boolean,
    val message: String,
)
