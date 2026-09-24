package com.niki914.xposed.api.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

abstract class XProvider<T> {

    private val contextDeferred = CompletableDeferred<T>()

    fun provide(t: T) = contextDeferred.complete(t)

    suspend fun await(): T {
        return contextDeferred.await()
    }

    /**
     * 带 [timeoutMillis] 上限的等待：超时返回 null 而不是永久挂起。
     *
     * [await] 无限期等待在冷启动早期（provide 尚未被调用）会把调用协程
     * 永久挂在 CompletableDeferred 上（终端/Python/Vision 初始化全部卡死）。
     * 需要安全降级的路径一律用本方法，由调用方决定 fallback。
     */
    suspend fun await(timeoutMillis: Long): T? {
        if (contextDeferred.isCompleted) return contextDeferred.getCompleted()
        return withTimeoutOrNull(timeoutMillis) { contextDeferred.await() }
    }

    /** 非挂起快照读：未 provide 时返回 null，供无法 suspend 的路径（同步过滤器等）使用。 */
    fun awaitIfAvailable(): T? = if (contextDeferred.isCompleted) contextDeferred.getCompleted() else null
}
