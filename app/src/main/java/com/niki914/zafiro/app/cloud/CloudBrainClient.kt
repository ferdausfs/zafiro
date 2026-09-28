package com.niki914.zafiro.app.cloud

import com.niki914.logging.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Cloud Brain 请求失败（HTTP 非 2xx / 网络 IO / 解析失败）。 */
class CloudBrainException(
    val httpCode: Int,
    message: String,
) : Exception(message)

/**
 * Cloud Brain 客户端（OkHttp + kotlinx-serialization）。
 *
 * 纯 HTTP 封装：不做轮询循环、不落地持久化——那些在 CloudBrainManager /
 * CloudOutboxDispatcher。每个实例绑定一组 (workerUrl, secret)，
 * 设置变更时由调用方重建实例。
 */
class CloudBrainClient(
    private val workerUrl: String,
    private val secret: String,
    baseClient: OkHttpClient? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val client: OkHttpClient = (baseClient?.newBuilder() ?: OkHttpClient().newBuilder())
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(LONG_POLL_WAIT_SECONDS + READ_TIMEOUT_HEADROOM_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** GET /v1/health —— 设置页「测试连接」。 */
    fun ping(): CloudBrainPing {
        return try {
            val body = execute(
                Request.Builder()
                    .url("$workerUrl/v1/health")
                    .get()
                    .build()
            )
            val payload = json.decodeFromString(HealthResponse.serializer(), body)
            CloudBrainPing(ok = true, message = payload.status)
        } catch (e: CloudBrainException) {
            CloudBrainPing(ok = false, message = "HTTP ${e.httpCode}: ${e.message}")
        } catch (e: IOException) {
            CloudBrainPing(ok = false, message = e.message ?: "network error")
        } catch (e: Exception) {
            Logger.w(LOG_TAG, "ping parse failed ${e.message}")
            CloudBrainPing(ok = false, message = e.message ?: "unexpected error")
        }
    }

    /** POST /v1/tasks。 */
    fun submitTask(submit: CloudTaskSubmit): CloudSessionSnapshot {
        val body = execute(
            Request.Builder()
                .url("$workerUrl/v1/tasks")
                .post(
                    json.encodeToString(CloudTaskSubmit.serializer(), submit)
                        .toRequestBody(JSON_MEDIA)
                )
                .build()
        )
        return decodeSnapshot(body)
    }

    /** GET /v1/sessions/{id}?cursor=&wait= —— 长轮询。 */
    fun poll(sessionId: String, cursor: Long): CloudSessionSnapshot {
        val body = execute(
            Request.Builder()
                .url("$workerUrl/v1/sessions/$sessionId?cursor=$cursor&wait=$LONG_POLL_WAIT_SECONDS")
                .get()
                .build()
        )
        return decodeSnapshot(body)
    }

    /** POST /v1/sessions/{id}/results。 */
    fun postResults(sessionId: String, results: CloudResultsPost): CloudSessionSnapshot {
        val body = execute(
            Request.Builder()
                .url("$workerUrl/v1/sessions/$sessionId/results")
                .post(
                    json.encodeToString(CloudResultsPost.serializer(), results)
                        .toRequestBody(JSON_MEDIA)
                )
                .build()
        )
        return decodeSnapshot(body)
    }

    /** POST /v1/sessions/{id}/cancel。 */
    fun cancel(sessionId: String) {
        execute(
            Request.Builder()
                .url("$workerUrl/v1/sessions/$sessionId/cancel")
                .post("{}".toRequestBody(JSON_MEDIA))
                .build()
        )
    }

    // ------------------------------------------------------------ internals

    private fun decodeSnapshot(body: String): CloudSessionSnapshot {
        return json.decodeFromString(CloudSessionSnapshot.serializer(), body)
    }

    private fun execute(request: Request): String {
        val authorized = request.newBuilder()
            .header("Authorization", "Bearer $secret")
            .build()
        client.newCall(authorized).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CloudBrainException(
                    httpCode = response.code,
                    message = extractError(responseBody, response.code),
                )
            }
            return responseBody
        }
    }

    private fun extractError(body: String, code: Int): String {
        return runCatching {
            val error = json.parseToJsonElement(body)
                .jsonObject["error"]
            (error as? JsonPrimitive)?.content
        }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "HTTP $code"
    }

    @Serializable
    private data class HealthResponse(val status: String)

    private companion object {
        private const val LOG_TAG = "niki914_nexus_CloudBrainClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val WRITE_TIMEOUT_SECONDS = 30L
        private const val LONG_POLL_WAIT_SECONDS = 25L
        private const val READ_TIMEOUT_HEADROOM_SECONDS = 15L
    }
}
