package com.niki914.zafiro.chat

import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome

/**
 * 上下文压缩器（Feature: Context Budget）：把超出预算的历史压缩为
 * 「原始请求锚点 + 摘要 digest + 近期原样尾部」，让每次模型请求都接近
 * 「首条消息」的体量——小上下文免费模型也能跑长会话，token 花销最小化，
 * 同时保留对早前内容的感知（digest）。
 *
 * 设计约束（对齐 RealAgentLoop beforeSerialization seam 的宿主契约）：
 * - 纯函数、确定性、幂等友好：每段请求都从全量历史重算，不缓存不落盘；
 * - 尾部完整性：Assistant(带 ToolCall) 与其后的 ToolResult 原子成组，
 *   永不拆开（OpenAI / Anthropic 都要求 call/result 相邻）；
 * - 输出首条后不暴露无主的 ToolResult（悬挂结果降级为 digest 注记）；
 * - 不产生连续两条 User：tail 以 User 开头时 digest 文本并入该条，
 *   否则 digest 作为独立 User 消息（其后是 Assistant/Tool 组，轮替合法）；
 * - 估算零拷贝：只扫字符数，不拼接大字符串（3M token 会话也轻量）。
 *
 * 触发点：LLMController.contextCompactorHook（beforeSerialization，
 * 注册在 fixIncompleteToolCallsHook 之后——先补孤儿结果，再压缩）。
 * 会话树不受影响：压缩只改写发出去的请求（write 即弃），fork/rewind/
 * 持久化增量落盘全部无感。
 */
object ContextCompactor {

    /** 默认预算（history token 上限）：免费/小上下文模型友好。 */
    const val DEFAULT_BUDGET_TOKENS = 8000

    /** 粗略估算：1 token ≈ 4 字符（混合中英文的经验值，宁高勿低）。 */
    private const val CHARS_PER_TOKEN = 4

    /** 历史图片按固定成本计（base64 内联在协议层发生，此处按视觉 token 粗估）。 */
    private const val IMAGE_TOKEN_COST = 800

    /** 原始请求锚点最多保留的字符数。 */
    private const val ANCHOR_MAX_CHARS = 1200

    /** digest 内每条片段的最大字符数。 */
    private const val SNIPPET_MAX_CHARS = 160
    private const val TOOL_SNIPPET_MAX_CHARS = 120

    /** digest 头（英文，面向模型；与 buildMcpFailureNotice 同风格）。 */
    private const val DIGEST_HEADER =
        "[CONTEXT DIGEST — the earlier part of this conversation was condensed " +
            "to stay within the context budget. The original request and a condensed " +
            "digest of the omitted messages follow; treat them as background knowledge " +
            "and continue from the recent messages below.]"

    /**
     * 估算一段历史的 token 数。零拷贝：只累加字符长度，不构造新字符串。
     */
    fun estimateTokens(history: List<Message>): Int {
        var chars = 0
        var images = 0
        for (message in history) {
            when (message) {
                is Message.User -> message.content.forEach { block ->
                    when (block) {
                        is ContentBlock.Text -> chars += block.text.length
                        is ContentBlock.Image -> images++
                        is ContentBlock.Thinking -> chars += block.text.length
                        is ContentBlock.ToolCall -> chars += block.argumentsJson.length + block.id.length + block.name.length
                    }
                }

                is Message.Assistant -> message.message.content.forEach { block ->
                    when (block) {
                        is ContentBlock.Text -> chars += block.text.length
                        is ContentBlock.Image -> images++
                        is ContentBlock.Thinking -> chars += block.text.length
                        is ContentBlock.ToolCall -> chars += block.argumentsJson.length + block.id.length + block.name.length
                    }
                }

                is Message.ToolResult -> {
                    chars += message.callId.length + message.toolName.length
                    chars += when (val outcome = message.outcome) {
                        is ToolCallOutcome.Success -> outcome.content.length + outcome.images.size * 1
                        is ToolCallOutcome.Failure -> outcome.message.length + (outcome.content?.length ?: 0)
                        is ToolCallOutcome.Intercepted -> outcome.reason.length + (outcome.content?.length ?: 0)
                        is ToolCallOutcome.Interrupted -> outcome.content?.length ?: 0
                        is ToolCallOutcome.Unknown -> outcome.message.length + (outcome.content?.length ?: 0)
                    }
                    if (message.outcome is ToolCallOutcome.Success) {
                        images += (message.outcome as ToolCallOutcome.Success).images.size
                    }
                }
            }
        }
        return chars / CHARS_PER_TOKEN + images * IMAGE_TOKEN_COST
    }

