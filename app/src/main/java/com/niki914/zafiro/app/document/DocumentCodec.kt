package com.niki914.zafiro.app.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.niki914.logging.Logger
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * 通用文档 ingest 管线（Feature: Universal File Upload & Processing）。
 *
 * 支持格式与提取方式：
 *  - PDF：pdfbox-android（PDFTextStripper）
 *  - DOCX：zip 内 word/document.xml，剥标签提文本（w:t 节点，w:p 段落换行）
 *  - XLSX：zip 内 xl/sharedStrings.xml（共享字符串表，每条一行）
 *  - PPTX：zip 内 ppt/slides/slideN.xml 的 a:t 节点
 *  - TXT/MD/CSV/JSON/日志/代码等文本类：直接读
 *
 * 产出统一为 UTF-8 文本落盘 filesDir/document_cache/<sha256-8>.txt，
 * 随查询以上下文块注入（[DocumentContextBuilder]），Agent 也可用
 * execute_python 读取全量。
 */
internal class DocumentCodec(private val context: Context) {

    companion object {
        const val MAX_DOCUMENT_BYTES = 50 * 1024 * 1024 // 50MB 硬上限
        const val MAX_EXTRACTED_CHARS = 400_000 // 提取文本上限
        private const val LOG_TAG = "niki914_nexus_DocumentCodec"
    }

    /** 落盘文本的统一目录。 */
    private val documentsDir: File
        get() = File(context.filesDir, "document_cache").apply {
            if (!exists()) mkdirs()
        }

    sealed interface IngestResult {
        data class Ok(val document: IngestedDocument) : IngestResult
        data class Err(val error: IngestError) : IngestResult
    }

    sealed interface IngestError {
        data object FileNotFound : IngestError
        data object EmptyContent : IngestError
        data object UnsupportedFormat : IngestError
        data class TooLarge(val sizeBytes: Long) : IngestError
        data class IoFailed(val cause: Throwable?) : IngestError
    }

    data class IngestedDocument(
        /** 提取文本落盘路径（document_cache）。 */
        val path: String,
        val displayName: String,
        val mime: String,
        val sizeBytes: Long,
        /** 提取出的字符数（未截断前的全量）。 */
        val textLength: Int,
        /** 文本被截断到 [MAX_EXTRACTED_CHARS] 时为 true。 */
        val truncated: Boolean,
    )

    suspend fun ingestUri(uri: Uri): IngestResult = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val displayName = queryDisplayName(uri)
            val mime = resolver.getType(uri).orEmpty()
            val sizeHint = querySize(uri)

            if (sizeHint > MAX_DOCUMENT_BYTES) {
                return@withContext IngestResult.Err(IngestError.TooLarge(sizeHint))
            }

            // 读取源字节（上限内）
            val bytes = readBytes(uri) ?: return@withContext IngestResult.Err(
                IngestError.FileNotFound
            )
            if (bytes.isEmpty()) {
                return@withContext IngestResult.Err(IngestError.EmptyContent)
            }
            if (bytes.size > MAX_DOCUMENT_BYTES) {
                return@withContext IngestResult.Err(IngestError.TooLarge(bytes.size.toLong()))
            }

            val extension = displayName.substringAfterLast('.', "").lowercase()
            val extracted = extractText(bytes, mime, extension)
                ?: return@withContext IngestResult.Err(IngestError.UnsupportedFormat)

            val truncated = extracted.length > MAX_EXTRACTED_CHARS
            val finalText = if (truncated) extracted.take(MAX_EXTRACTED_CHARS) else extracted
            if (finalText.isBlank()) {
                return@withContext IngestResult.Err(IngestError.UnsupportedFormat)
            }

            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val path = File(
                documentsDir,
                digest.joinToString("") { "%02x".format(it) }.take(8) + ".txt",
            )
            path.writeText(finalText)

