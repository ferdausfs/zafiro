package com.niki914.zafiro.repo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 脚本/配置 → Provider 导入器（Feature: Script-to-Config Importer）。
 *
 * 从用户粘贴的任意文本中提取接入三要素：base_url / api_key / model_id。
 * 支持的输入形态（按优先级尝试）：
 *  1. JSON 配置（含嵌套 config/client 对象）：键名接受 base_url|baseUrl|endpoint|
 *     api_base|API_BASE / api_key|apiKey|key|token|API_KEY / model|model_id|modelId|
 *     model_name|MODEL（models 数组也收）
 *  2. Python 脚本：赋值语句（base_url = "…"）、OpenAI(client) 构造参数、
 *     requests.post("…/v1/chat/completions")、os.environ.get("X") 常量名提示
 *  3. cURL 命令：URL + Authorization: Bearer 头 + -d '{"model": …}'
 *  4. 兜底行扫描：任何 https URL（/v1 结尾或含 chat/completions）与 sk- 形态密钥
 *
 * 纯 Kotlin、无 Android 依赖，可单测。
 */
object ProviderScriptImporter {

    /** 一次成功的解析结果；字段可能不全（调用方对缺项让用户补填）。 */
    data class ImportedProvider(
        val baseUrl: String,
        val apiKey: String,
        val modelIds: List<String>,
        /** 从输入里推断的名字（域名 / 变量名 / JSON name 字段），可被用户改。 */
        val suggestedName: String,
    )

    fun parse(raw: String): ImportedProvider? {
        val text = raw.trim()
        if (text.isBlank()) return null

        var baseUrl = ""
        var apiKey = ""
        val models = mutableListOf<String>()
        var name = ""

        // 1. JSON 优先（粘进来的 config 可能裹在 markdown 代码块里）
        val json = extractJsonObject(text)
        if (json != null) {
            flattenJson(json).let { flat ->
                baseUrl = flat.firstOrNull { it.first in BASE_URL_KEYS }?.second.orEmpty()
                apiKey = flat.firstOrNull { it.first in API_KEY_KEYS }?.second.orEmpty()
                flat.filter { it.first in MODEL_KEYS }.forEach { models += it.second }
            }
            // JSON 的 name 字段作名字提示
            name = json.scalar(NAME_KEYS) ?: ""
        }

        // 2. Python / 通用赋值语句
        if (baseUrl.isBlank()) baseUrl = matchAssign(text, BASE_URL_KEYS, ASSIGNMENT_PATTERNS)
        if (apiKey.isBlank()) apiKey = matchAssign(text, API_KEY_KEYS, ASSIGNMENT_PATTERNS)
        val assignModel = matchAssign(text, MODEL_KEYS, ASSIGNMENT_PATTERNS)
        if (assignModel.isNotBlank()) models += assignModel

        // OpenAI(base_url="…", api_key="…") 构造参数（赋值正则已覆盖同形态，这里补位置无关的引号参数）
        if (apiKey.isBlank()) apiKey = matchQuotedAfter(text, listOf("api_key", "apikey", "token", "bearer"))
        if (baseUrl.isBlank()) baseUrl = matchQuotedAfter(text, listOf("base_url", "baseurl", "api_base"))

        // 3. cURL / 任意文本中的 URL 与 Bearer
        if (apiKey.isBlank()) {
            Regex("(?:authorization|Authorization)\\s*[:=]\\s*Bearer\\s+([A-Za-z0-9._\\-~+]{8,})")
                .find(text)?.groupValues?.get(1)?.let { apiKey = it }
        }
        if (apiKey.isBlank()) {
            // 裸密钥形态：sk-… / ghp_… / 长十六进制
            Regex("\\b(?:sk-[A-Za-z0-9_\\-]{16,}|ghp_[A-Za-z0-9]{20,}|[a-f0-9]{32,64})\\b")
                .find(text)?.value?.let { apiKey = it }
        }

        val urls = URL_REGEX.findAll(text).map { it.value }.toList()
        if (baseUrl.isBlank()) {
            baseUrl = urls.firstOrNull { it.contains("/v1") || it.contains("chat/completions") }
                ?: urls.firstOrNull()
                .orEmpty()
        }
        // URL 带了完整路径 → 收敛到 /v1 根（客户端按协议拼路径）
        baseUrl = normalizeBaseUrl(baseUrl)

        // -d '{"model": "…"}' / "model": "…" 的 JSON 内 model 已由步骤 1 覆盖；
        // 这里补 curl 参数与散落的 model= 形态
        Regex("\"model\"\\s*:\\s*\"([^\"]+)\"").findAll(text).forEach { models += it.groupValues[1] }

        if (name.isBlank()) {
            name = baseUrl.takeIf(String::isNotBlank)
                ?.let { runCatching { java.net.URI(it).host }.getOrNull() }
                .orEmpty()
                .removePrefix("api.")
                .removePrefix("www.")
                .substringBefore('.')
                .replaceFirstChar { it.uppercase() }
        }
        if (name.isBlank()) name = "Custom Provider"

        val cleanModels = models.map(String::trim).filter(String::isNotEmpty).distinct()
        if (baseUrl.isBlank() && apiKey.isBlank() && cleanModels.isEmpty()) return null

        return ImportedProvider(
            baseUrl = baseUrl,
            apiKey = apiKey,
            modelIds = cleanModels,
            suggestedName = name.trim(),
        )
    }