    /**
     * 压缩入口。预算 <=0 视为不限制；估算在预算内原样返回（同一实例）。
     * 任何异常由调用方（hook）兜底回落原历史。
     */
    fun compact(history: List<Message>, budgetTokens: Int): List<Message> {
        if (budgetTokens <= 0) return history
        if (estimateTokens(history) <= budgetTokens) return history

        val blocks = toAtomicBlocks(history)
        if (blocks.isEmpty()) return history

        // digest 预留 = min(1200, 预算/4)；其余给原样尾部
        val digestReserve = minOf(1200, budgetTokens / 4)
        val tailBudget = (budgetTokens - digestReserve).coerceAtLeast(1)

        // 从尾向前收块：至少保留最后一块（否则请求形状非法）
        val tailBlocks = mutableListOf<Pair<Int, List<Message>>>()
        var tailTokens = 0
        for (block in blocks.reversed()) {
            val blockTokens = estimateTokens(block.second)
            if (tailBlocks.isNotEmpty() && tailTokens + blockTokens > tailBudget) break
            tailBlocks.add(0, block)
            tailTokens += blockTokens
        }
        val tail = tailBlocks.flatMap { it.second }

        // 悬挂 ToolResult（无前置 Assistant）不能出现在 digest 之后（Provider 拒收）：
        // 降级为 digest 注记并从尾部剔除
        val digestNotes = mutableListOf<String>()
        var tailStart = 0
        while (tailStart < tail.size && tail[tailStart] is Message.ToolResult) {
            val dropped = tail[tailStart] as Message.ToolResult
            digestNotes += "tool ${dropped.toolName}: (unpaired tool result omitted)"
            tailStart++
        }
        val keptTail = tail.drop(tailStart)
        if (keptTail.isEmpty()) return history

        // 中段 = 尾部首块之前的历史（块携带 start index，避免 indexOf 被重复消息误导）
        val headIndex = tailBlocks.first().first
        val middle = if (headIndex > 0) history.subList(0, headIndex) else emptyList()
        val anchorText = firstUserText(history, ANCHOR_MAX_CHARS)

        // 中段为空且锚点已在尾部 → 无需 digest（不应发生：估算已超预算，
        // 但防御性兜底）
        if (middle.isEmpty() && digestNotes.isEmpty()) return keptTail

        val digestText = buildDigestText(
            header = DIGEST_HEADER,
            anchorText = anchorText,
            omittedCount = middle.size,
            snippets = digestSnippets(middle, digestReserve * CHARS_PER_TOKEN),
            notes = digestNotes,
        )

        // 轮替安全：tail 以 User 开头 → digest 并入该条；否则 digest 独立成条
        // （其后是 Assistant / Assistant+Tool 组，user→assistant 轮替合法）
        val digestBlock = listOf(Message.User(listOf(ContentBlock.Text(digestText))))
        val merged = when (val first = keptTail.first()) {
            is Message.User ->
                listOf(Message.User(listOf(ContentBlock.Text(digestText)) + first.content)) +
                    keptTail.drop(1)

            else -> digestBlock + keptTail
        }
        return merged
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /**
     * 历史消息 → 原子块（带 history 起始下标）：Assistant(带 ToolCall) 与
     * 紧随其后的 ToolResult 合并成组；其余消息各成一块
     * （User / 无调用 Assistant / 悬挂 ToolResult）。
     */
    private fun toAtomicBlocks(history: List<Message>): List<Pair<Int, List<Message>>> {
        val blocks = mutableListOf<Pair<Int, List<Message>>>()
        var index = 0
        while (index < history.size) {
            val message = history[index]
            if (message is Message.Assistant &&
                message.message.content.any { it is ContentBlock.ToolCall }
            ) {
                val group = mutableListOf<Message>(message)
                index++
                while (index < history.size && history[index] is Message.ToolResult) {
                    group += history[index]
                    index++
                }
                blocks += index - group.size to group
            } else {
                blocks += index to listOf(message)
                index++
            }
        }
        return blocks
    }

    /** 首条 User 消息的文本（扁平化、截断）——任务的原始目标。 */
    private fun firstUserText(history: List<Message>, maxChars: Int): String {
        val first = history.firstOrNull { it is Message.User } as? Message.User
            ?: return "(not recorded)"
        return flatten(first.content).take(maxChars)
    }

    /**
     * 中段消息 → 有界 digest 片段（从最新往回收集，输出按时间序）。
     * 预算按字符计，超限即停——digest 体量与历史总长无关（O(预算)）。
     */
    private fun digestSnippets(middle: List<Message>, maxChars: Int): List<String> {
        val collected = mutableListOf<String>()
        var used = 0
        for (message in middle.reversed()) {
            if (used >= maxChars) break
            val line = when (message) {
                is Message.User -> "user: ${flatten(message.content).take(SNIPPET_MAX_CHARS)}"
                is Message.Assistant -> {
                    val calls = message.message.content
                        .filterIsInstance<ContentBlock.ToolCall>()
                    val text = flatten(message.message.content).take(SNIPPET_MAX_CHARS)
                    if (calls.isEmpty()) {
                        "assistant: $text"
                    } else {
                        val names = calls.joinToString(", ") { it.name }
                        "assistant: called tool(s): $names" +
                            (if (text.isBlank()) "" else " | $text")
                    }
                }

                is Message.ToolResult -> {
                    val ok = message.outcome is ToolCallOutcome.Success
                    val preview = outcomeText(message.outcome).take(TOOL_SNIPPET_MAX_CHARS)
                    "tool ${message.toolName}: ${if (ok) "ok" else "error"}: $preview"
                }
            }
            if (line.isBlank()) continue
            collected += line
            used += line.length
        }
        return collected.asReversed()
    }

    private fun buildDigestText(
        header: String,
        anchorText: String,
        omittedCount: Int,
        snippets: List<String>,
        notes: List<String>,
    ): String = buildString {
        appendLine(header)
        appendLine()
        appendLine("Original request:")
        appendLine(anchorText)
        appendLine()
        appendLine("$omittedCount earlier message(s) omitted. Condensed digest (oldest to newest):")
        snippets.forEach { appendLine(it) }
        notes.forEach { appendLine(it) }
    }.trim()

    /** 内容块 → 单行文本（Text 为主；换行折叠；图片以占位注记表达）。 */
    private fun flatten(blocks: List<ContentBlock>): String {
        val parts = mutableListOf<String>()
        for (block in blocks) {
            when (block) {
                is ContentBlock.Text -> if (block.text.isNotBlank()) parts += block.text
                is ContentBlock.Image -> parts += "[image]"
                is ContentBlock.Thinking -> Unit // 思考不进 digest
                is ContentBlock.ToolCall -> Unit // 调用由 Assistant 行表达
            }
        }
        return parts.joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun outcomeText(outcome: ToolCallOutcome): String = when (outcome) {
        is ToolCallOutcome.Success -> outcome.content
        is ToolCallOutcome.Failure -> outcome.content ?: outcome.message
        is ToolCallOutcome.Intercepted -> outcome.content ?: outcome.reason
        is ToolCallOutcome.Interrupted -> outcome.content ?: "(interrupted)"
        is ToolCallOutcome.Unknown -> outcome.content ?: outcome.message
    }.replace(Regex("\\s+"), " ").trim()
}
