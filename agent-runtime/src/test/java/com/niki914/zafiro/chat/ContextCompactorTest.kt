package com.niki914.zafiro.chat

import com.niki914.okia.message.AssistantMessage
import com.niki914.okia.message.ContentBlock
import com.niki914.okia.message.Message
import com.niki914.okia.message.ToolCallOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ContextCompactor 单测：预算裁剪、锚点/digest 保留、尾部完整性
 * （call/result 原子、悬挂结果不外露、user 轮替安全）。
 */
class ContextCompactorTest {

    private fun user(text: String) = Message.User(listOf(ContentBlock.Text(text)))

    private fun assistant(text: String) = Message.Assistant(
        AssistantMessage(listOf(ContentBlock.Text(text)))
    )

    private fun assistantWithCall(
        callId: String,
        toolName: String = "bash",
        args: String = "{\"cmd\":\"ls\"}",
    ) = Message.Assistant(
        AssistantMessage(listOf(ContentBlock.ToolCall(callId, toolName, args)))
    )

    private fun toolResult(callId: String, toolName: String = "bash", content: String) =
        Message.ToolResult(callId, toolName, ToolCallOutcome.Success(content))

    /** ~token 数的文本（4 字符 ≈ 1 token）。 */
    private fun textOfTokens(tokens: Int): String = "x".repeat(tokens * 4)

    // ── 估算 ─────────────────────────────────────────────────────────────

    @Test
    fun estimateCountsTextCharsAndImages() {
        val history = listOf(
            user(textOfTokens(100)), // 400 chars → 100
            user(textOfTokens(50)),  // 200 chars → 50
        )
        assertEquals(150, ContextCompactor.estimateTokens(history))

        val withImage = listOf(
            Message.User(
                listOf(
                    ContentBlock.Text("hi"),
                    ContentBlock.Image("/img/a.jpg", "image/jpeg"),
                )
            ),
        )
        // 2 chars / 4 = 0 token + 800（图片固定成本）
        assertEquals(800, ContextCompactor.estimateTokens(withImage))
    }

    // ── 快路径 ───────────────────────────────────────────────────────────

    @Test
    fun underBudgetReturnsSameInstance() {
        val history = listOf(user("hi"), assistant("hello"))
        assertTrue(ContextCompactor.compact(history, 8000).sameAs(history))
    }

    @Test
    fun nonPositiveBudgetMeansUnlimited() {
        val history = listOf(user(textOfTokens(50_000)))
        assertTrue(ContextCompactor.compact(history, 0).sameAs(history))
        assertTrue(ContextCompactor.compact(history, -1).sameAs(history))
    }

    // ── 压缩形状 ─────────────────────────────────────────────────────────

    @Test
    fun overBudgetKeepsAnchorDigestAndFinalMessage() {
        val finalUser = user("current question about CI")
        val history = listOf(
            user("fix the login flow in my app"), // 锚点（原始请求）
            assistant(textOfTokens(3000)),
            user(textOfTokens(3000)),
            assistant(textOfTokens(3000)),
            finalUser,
        )
        val compacted = ContextCompactor.compact(history, budgetTokens = 4000)

        // 首条 = digest（User），含锚点原文与头部标记
        val first = compacted.first() as Message.User
        val digestText = (first.content.first() as ContentBlock.Text).text
        assertTrue(digestText.contains("CONTEXT DIGEST"))
        assertTrue(digestText.contains("fix the login flow in my app"))
        assertTrue(digestText.contains("earlier message(s) omitted"))

        // 末条 User：尾部以 User 开头 → digest 并入该条，原文 content 保留在后
        val last = compacted.last() as Message.User
        assertEquals(finalUser.content, last.content.drop(1))

        // 压缩后体量必须显著下降
        assertTrue(ContextCompactor.estimateTokens(compacted) < ContextCompactor.estimateTokens(history))
    }

    @Test
    fun compactedOutputStaysWithinBudgetSlack() {
        val messages = buildList {
            add(user("original goal: build a web scraper"))
            repeat(60) { i ->
                add(user(textOfTokens(1000)))
                add(assistant(textOfTokens(1000)))
            }
            add(user("latest question"))
        }
        val compacted = ContextCompactor.compact(messages, budgetTokens = 8000)
        val estimate = ContextCompactor.estimateTokens(compacted)
        // 尾部至少保留最后一块，允许其超预算的余量（此处最后一条很小）
        assertTrue("estimate=$estimate should be <= 9500", estimate <= 9500)
    }

    // ── 尾部完整性 ───────────────────────────────────────────────────────

    @Test
    fun toolCallAndResultStayAdjacent() {
        val a3 = assistantWithCall("call-1")
        val t3 = toolResult("call-1", content = textOfTokens(2000))
        val history = listOf(
            user(textOfTokens(4000)),
            assistant(textOfTokens(4000)),
            a3,
            t3,
            user("next"),
        )
        val compacted = ContextCompactor.compact(history, budgetTokens = 2000)
        assertCallResultIntegrity(compacted)
    }

