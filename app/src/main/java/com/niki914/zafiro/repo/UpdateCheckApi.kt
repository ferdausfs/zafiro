package com.niki914.zafiro.repo

import android.os.Build
import com.niki914.xposed.api.util.xTry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request

data class UpdateCheckResult(
    val hasUpdate: Boolean,
    val remoteVersion: String?,
    val releaseUrl: String?,
    /** 与设备 ABI 匹配的 APK 直链（in-app 更新用）；null = 仅能跳浏览器 */
    val apkUrl: String? = null,
    val apkName: String? = null,
    val apkSizeBytes: Long = 0,
)

object UpdateCheckHolder {
    private val _result = MutableStateFlow<UpdateCheckResult?>(null)
    val result: StateFlow<UpdateCheckResult?> = _result.asStateFlow()

    private var fired = false
    private var dismissed = false

    suspend fun runOnce(currentVersion: String) {
        if (fired) return
        fired = true
        val r = UpdateCheckApi.check(currentVersion)
        _result.value = r
    }

    fun dismiss() {
        dismissed = true
        _result.value =
            UpdateCheckResult(hasUpdate = false, remoteVersion = null, releaseUrl = null)
    }

    fun isDismissed(): Boolean = dismissed
}

private object UpdateCheckApi {
    private val client = SharedHttp.client
    private val json = Json { ignoreUnknownKeys = true }

    // v2.1.2: 分发渠道 = 用户自己的 fork（此前检查上游 niki914/zafiro，
    // fork 的 release 永远检测不到，导致每次都要手动去 GitHub 下载安装）
    private const val GITHUB_API_LATEST =
        "https://api.github.com/repos/ferdausfs/zafiro/releases/latest"
    private const val GITHUB_API_LATEST_ANY =
        "https://api.github.com/repos/ferdausfs/zafiro/releases?per_page=1"

    private val semverRe = Regex("""(\d+\.\d+\.\d+)""")

    suspend fun check(currentVersion: String): UpdateCheckResult {
        return withContext(Dispatchers.IO) {
            xTry { resolveUpdateOrNull(currentVersion) } ?: noUpdate()
        }
    }

    private fun resolveUpdateOrNull(currentVersion: String): UpdateCheckResult {
        // 先看最新 stable release；无更新时兜底看最新 release（含 prerelease，
        // preview 分发线靠它才能被检测到）
        return checkRelease(fetchLatestRelease(), currentVersion, includePrerelease = false)
            ?: checkRelease(fetchLatestReleaseAny(), currentVersion, includePrerelease = true)
            ?: noUpdate()
    }

    private fun checkRelease(
        body: String?,
        currentVersion: String,
        includePrerelease: Boolean,
    ): UpdateCheckResult? {
        if (body == null) return null
        val element = json.parseToJsonElement(body)
        val obj = when (element) {
            is JsonObject -> element
            is JsonArray -> element.firstOrNull() as? JsonObject
            else -> null
        } ?: return null

        if (obj["draft"]?.jsonPrimitive?.booleanOrNull == true) return null
        if (!includePrerelease && obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true) return null

        val tagName = obj["tag_name"]?.jsonPrimitive?.content ?: return null
        val remoteVersion = semverRe.find(tagName)?.groupValues?.get(1) ?: return null

        if (!isNewer(remoteVersion, currentVersion)) return null

        val releaseUrl = obj["html_url"]?.jsonPrimitive?.content.orEmpty()

        // 解析 assets，选出与本机 ABI 匹配的 APK（失败不阻塞更新提示，退回浏览器路径）
        val apks = (obj["assets"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { asset ->
                val name = asset["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L
                Triple(name, url, size)
            }
            .orEmpty()
        val picked = pickApkAssetName(Build.SUPPORTED_ABIS.toList(), apks.map { it.first })
        val chosen = apks.firstOrNull { it.first == picked }

        return UpdateCheckResult(
            hasUpdate = true,
            remoteVersion = remoteVersion,
            releaseUrl = releaseUrl,
            apkUrl = chosen?.second,
            apkName = chosen?.first,
            apkSizeBytes = chosen?.third ?: 0,
        )
    }

    private fun fetchLatestRelease(): String? = fetch(GITHUB_API_LATEST)

    private fun fetchLatestReleaseAny(): String? = fetch(GITHUB_API_LATEST_ANY)

    private fun fetch(url: String): String? {
        val request = Request.Builder().url(url).build()
        return xTry {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful && response.body != null) {
                    response.body!!.string()
                } else {
                    null
                }
            }
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rp = r.getOrElse(i) { 0 }
            val cp = c.getOrElse(i) { 0 }
            if (rp > cp) return true
            if (rp < cp) return false
        }
        return false
    }

    private fun noUpdate() =
        UpdateCheckResult(hasUpdate = false, remoteVersion = null, releaseUrl = null)
}

/**
 * 按设备 ABI 从 release assets 里选 APK：arm64 设备优先 *arm64*.apk（体积小一半），
 * 其余（32 位 / 未知 ABI）用 *universal*.apk；两者皆缺时退回任意 .apk。
 * 纯函数，单测覆盖。
 */
internal fun pickApkAssetName(abis: List<String>, assetNames: List<String>): String? {
    val apks = assetNames.filter { it.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return null
    val preferArm64 = abis.any { it.equals("arm64-v8a", ignoreCase = true) }
    val keyword = if (preferArm64) "arm64" else "universal"
    return apks.firstOrNull { it.contains(keyword, ignoreCase = true) }
        ?: apks.first()
}
