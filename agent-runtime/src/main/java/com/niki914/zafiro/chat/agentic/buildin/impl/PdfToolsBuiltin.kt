package com.niki914.zafiro.chat.agentic.buildin.impl

import com.niki914.logging.Logger
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream

/**
 * pdf_tools 工具（v1.7.0 Capability 4: Global File System Orchestration）。
 *
 * 基于 pdfbox-android 的 PDF 结构化操作：
 *  - merge：多份 PDF 合并（自动化备份/归档场景核心能力）
 *  - split：按页拆分为多份
 *  - extract_pages：抽取指定页生成新 PDF
 *  - page_count：页数查询
 *  - extract_text：文本抽取（小文件内联返回）
 *
 * 全部走内存映射 API，免 root、免 UI 自动化；输出绝不覆盖既有文件
 * （同名自动加后缀），保证任务可重试。
 */
class PdfToolsBuiltin : BuiltinTool() {

    override val name: String = "pdf_tools"

    override val description: String = """
Structured PDF operations (no root, no UI automation): merge multiple PDFs into one,
split a PDF into per-page files, extract selected pages into a new PDF, count pages,
and extract text. Use for document tasks like "merge these three reports" or
"extract the invoice pages". All paths must be absolute; outputs never overwrite
existing files.
    """.trimIndent()

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
        withContext(Dispatchers.IO) {
            val obj = try {
                if (request.argumentsJson.isBlank()) JsonObject(emptyMap())
                else Json.parseToJsonElement(request.argumentsJson) as? JsonObject
                    ?: JsonObject(emptyMap())
            } catch (t: Throwable) {
                return@withContext BuiltinToolResult.failure(
                    code = "INVALID_ARGUMENTS",
                    message = "pdf_tools arguments are not valid JSON: ${t.message}.",
                    hint = """Example: {"action":"merge","paths":["/storage/emulated/0/a.pdf","/storage/emulated/0/b.pdf"],"output":"/storage/emulated/0/merged.pdf"}"""
                )
            }
            fun str(key: String): String =
                obj[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()

            val action = str("action")
            try {
                when (action) {
                    "merge" -> merge(
                        paths = (obj["paths"] as? kotlinx.serialization.json.JsonArray)
                            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                            .orEmpty(),
                        output = str("output"),
                    )

                    "split" -> split(str("path"), str("output_dir"))
                    "extract_pages" -> extractPages(str("path"), str("pages"), str("output"))
                    "page_count" -> pageCount(str("path"))
                    "extract_text" -> extractText(str("path"), str("max_chars"))
                    else -> BuiltinToolResult.failure(
                        code = "UNKNOWN_ACTION",
                        message = "Unknown action '$action'.",
                        hint = "Valid: merge, split, extract_pages, page_count, extract_text."
                    )
                }
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "pdf action=$action failed: ${t.message}")
                BuiltinToolResult.failure(
                    code = if ((t.message ?: "").contains("password", true)) "PDF_ENCRYPTED"
                    else "PDF_OPERATION_FAILED",
                    message = "pdf_tools $action failed: ${t.message ?: t.javaClass.simpleName}.",
                    hint = "Verify the file is a valid, non-encrypted PDF; check page numbers exist."
                )
            }
        }

    // ------------------------------------------------------------------ merge