    @Test
    fun midToolLoopKeepsTrailingCallResultGroup() {
        val a3 = assistantWithCall("call-9", toolName = "terminal", args = "{\"cmd\":\"gradle build\"}")
        val t3 = toolResult("call-9", toolName = "terminal", content = textOfTokens(300))
        val history = listOf(
            user(textOfTokens(8000)),
            assistant(textOfTokens(8000)),
            user("continue the build"),
            a3,
            t3,
        )
        val compacted = ContextCompactor.compact(history, budgetTokens = 1500)

        // 尾部组原样保留：digest 之后紧跟 Assistant(calls) + ToolResult
        assertTrue(compacted.size >= 3)
        assertEquals(a3, compacted[compacted.size - 2])
        assertEquals(t3, compacted.last())
        assertCallResultIntegrity(compacted)
    }

    @Test
    fun danglingToolResultNeverExposedAfterDigest() {
        val dangling = toolResult("orphan-1", content = "result without its call")
        val history = listOf(
            user(textOfTokens(8000)),
            dangling, // 悬挂结果（无前置 Assistant）
            user("latest"),
        )
        val compacted = ContextCompactor.compact(history, budgetTokens = 1500)
        // 输出中第一条若为 ToolResult 必须紧随其 Assistant；此处悬挂块应被剔除并进 digest
        assertCallResultIntegrity(compacted)
        assertFalse(compacted.any { it === dangling })
        val digestText = (compacted.first() as Message.User)
            .content.firstNotNullOf { it as ContentBlock.Text }.text
        assertTrue(digestText.contains("unpaired tool result omitted"))
    }

    // ── 轮替安全 ─────────────────────────────────────────────────────────

    @Test
    fun digestMergesIntoLeadingUserInsteadOfConsecutiveUsers() {
        val finalUser = user("final tiny question")
        val history = listOf(
            user(textOfTokens(4000)),
            assistant(textOfTokens(4000)),
            user(textOfTokens(4000)),
            assistant(textOfTokens(4000)),
            finalUser,
        )
        val compacted = ContextCompactor.compact(history, budgetTokens = 3000)

        // 尾部以 User 开头 → digest 并入该条，不产生连续两条 User
        assertEquals(1, compacted.size)
        val merged = compacted.single() as Message.User
        assertTrue((merged.content.first() as ContentBlock.Text).text.contains("CONTEXT DIGEST"))
        assertEquals(finalUser.content, merged.content.drop(1))
        assertAlternatingRoles(compacted)
    }

    @Test
    fun digestAsUserMessageKeepsAlternation() {
        val a3 = assistantWithCall("call-2")
        val t3 = toolResult("call-2", content = textOfTokens(500))
        val history = listOf(
            user(textOfTokens(6000)),
            assistant(textOfTokens(6000)),
            user("run tests"),
            a3,
            t3,
        )
        val compacted = ContextCompactor.compact(history, budgetTokens = 2000)
        assertAlternatingRoles(compacted)
        assertCallResultIntegrity(compacted)
    }

    // ── 图片降级 ─────────────────────────────────────────────────────────

    @Test
    fun imagesInCompactedRegionBecomeTextNotes() {
        val history = listOf(
            user(textOfTokens(4000)),
            Message.User(
                listOf(
                    ContentBlock.Text("look at this"),
                    ContentBlock.Image("/img/old.png", "image/png"),
                )
            ),
            assistant(textOfTokens(4000)),
            user("latest"),
        )
        val compacted = ContextCompactor.compact(history, budgetTokens = 2000)
        val hasImageBlock = compacted.any { message ->
            when (message) {
                is Message.User -> message.content.any { it is ContentBlock.Image }
                is Message.Assistant -> message.message.content.any { it is ContentBlock.Image }
                is Message.ToolResult -> false
            }
        }
        assertFalse("digest 区域的图片应降级为文本注记", hasImageBlock)
    }

    // ── 断言辅助 ─────────────────────────────────────────────────────────

    /** 输出中每个 ToolResult 的前一条必须是带同 id ToolCall 的 Assistant。 */
    private fun assertCallResultIntegrity(history: List<Message>) {
        history.forEachIndexed { index, message ->
            if (message is Message.ToolResult) {
                assertTrue(
                    "ToolResult at $index without preceding Assistant",
                    index > 0,
                )
                val prev = history[index - 1] as? Message.Assistant
                assertTrue(
                    "ToolResult at $index preceded by ${history[index - 1]::class.simpleName}",
                    prev != null,
                )
                val callIds = prev!!.message.content
                    .filterIsInstance<ContentBlock.ToolCall>()
                    .map { it.id }
                assertTrue(
                    "ToolResult callId=${message.callId} has no matching call in previous Assistant",
                    message.callId in callIds,
                )
            }
        }
    }

    /** user/assistant 轮替检查（ToolResult 只允许出现在 Assistant 之后）。 */
    private fun assertAlternatingRoles(history: List<Message>) {
        var lastRole = "assistant" // 序列化语义上 digest 前 = assistant 回复
        history.forEachIndexed { index, message ->
            val role = when (message) {
                is Message.User -> "user"
                is Message.Assistant -> "assistant"
                is Message.ToolResult -> "tool"
            }
            if (role == "tool") {
                assertTrue("tool at $index must follow assistant", lastRole == "assistant")
            } else {
                assertFalse("consecutive $role at $index", lastRole == role)
            }
            lastRole = if (role == "tool") "assistant" else role
        }
    }

    private fun List<Message>.sameAs(other: List<Message>) =
        this == other || (this === other)
}