            IngestResult.Ok(
                IngestedDocument(
                    path = path.absolutePath,
                    displayName = displayName,
                    mime = mime.ifBlank { mimeForExtension(extension) },
                    sizeBytes = bytes.size.toLong(),
                    textLength = extracted.length,
                    truncated = truncated,
                )
            )
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "document ingest failed reason=${t.message}")
            IngestResult.Err(IngestError.IoFailed(t))
        }
    }

    // ── 提取 ────────────────────────────────────────────────────────────

    private fun extractText(bytes: ByteArray, mime: String, extension: String): String? {
        return when {
            // PDF：魔数或 mime/扩展名命中
            mime == "application/pdf" || extension == "pdf" || hasPdfMagic(bytes) ->
                extractPdf(bytes)

            // DOCX（zip 容器；老 .doc 二进制格式不支持）
            extension == "docx" || mime == DOCX_MIME -> extractDocx(bytes)

            extension == "xlsx" || mime == XLSX_MIME -> extractXlsx(bytes)
            extension == "pptx" || mime == PPTX_MIME -> extractPptx(bytes)

            // 文本类：txt/md/csv/json/log/xml/yaml/代码等直接读
            isTextLike(mime, extension) -> String(bytes, Charsets.UTF_8)

            // 未知类型但内容看起来是文本：按文本读（宽松策略）
            mime.isBlank() && looksLikeText(bytes) -> String(bytes, Charsets.UTF_8)

            else -> null
        }
    }

    private fun extractPdf(bytes: ByteArray): String? {
        return try {
            PDDocument.load(bytes).use { document ->
                PDFTextStripper().getText(document)
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "pdf extract failed reason=${t.message}")
            null
        }
    }

    /**
     * DOCX = zip：word/document.xml 中 <w:t>text</w:t> 是文本 run，
     * <w:p> 是段落（换行）。流式扫描 zip 条目，不整包解压。
     */
    private fun extractDocx(bytes: ByteArray): String? {
        return extractFromZip(bytes, targetEntry = { it == "word/document.xml" }) { xml ->
            val builder = StringBuilder()
            val paragraphRegex = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
            val textRegex = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
            paragraphRegex.findAll(xml).forEach { paragraph ->
                textRegex.findAll(paragraph.value).forEach { match ->
                    builder.append(decodeXmlEntities(match.groupValues[1]))
                }
                builder.append('\n')
            }
            // 无段落结构的兜底：直接抽 w:t
            if (builder.isBlank()) {
                textRegex.findAll(xml).forEach { builder.append(it.groupValues[1]).append(' ') }
            }
            builder.toString()
        }
    }

    /** XLSX：xl/sharedStrings.xml 的 <t> 节点（共享字符串表）。 */
    private fun extractXlsx(bytes: ByteArray): String? {
        return extractFromZip(bytes, targetEntry = { it == "xl/sharedStrings.xml" }) { xml ->
            Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .joinToString("\n") { decodeXmlEntities(it.groupValues[1]) }
        }
    }

    /** PPTX：ppt/slides/slideN.xml 的 <a:t> 节点。 */
    private fun extractPptx(bytes: ByteArray): String? {
        return extractFromZip(bytes, targetEntry = { it.startsWith("ppt/slides/slide") }) { xml ->
            Regex("<a:t(?:\\s[^>]*)?>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                .findAll(xml)
                .joinToString("\n") { decodeXmlEntities(it.groupValues[1]) }
        }
    }

    private fun extractFromZip(
        bytes: ByteArray,
        targetEntry: (String) -> Boolean,
        transform: (String) -> String,
    ): String? {
        return try {
            var extracted: String? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && extracted == null) {
                    if (targetEntry(entry.name)) {
                        val xml = zip.readBytes().toString(Charsets.UTF_8)
                        extracted = transform(xml)
                    }
                    entry = zip.nextEntry
                }
            }
            extracted?.takeIf(String::isNotBlank)
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "zip extract failed reason=${t.message}")
            null
        }
    }

    private fun decodeXmlEntities(raw: String): String {
        return raw
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
    }

    private fun isTextLike(mime: String, extension: String): Boolean {
        if (mime.startsWith("text/")) return true
        if (mime in setOf(
                "application/json", "application/xml", "application/javascript",
                "application/x-yaml", "application/toml", "application/yaml",
            )
        ) {
            return true
        }
        return extension in TEXT_EXTENSIONS
    }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        val sample = bytes.take(1024)
        // 大量控制字符视为二进制
        val controlCount = sample.count {
            it < 0x09 || (it in 0x0E..0x1F)
        }
        return sample.isNotEmpty() && controlCount == 0
    }

    private fun hasPdfMagic(bytes: ByteArray): Boolean {
        return bytes.size > 5 && bytes[0] == '%'.code.toByte() &&
                bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
    }

    private fun mimeForExtension(extension: String): String = when (extension) {
        "pdf" -> "application/pdf"
        "docx" -> DOCX_MIME
        "xlsx" -> XLSX_MIME
        "pptx" -> PPTX_MIME
        "json" -> "application/json"
        "csv" -> "text/csv"
        "md" -> "text/markdown"
        else -> "text/plain"
    }

    // ── IO 辅助 ─────────────────────────────────────────────────────────

    private fun readBytes(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBoundedBytes(MAX_DOCUMENT_BYTES + 1)
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "read uri failed reason=${t.message}")
            null
        }
    }

    private fun InputStream.readBoundedBytes(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(available().coerceAtLeast(8192), limit))
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val chunkSize = read(buffer)
            if (chunkSize <= 0) break
            total += chunkSize
            output.write(buffer, 0, chunkSize)
            if (total > limit) {
                // 超限即返回，由调用方按长度判定 TooLarge
                return output.toByteArray()
            }
        }
        return output.toByteArray()
    }

    private fun queryDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf(String::isNotBlank)
                } else {
                    null
                }
            }
        } catch (t: Throwable) {
            null
        } ?: "document-${System.currentTimeMillis() % 100000}"
    }

    private fun querySize(uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri, arrayOf(OpenableColumns.SIZE), null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        } catch (t: Throwable) {
            null
        } ?: 0L
    }
}