    private fun merge(paths: List<String>, output: String): BuiltinToolResult {
        if (paths.size < 2) {
            return BuiltinToolResult.failure(
                code = "NOT_ENOUGH_INPUTS",
                message = "merge needs at least 2 input PDFs, got ${paths.size}.",
                fieldErrors = mapOf("paths" to "at least 2 absolute PDF paths"),
            )
        }
        val inputs = paths.map { path ->
            val f = File(path)
            if (!f.isFile) throw IllegalArgumentException("Input not found: $path")
            f
        }
        val outFile = uniqueOutput(
            output.ifBlank {
                File(
                    inputs.first().parentFile,
                    "merged_${System.currentTimeMillis() % 100000}.pdf"
                ).absolutePath
            }
        )
        val merger = PDFMergerUtility()
        merger.destinationStream = FileOutputStream(outFile)
        inputs.forEach { merger.addSource(it) }
        merger.mergeDocuments(null)

        return BuiltinToolResult.success(
            message = "Merged ${inputs.size} PDF(s) → ${outFile.name} " +
                    "(${humanBytes(outFile.length())}).",
            data = buildJsonObject {
                put("output", outFile.absolutePath)
                put("bytes", outFile.length())
                put("inputs", buildJsonArray { inputs.forEach { add(JsonPrimitive(it.absolutePath)) } })
            },
            hint = "Verify with page_count if the merged document matters downstream."
        )
    }

    // ------------------------------------------------------------------ split

    private fun split(path: String, outputDir: String): BuiltinToolResult {
        val src = requirePdf(path)
        val destDir = File(outputDir.ifBlank {
            File(src.parentFile, src.nameWithoutExtension + "_split").absolutePath
        })
        destDir.mkdirs()
        PDDocument.load(src).use { doc ->
            val total = doc.numberOfPages
            for (i in 0 until total) {
                PDDocument().use { single ->
                    single.addPage(PDPage()) // 占位保证 importPage 目标树非空
                    single.importPage(doc.getPage(i))
                    single.save(File(destDir, "${src.nameWithoutExtension}_p${i + 1}.pdf"))
                }
            }
            return BuiltinToolResult.success(
                message = "Split ${src.name} into $total single-page PDF(s) in ${destDir.name}.",
                data = buildJsonObject {
                    put("output_dir", destDir.absolutePath)
                    put("pages", total)
                }
            )
        }
    }

    // ---------------------------------------------------------- extract_pages

    private fun extractPages(path: String, pagesSpec: String, output: String): BuiltinToolResult {
        val src = requirePdf(path)
        if (pagesSpec.isBlank()) {
            return BuiltinToolResult.failure(
                code = "MISSING_REQUIRED_FIELD",
                message = "Field 'pages' is required (e.g. \"1,3,5-8\").",
                fieldErrors = mapOf("pages" to "required"),
            )
        }
        val pages = parsePageSpec(pagesSpec)
        if (pages.isEmpty()) {
            return BuiltinToolResult.failure(
                code = "INVALID_PAGE_SPEC",
                message = "Could not parse page spec '$pagesSpec'.",
                hint = "Use 1-based page numbers: \"1,3,5-8\"."
            )
        }
        val outFile = uniqueOutput(
            output.ifBlank {
                File(
                    src.parentFile,
                    "${src.nameWithoutExtension}_extract_${System.currentTimeMillis() % 100000}.pdf"
                ).absolutePath
            }
        )
        PDDocument.load(src).use { doc ->
            val maxPage = doc.numberOfPages
            val invalid = pages.filter { it < 1 || it > maxPage }
            if (invalid.isNotEmpty()) {
                return BuiltinToolResult.failure(
                    code = "PAGE_OUT_OF_RANGE",
                    message = "Page(s) $invalid do not exist (document has $maxPage page(s)).",
                    hint = "Use page_count to check the total first."
                )
            }
            PDDocument().use { out ->
                pages.forEach { pageNo ->
                    out.importPage(doc.getPage(pageNo - 1))
                }
                out.save(outFile)
            }
        }
        return BuiltinToolResult.success(
            message = "Extracted ${pages.size} page(s) → ${outFile.name}.",
            data = buildJsonObject {
                put("output", outFile.absolutePath)
                put("pages", buildJsonArray { pages.forEach { add(JsonPrimitive(it)) } })
                put("bytes", outFile.length())
            }
        )
    }

    // ------------------------------------------------------------- page_count