    /** 从 markdown 代码块或裸文本中提取第一个 JSON 对象。 */
    private fun extractJsonObject(text: String): JsonObject? {
        val stripped = text
            .replace(Regex("```(?:json)?", RegexOption.IGNORE_CASE), "")
            .trim()
        // 直接整体是 JSON
        parseJson(stripped)?.let { return it }
        // 文本中 { … } 逐步尝试（从第一个 { 到最后一个 }）
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start >= 0 && end > start) {
            parseJson(stripped.substring(start, end + 1))?.let { return it }
        }
        return null
    }

    private fun parseJson(candidate: String): JsonObject? {
        return try {
            Json.parseToJsonElement(candidate).let { element ->
                when (element) {
                    is JsonObject -> element.jsonObject
                    is JsonArray -> element.firstOrNull()?.let { inner ->
                        (inner as? JsonObject)?.jsonObject
                    }

                    else -> null
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 展平一层嵌套（config / client / provider / openai 等包裹对象）。 */
    private fun flattenJson(root: JsonObject): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun walk(obj: JsonObject) {
            obj.forEach { (key, value) ->
                when (value) {
                    is JsonPrimitive -> out += key.lowercase() to value.jsonPrimitive.content
                    is JsonObject -> walk(value)
                    is JsonArray -> {
                        (value.firstOrNull() as? JsonPrimitive)?.contentOrNull?.let { content ->
                            out += key.lowercase() to content
                        }
                    }
                }
            }
        }
        walk(root)
        // 常见包裹层再探一层（flatten 已递归，这里冗余防御无需）
        return out
    }

    private fun JsonObject.scalar(keys: List<String>): String? {
        fun dig(obj: JsonObject): String? {
            obj.forEach { (key, value) ->
                if (key.lowercase() in keys && value is JsonPrimitive) {
                    return value.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                }
                if (value is JsonObject) dig(value)?.let { return it }
            }
            return null
        }
        return dig(this)
    }

    /** base_url = "…" / BASE_URL: '…' / openai_api_key="…" 等赋值形态。 */
    private fun matchAssign(text: String, keys: List<String>, patterns: List<Regex>): String {
        for (key in keys) {
            for (pattern in patterns) {
                val regex = Regex("(?i)[\"']?${Regex.escape(key)}[\"']?\\s*${pattern.pattern}")
                regex.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
                    ?.let { return stripWrappers(it) }
            }
        }
        return ""
    }

    /** openai(api_key="…") / client = OpenAI(api_key='…') 中参数名后跟的引号串。 */
    private fun matchQuotedAfter(text: String, paramNames: List<String>): String {
        for (name in paramNames) {
            Regex("(?i)[\"']?${Regex.escape(name)}[\"']?\\s*[=:]\\s*[\"']([^\"']{8,})[\"']")
                .find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf(String::isNotBlank)
                ?.let { return stripWrappers(it) }
        }
        return ""
    }

    private val ASSIGNMENT_PATTERNS = listOf(
        Regex("[=: ]\\s*[\"']([^\"']+)[\"']"), // base_url = "https://…"
        Regex("[=: ]\\s*([^,\\s)\"']+)"), // BASE_URL=https://…（无引号）
    )

    private val BASE_URL_KEYS = listOf(
        "base_url", "baseurl", "base-url", "api_base", "apibase", "endpoint",
        "base", "host", "url", "server_url", "openai_api_base", "openai_base_url",
    )
    private val API_KEY_KEYS = listOf(
        "api_key", "apikey", "api-key", "key", "token", "secret", "password",
        "openai_api_key", "authorization", "bearer", "access_token", "auth_token",
    )
    private val MODEL_KEYS = listOf(
        "model", "model_id", "modelid", "model-id", "model_name", "modelname",
        "models", "deployment", "modelid_or_name",
    )
    private val NAME_KEYS = listOf("name", "provider", "provider_name", "title", "label")

    private val URL_REGEX = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&'()*+,;=%\\-]+")

    /** https://host/v1/chat/completions → https://host/v1；保留 /v1 根。 */
    fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isBlank()) return ""
        return when {
            trimmed.contains("/chat/completions") ->
                trimmed.substring(0, trimmed.indexOf("/chat/completions"))

            trimmed.endsWith("/completions") ->
                trimmed.removeSuffix("/completions")

            else -> trimmed
        }
    }

    private fun stripWrappers(value: String): String = value.trim().trim('"', '\'')
}
