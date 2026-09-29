package com.niki914.zafiro.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.settings.model.RuntimeVaultTokenSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭证库（设置 → Tokens）。
 *
 * 与其他设置不同：凭证 明文绝不允许落盘为可读 JSON，也不进入 XIpc 设置同步链路
 * （store 文件是明文 JSON，且会同步给宿主进程）。这里采用：
 *  - 值：AndroidKeyStore AES-256/GCM 加密后存储，密钥不出安全硬件/系统服务；
 *  - 摘要与备注：非敏感，随条目明文存储；
 *  - 存放：应用私有 SharedPreferences（MODE_PRIVATE，仅本进程可读）。
 *
 * Agent 通过 RuntimeSettingsGateway（vault_token 工具）访问：list 只暴露 name/note，
 * get 按精确名称取值。UI 通过 [reveal]/[value] 取值。
 *
 * 修复记录（v2.1.2+）：AndroidKeyStore 密钥在 卸载重装/系统迁移/部分 OEM OTA 后会丢失，
 * 而加密条目（SharedPreferences）可能经 Auto Backup 还原回来——两者一旦不配对，
 * value() 解密失败返回 null，运行时就会以「空 API key」发出请求，远端表现为
 * 401 Unauthorized（AiGatewayError 2009），用户侧看似「key 没变却失效」。
 * 现在检测该状态（[keyLostDetected] / [health]）并输出可行动的 ERROR 日志，
 * 且本 prefs 文件已排除出备份（见 data_extraction_rules / full_backup_content），
 * 避免还原出「看起来存在但永远解不开」的幽灵条目。
 */
object TokenVault {

    @Serializable
    data class EncryptedEntry(
        val name: String,
        val note: String = "",
        /** Base64(iv + ciphertext)。 */
        val valueCt: String,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L,
    )

    @Serializable
    private data class VaultDocument(
        val tokens: List<EncryptedEntry> = emptyList(),
    )

    private const val LOG_TAG = "niki914_nexus_TokenVault"
    private const val PREFS_NAME = "zafiro_token_vault"
    private const val DOC_KEY = "document"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "zafiro_token_vault_key"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    const val MAX_TOKENS = 64

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private var appContext: Context? = null

    /**
     * Keystore 密钥与加密条目失配（解密必然失败）的状态位。
     * 置位后所有既有条目都不可解密；只能由用户重新录入 token 恢复。
     */
    @Volatile
    var keyLostDetected: Boolean = false
        private set

    /** Vault 健康状态快照（UI 可用于提示「需要重新录入 token」）。 */
    data class VaultHealth(
        val entries: Int,
        val keyLost: Boolean,
    )

    suspend fun health(): VaultHealth = withContext(Dispatchers.IO) {
        VaultHealth(entries = readDocument().tokens.size, keyLost = keyLostDetected)
    }

    private fun markKeyLost(reason: String) {
        if (!keyLostDetected) {
            keyLostDetected = true
            Logger.e(
                LOG_TAG,
                "KEYSTORE KEY LOST: encrypted token entries exist but the AndroidKeyStore " +
                    "key cannot decrypt them ($reason). Requests using vault-referenced API keys " +
                    "will go out UNAUTHENTICATED and fail with remote 401. Recovery: re-enter the " +
                    "affected tokens in Settings → Tokens (or re-save the API key in the config).",
            )
        }
    }

