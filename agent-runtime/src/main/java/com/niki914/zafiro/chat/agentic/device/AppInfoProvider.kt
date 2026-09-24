package com.niki914.zafiro.chat.agentic.device

import com.niki914.xposed.api.util.ContextProvider
import com.niki914.xposed.api.util.XProvider

object AppInfoProvider : XProvider<AppInfoCache>() {
    @Volatile
    private var installed = false

    /** A5：Context 等待上限 —— 冷启动早期 provide 未到时明确报错而非永久挂起。 */
    private const val CONTEXT_WAIT_TIMEOUT_MS = 10_000L

    suspend fun cache(): AppInfoCache {
        if (!installed) {
            val context = ContextProvider.await(CONTEXT_WAIT_TIMEOUT_MS)?.applicationContext
                ?: throw IllegalStateException(
                    "App info unavailable: application context not provided after " +
                            "${CONTEXT_WAIT_TIMEOUT_MS}ms."
                )
            installed = provide(AppInfoCache(context)) || installed
        }
        return await()
    }
}