private const val DOCX_MIME =
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
private const val XLSX_MIME =
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
private const val PPTX_MIME =
    "application/vnd.openxmlformats-officedocument.presentationml.presentation"

private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml",
    "ini", "cfg", "conf", "properties", "env", "log", "html", "htm", "css",
    "js", "ts", "jsx", "tsx", "kt", "kts", "java", "py", "rb", "go", "rs", "c",
    "h", "cpp", "hpp", "cs", "swift", "sh", "bat", "ps1", "sql", "gradle", "dart",
    "php", "lua", "r", "m", "pl", "scss", "less",
)

/**
 * 附件文档的查询上下文块构造器：把 ingest 落盘的提取文本以明确分隔的结构
 * 注入用户查询前方。内联上限 60k 字符（≈1.5 万 token）；超长时注入预览 +
 * 全文落盘路径提示，Agent 可用 execute_python 读取剩余部分。
 */
internal object DocumentContextBuilder {

    const val INLINE_CHAR_LIMIT = 60_000

    /** 上下文块输入：与具体 UI/repo 模型解耦（HomeChatDocument / IngestedDocument 都可映射）。 */
    data class DocRef(
        val path: String,
        val displayName: String,
        val mime: String,
        val sizeBytes: Long,
    )

    fun build(
        documents: List<DocRef>,
        readFile: (String) -> String,
    ): String {
        if (documents.isEmpty()) return ""
        return documents.joinToString("\n\n") { document ->
            buildBlock(document, readFile(document.path))
        }
    }

    private fun buildBlock(document: DocRef, fullText: String): String {
        val sizeLabel = formatSize(document.sizeBytes)
        return buildString {
            appendLine("[DOCUMENT_CONTEXT]")
            appendLine("file: ${document.displayName} (${document.mime}, $sizeLabel)")
            appendLine("extracted-path: ${document.path}")
            appendLine("--- extracted text begin ---")
            if (fullText.length > INLINE_CHAR_LIMIT) {
                appendLine(fullText.take(INLINE_CHAR_LIMIT))
                appendLine(
                    "--- text truncated (showing $INLINE_CHAR_LIMIT of " +
                            "${fullText.length} chars) ---"
                )
                appendLine(
                    "The full extracted text is saved at extracted-path above; " +
                            "use execute_python to read the remainder if needed."
                )
            } else {
                appendLine(fullText)
                appendLine("--- extracted text end ---")
            }
        }.trimEnd()
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024f / 1024f)
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
            else -> "$bytes B"
        }
    }
}