    internal fun init(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
        }
    }

    private suspend fun context(): Context {
        appContext?.let { return it }
        val context = ContextProvider.await()
        init(context)
        return appContext ?: context
    }

    /** 摘要列表（不含明文），按 name 排序。 */
    suspend fun list(): List<RuntimeVaultTokenSummary> = withContext(Dispatchers.IO) {
        readDocument().tokens
            .sortedBy { it.name.lowercase() }
            .map { RuntimeVaultTokenSummary(name = it.name, note = it.note, updatedAt = it.updatedAt) }
    }

    /** 新增或覆盖（同名覆盖，大小写不敏感判定）。 */
    suspend fun put(name: String, value: String, note: String = "") {
        val trimmedName = name.trim()
        require(trimmedName.isNotBlank()) { "Token name is required." }
        require(value.isNotEmpty()) { "Token value is required." }
        val trimmedNote = note.trim()
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val context = context()
                val document = readDocument()
                val now = System.currentTimeMillis()
                val normalized = trimmedName.lowercase()
                val existing = document.tokens.firstOrNull { it.name.lowercase() == normalized }
                val entry = EncryptedEntry(
                    name = trimmedName,
                    note = trimmedNote,
                    valueCt = encryptOrThrow(context, value),
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
                val updated = document.tokens
                    .filterNot { it.name.lowercase() == normalized }
                    .plus(entry)
                require(updated.size <= MAX_TOKENS) { "Vault is full (max $MAX_TOKENS tokens)." }
                writeDocument(context, VaultDocument(tokens = updated))
            }
        }
        Logger.i(LOG_TAG, "put name=$trimmedName valueLength=${value.length}")
    }

    /** 按精确名称删除；存在返回 true。 */
    suspend fun delete(name: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val context = context()
            val document = readDocument()
            val target = document.tokens.firstOrNull { it.name == name.trim() }
                ?: return@withContext false
            writeDocument(
                context,
                VaultDocument(tokens = document.tokens.filterNot { it.name == target.name }),
            )
            true
        }.also { removed ->
            if (removed) Logger.i(LOG_TAG, "delete name=$name")
        }
    }

    /** 按精确名称取回明文（gateway / UI reveal 共用）。 */
    suspend fun value(name: String): String? = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext null
        context() // 确保 appContext 就绪，避免 init 前读到空文档误判为「不存在」
        val entry = readDocument().tokens.firstOrNull { it.name == trimmed }
            ?: return@withContext null
        try {
            decryptOrThrow(context(), entry.valueCt)
        } catch (error: Throwable) {
            // Keystore 密钥被清（卸载重装/系统还原）等：不可解密按不存在处理，
            // 绝不让坏条目崩溃调用方——但必须把「key 丢失」暴露出来，
            // 否则调用方拿到 null → 空 key 发请求 → 远端 401，用户无从排查。
            Logger.w(LOG_TAG, "decrypt failed name=$trimmed reason=${error.message}")
            markKeyLost(error.message.orEmpty())
            null
        }
    }

    private fun readDocument(): VaultDocument {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?: return VaultDocument()
        val raw = prefs.getString(DOC_KEY, null) ?: return VaultDocument()
        return try {
            json.decodeFromString<VaultDocument>(raw)
        } catch (error: SerializationException) {
            Logger.w(LOG_TAG, "vault document parse failed: ${error.message}")
            VaultDocument()
        } catch (error: IllegalArgumentException) {
            Logger.w(LOG_TAG, "vault document parse failed: ${error.message}")
            VaultDocument()
        }
    }

    private fun writeDocument(context: Context, document: VaultDocument) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(DOC_KEY, json.encodeToString(document))
            .apply()
    }

    private fun secretKey(context: Context): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        // 密钥不存在但库里已有条目：意味着条目是用「已丢失的旧密钥」加密的。
        // 这里仍需生成新密钥（让新 put 可用），但必须留下显式痕迹，
        // 严禁静默吞掉——静默正是「401 疑似 key 失效」难排查的根源。
        if (readDocument().tokens.isNotEmpty()) {
            markKeyLost("keystore key alias '$KEY_ALIAS' missing while ${readDocument().tokens.size} entries exist")
        }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        Logger.i(
            LOG_TAG,
            "keystore key generated pid=${android.os.Process.myPid()} pkg=${context.packageName}"
        )
        return generator.generateKey()
    }

    private fun encryptOrThrow(context: Context, plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Keystore（randomizedEncryptionRequired 默认 true）每次加密生成新随机 IV，cipher.iv 取回。
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(context))
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}." }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val sealed = iv + ciphertext
        return android.util.Base64.encodeToString(sealed, android.util.Base64.NO_WRAP)
    }

    private fun decryptOrThrow(context: Context, sealedBase64: String): String {
        val sealed = android.util.Base64.decode(sealedBase64, android.util.Base64.NO_WRAP)
        require(sealed.size > GCM_IV_BYTES) { "Corrupted sealed value." }
        val iv = sealed.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = sealed.copyOfRange(GCM_IV_BYTES, sealed.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(context), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }
}