    private fun pageCount(path: String): BuiltinToolResult {
        val src = requirePdf(path)
        PDDocument.load(src).use { doc ->
            return BuiltinToolResult.success(
                message = "${src.name} has ${doc.numberOfPages} page(s).",
                data = buildJsonObject {
                    put("path", src.absolutePath)
                    put("pages", doc.numberOfPages)
                    put("bytes", src.length())
                }
            )
        }
    }

    // ------------------------------------------------------------ extract_text

    private fun extractText(path: String, maxCharsRaw: String): BuiltinToolResult {
        val src = requirePdf(path)
        val maxChars = maxCharsRaw.toIntOrNull()?.coerceIn(500, 200_000) ?: 40_000
        PDDocument.load(src).use { doc ->
            val stripper = PDFTextStripper()
            val text = stripper.getText(doc)
            val truncated = text.length > maxChars
            return BuiltinToolResult.success(
                message = "Extracted ${text.length} chars from ${doc.numberOfPages} page(s)" +
                        (if (truncated) " (showing first $maxChars)." else "."),
                data = buildJsonObject {
                    put("path", src.absolutePath)
                    put("pages", doc.numberOfPages)
                    put("total_chars", text.length)
                    put("truncated", truncated)
                    put("text", if (truncated) text.take(maxChars) else text)
                },
                hint = "If truncated, use execute_python with the same path to read more."
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun requirePdf(path: String): File {
        if (path.isBlank()) throw IllegalArgumentException("'path' is required.")
        val f = File(path)
        if (!f.isFile) throw IllegalArgumentException("File not found: $path")
        // 有扩展名但不是 pdf 时提前报错；无扩展名时交给 pdfbox 按内容判定
        if (f.extension.isNotBlank() && !f.extension.equals("pdf", true)) {
            throw IllegalArgumentException("Not a PDF file: $path")
        }
        return f
    }

    /** "1,3,5-8" → [1,3,5,6,7,8]（1-based，去重升序）。 */
    private fun parsePageSpec(spec: String): List<Int> {
        val result = sortedSetOf<Int>()
        spec.split(',', ';').forEach { token ->
            val t = token.trim()
            if (t.isEmpty()) return@forEach
            val range = t.split('-')
            when {
                range.size == 2 -> {
                    val from = range[0].trim().toIntOrNull() ?: return@forEach
                    val to = range[1].trim().toIntOrNull() ?: return@forEach
                    if (from <= to) (from..to).forEach { result.add(it) }
                }

                else -> t.toIntOrNull()?.let(result::add)
            }
        }
        return result.toList()
    }

    private fun uniqueOutput(path: String): File {
        var target = File(path)
        if (target.exists()) {
            val base = target.nameWithoutExtension
            var i = 1
            while (target.exists()) {
                target = File(target.parentFile, "${base}_$i.${target.extension}")
                i++
            }
        }
        target.parentFile?.mkdirs()
        return target
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(java.util.Locale.US, bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }

    private companion object {
        private const val LOG_TAG = "niki914_nexus_PdfToolsBuiltin"

        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["merge", "split", "extract_pages", "page_count", "extract_text"],
      "description": "Which PDF operation to run."
    },
    "paths": {
      "type": "array",
      "items": {"type": "string"},
      "description": "merge only: absolute paths of 2+ input PDFs, in merge order."
    },
    "path": {
      "type": "string",
      "description": "Absolute path of the source PDF (split/extract_pages/page_count/extract_text)."
    },
    "output": {
      "type": "string",
      "description": "merge/extract_pages: absolute output path. Blank = auto-generated alongside input."
    },
    "output_dir": {
      "type": "string",
      "description": "split only: destination folder. Blank = '<name>_split' next to input."
    },
    "pages": {
      "type": "string",
      "description": "extract_pages only: 1-based spec like \"1,3,5-8\"."
    },
    "max_chars": {
      "type": "integer",
      "description": "extract_text only: max characters returned (default 40000)."
    }
  },
  "required": ["action"]
}
        """.trimIndent()
    }
}
