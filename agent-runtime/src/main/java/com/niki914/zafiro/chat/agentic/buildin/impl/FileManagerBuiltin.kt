package com.niki914.zafiro.chat.agentic.buildin.impl

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * file_manager 工具（v1.7.0 Capability 4: Global File System Orchestration）。
 *
 * 全盘文件编排：list/read/write/move/copy/rename/delete/mkdir/search/
 * sort_folder（批量归类）/disk_usage/zip/unzip。
 *
 * 作用域：
 *  - App 私有目录（filesDir/cacheDir）：零权限，始终可用；
 *  - 共享存储（/storage/emulated/0/...）：需要「所有文件访问」权限
 *    （MANAGE_EXTERNAL_STORAGE，Android 11+ 用户在系统设置里授予）。
 *
 * Agent 的复杂文件任务（批量整理、备份、归档）优先走本工具，而不是
 * terminal shell 命令 —— 结构化结果 + 跨进程一致 + 免 root。
 */
class FileManagerBuiltin : BuiltinTool() {

    override val name: String = "file_manager"

    override val description: String = """
Full file-system orchestration over the device's shared storage and the app's private
directories. Actions: list, read_text, write_text, move, copy, rename, delete, mkdir,
search (glob), sort_folder (bulk-organize a folder by type/date/name/samsung), disk_usage,
zip, unzip. Use this INSTEAD of shell commands for file tasks: results are structured,
survive across processes, and need no root. Paths must be absolute
(e.g. /storage/emulated/0/Download/report.pdf). Bulk organization and automated
backups are exactly what this tool is for.
    """.trimIndent()

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
        withContext(Dispatchers.IO) {
            val args = try {
                parseArguments(request.argumentsJson)
            } catch (t: Throwable) {
                return@withContext BuiltinToolResult.failure(
                    code = "INVALID_ARGUMENTS",
                    message = "file_manager arguments could not be parsed: ${t.message ?: "invalid JSON"}.",
                    hint = "Provide a JSON object with an 'action' field."
                )
            }

            try {
                execute(args)
            } catch (t: Throwable) {
                Logger.w(LOG_TAG, "action=${args.action} failed: ${t.message}")
                BuiltinToolResult.failure(
                    code = if (isPermissionError(t)) "STORAGE_ACCESS_DENIED" else "FILE_OPERATION_FAILED",
                    message = "file_manager ${args.action} failed: ${t.message ?: t.javaClass.simpleName}.",
                    hint = if (isPermissionError(t))
                        "Shared storage needs 'All files access'. Ask the user to grant it in " +
                                "Zafiro Settings → System Integration, then retry."
                    else
                        "Check the paths exist and are spelled correctly; list the parent directory first."
                )
            }
        }

    // ---------------------------------------------------------------- dispatch

    private fun execute(args: Args): BuiltinToolResult = when (args.action) {
        "list" -> list(args)
        "read_text" -> readText(args)
        "write_text" -> writeText(args)
        "move" -> move(args)
        "copy" -> copy(args)
        "rename" -> rename(args)
        "delete" -> delete(args)
        "mkdir" -> mkdir(args)
        "search" -> search(args)
        "sort_folder" -> sortFolder(args)
        "disk_usage" -> diskUsage(args)
        "zip" -> zip(args)
        "unzip" -> unzip(args)
        else -> BuiltinToolResult.failure(
            code = "UNKNOWN_ACTION",
            message = "Unknown action '${args.action}'.",
            hint = "Valid: list, read_text, write_text, move, copy, rename, delete, mkdir, " +
                    "search, sort_folder, disk_usage, zip, unzip."
        )
    }

    // ------------------------------------------------------------------- list

    private fun list(args: Args): BuiltinToolResult {
        val dir = requireDir(args.path)
        val files = dir.listFiles()
            ?: return BuiltinToolResult.failure(
                code = "LIST_FAILED",
                message = "Cannot read directory: ${args.path}",
                hint = "The path may not exist or may not be a directory."
            )
        val sorted = files.sortedWith(
            compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }
        ).take(args.limit)
        val entries = buildJsonArray {
            sorted.forEach { f ->
                add(buildJsonObject {
                    put("name", f.name)
                    put("path", f.absolutePath)
                    put("type", if (f.isDirectory) "dir" else "file")
                    if (f.isFile) put("bytes", f.length())
                    put("modified", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(Date(f.lastModified())))
                })
            }
        }
        return BuiltinToolResult.success(
            message = "Listed ${sorted.size} of ${files.size} entr(ies) in ${args.path}.",
            data = buildJsonObject {
                put("total", files.size)
                put("entries", entries)
            }
        )
    }

    // -------------------------------------------------------------- read_text

    private fun readText(args: Args): BuiltinToolResult {
        val file = requireFile(args.path)
        if (file.length() > MAX_READ_BYTES) {
            return BuiltinToolResult.failure(
                code = "FILE_TOO_LARGE",
                message = "File is ${file.length()} bytes (limit $MAX_READ_BYTES).",
                hint = "For large files use execute_python to stream-read in chunks, or read a specific line range via terminal."
            )
        }
        val text = file.readText(Charsets.UTF_8)
        val truncated = text.length > args.maxChars
        val shown = if (truncated) text.take(args.maxChars) else text
        return BuiltinToolResult.success(
            message = "Read ${file.length()} bytes from ${file.name}" +
                    (if (truncated) " (showing first ${args.maxChars} chars)." else "."),
            data = buildJsonObject {
                put("path", file.absolutePath)
                put("bytes", file.length())
                put("truncated", truncated)
                put("content", shown)
            }
        )
    }

    // ------------------------------------------------------------- write_text

    private fun writeText(args: Args): BuiltinToolResult {
        if (args.path.isBlank()) {
            return missingField("path")
        }
        val file = File(args.path)
        file.parentFile?.let { parent ->
            if (!parent.exists()) parent.mkdirs()
        }
        if (args.append) {
            file.appendText(args.content, Charsets.UTF_8)
        } else {
            file.writeText(args.content, Charsets.UTF_8)
        }
        return BuiltinToolResult.success(
            message = "Wrote ${args.content.length} chars to ${file.absolutePath}" +
                    (if (args.append) " (appended)." else "."),
            data = buildJsonObject {
                put("path", file.absolutePath)
                put("bytes", file.length())
                put("appended", args.append)
            },
            hint = "Verify with read_text or list if the result matters downstream."
        )
    }

    // ------------------------------------------------------------------- move

    private fun move(args: Args): BuiltinToolResult {
        val src = requireFile(args.path)
        val dst = requireDestination(args.destination, src)
        val moved = src.renameTo(dst) || run {
            src.copyTo(dst, overwrite = true)
            src.delete()
            true
        }
        return if (moved) BuiltinToolResult.success(
            message = "Moved ${src.name} → ${dst.absolutePath}.",
            data = buildJsonObject { put("destination", dst.absolutePath) }
        ) else BuiltinToolResult.failure(
            code = "MOVE_FAILED",
            message = "Could not move ${src.absolutePath}.",
            hint = "Cross-filesystem moves fall back to copy+delete; check destination is writable."
        )
    }

    private fun copy(args: Args): BuiltinToolResult {
        val src = requireFile(args.path)
        val dst = requireDestination(args.destination, src)
        if (src.isDirectory) {
            src.copyRecursively(dst, overwrite = true)
        } else {
            src.copyTo(dst, overwrite = true)
        }
        return BuiltinToolResult.success(
            message = "Copied ${src.name} → ${dst.absolutePath}.",
            data = buildJsonObject { put("destination", dst.absolutePath) }
        )
    }

    private fun rename(args: Args): BuiltinToolResult {
        val src = requireFile(args.path)
        if (args.newName.isBlank()) return missingField("new_name")
        val dst = File(src.parentFile, args.newName.trim())
        if (dst.exists()) {
            return BuiltinToolResult.failure(
                code = "TARGET_EXISTS",
                message = "A file named '${args.newName}' already exists in this folder.",
                hint = "Pick a different name or delete the target first."
            )
        }
        val ok = src.renameTo(dst)
        return if (ok) BuiltinToolResult.success(
            message = "Renamed to ${dst.name}.",
            data = buildJsonObject { put("destination", dst.absolutePath) }
        ) else BuiltinToolResult.failure(
            code = "RENAME_FAILED",
            message = "renameTo returned false for ${src.name} → ${args.newName}.",
            hint = "The name may contain illegal characters or the filesystem may refuse the operation."
        )
    }

    private fun delete(args: Args): BuiltinToolResult {
        val target = File(args.path)
        if (!target.exists()) {
            return BuiltinToolResult.failure(
                code = "NOT_FOUND",
                message = "Path does not exist: ${args.path}",
                hint = "List the parent directory to confirm the exact name."
            )
        }
        if (target.absolutePath == "/" || target.canonicalFile == Environment.getExternalStorageDirectory()?.canonicalFile) {
            return BuiltinToolResult.failure(
                code = "DANGEROUS_PATH_REFUSED",
                message = "Refusing to delete a filesystem root.",
                hint = "Delete specific files or subfolders instead."
            )
        }
        if (target.isDirectory && !args.recursive) {
            return BuiltinToolResult.failure(
                code = "IS_DIRECTORY",
                message = "${args.path} is a directory; pass recursive=true to delete it with contents.",
                hint = "Confirm the contents first with list, then set recursive=true."
            )
        }
        val freed = target.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
        return if (ok) BuiltinToolResult.success(
            message = "Deleted ${target.name}" +
                    (if (freed > 0) " (freed ${humanBytes(freed)})." else "."),
            data = buildJsonObject { put("bytes_freed", freed) }
        ) else BuiltinToolResult.failure(
            code = "DELETE_FAILED",
            message = "Could not delete ${args.path}.",
            hint = "Another process may hold the file open; retry or inform the user."
        )
    }

    private fun mkdir(args: Args): BuiltinToolResult {
        if (args.path.isBlank()) return missingField("path")
        val dir = File(args.path)
        val ok = dir.mkdirs() || dir.isDirectory
        return if (ok) BuiltinToolResult.success(
            message = "Directory ready: ${dir.absolutePath}.",
            data = buildJsonObject { put("path", dir.absolutePath) }
        ) else BuiltinToolResult.failure(
            code = "MKDIR_FAILED",
            message = "Could not create ${args.path}.",
            hint = "A file may exist at that path, or the parent may be read-only."
        )
    }

    // ----------------------------------------------------------------- search

    private fun search(args: Args): BuiltinToolResult {
        val root = requireDir(args.path)
        val pattern = if (args.pattern.isBlank()) "*" else args.pattern
        val regex = globToRegex(pattern)
        val matches = mutableListOf<String>()
        root.walkTopDown()
            .filter { !it.isHidden }
            .forEach { f ->
                if (matches.size >= args.limit) return@forEach
                if (f.name != root.name && regex.matches(f.name)) matches += f.absolutePath
            }
        return BuiltinToolResult.success(
            message = "Found ${matches.size} match(es) for '$pattern' under ${args.path}.",
            data = buildJsonObject {
                put("count", matches.size)
                put("matches", buildJsonArray { matches.forEach { add(JsonPrimitive(it)) } })
            },
            hint = "Increase limit or narrow the folder for large result sets."
        )
    }

    // ------------------------------------------------------------ sort_folder

    private fun sortFolder(args: Args): BuiltinToolResult {
        val dir = requireDir(args.path)
        val mode = args.mode.ifBlank { "type" }
        val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
        if (files.isEmpty()) {
            return BuiltinToolResult.failure(
                code = "EMPTY_FOLDER",
                message = "No files to organize in ${args.path}.",
                hint = "This tool sorts loose files; subfolders are left untouched."
            )
        }
        var movedCount = 0
        val groups = LinkedHashMap<String, Int>()
        for (file in files) {
            val bucket = when (mode) {
                "date" -> {
                    val stamp = SimpleDateFormat("yyyy-MM", Locale.US).format(Date(file.lastModified()))
                    if (args.dryRun) stamp else File(dir, stamp).let { it.mkdirs(); it }
                    stamp
                }

                "name" -> file.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"

                "samsung" -> samsungBucketOf(file.name, file.extension.lowercase())

                else -> categoryOf(file.extension.lowercase())
            }
            if (args.dryRun) {
                groups[bucket] = (groups[bucket] ?: 0) + 1
                movedCount++
                continue
            }
            val targetDir = File(dir, bucket).apply { mkdirs() }
            val target = File(targetDir, file.name)
            if (target.exists()) {
                // 同名冲突：加时间戳后缀，绝不覆盖
                val safe = File(targetDir, "${file.nameWithoutExtension}_${System.currentTimeMillis() % 100000}.${file.extension}")
                file.renameTo(safe)
            } else {
                file.renameTo(target)
            }
            groups[bucket] = (groups[bucket] ?: 0) + 1
            movedCount++
        }
        return BuiltinToolResult.success(
            message = (if (args.dryRun) "Dry run: " else "Organized ") +
                    "$movedCount file(s) into ${groups.size} folder(s) by $mode.",
            data = buildJsonObject {
                put("mode", mode)
                put("dry_run", args.dryRun)
                put("files_organized", movedCount)
                put("groups", buildJsonArray {
                    groups.forEach { (bucket, count) ->
                        add(buildJsonObject { put("folder", bucket); put("files", count) })
                    }
                })
            },
            hint = "Run with dry_run=true first to preview the plan when the outcome is user-visible."
        )
    }

    private fun categoryOf(ext: String): String = when (ext) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" -> "Images"
        "mp4", "mkv", "avi", "mov", "3gp", "webm" -> "Videos"
        "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus" -> "Audio"
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv", "epub" -> "Documents"
        "apk", "apks", "xapk" -> "Apps"
        "zip", "rar", "7z", "tar", "gz" -> "Archives"
        else -> "Other"
    }

    /**
     * v1.8.0 Samsung/One UI 档案模式：截图、相机媒体、录音按三星命名习惯分桶，
     * 其余回落到通用类型分桶。
     */
    private fun samsungBucketOf(name: String, ext: String): String = when {
        name.startsWith("Screenshot_", ignoreCase = true) -> "Screenshots"
        name.startsWith("IMG_") && ext in CAMERA_IMAGE_EXT -> "Camera"
        name.startsWith("VID_") && ext in CAMERA_VIDEO_EXT -> "Camera"
        (name.startsWith("REC", ignoreCase = true) || name.startsWith("Voice", ignoreCase = true)) &&
                ext in RECORDING_EXT -> "Recordings"
        else -> categoryOf(ext)
    }

    // ------------------------------------------------------------ disk_usage

    private fun diskUsage(args: Args): BuiltinToolResult {
        if (args.path.isNotBlank()) {
            val target = File(args.path)
            if (!target.exists()) {
                return BuiltinToolResult.failure(
                    code = "NOT_FOUND",
                    message = "Path does not exist: ${args.path}",
                    hint = "List the parent directory first."
                )
            }
            var files = 0L
            var dirs = 0L
            var bytes = 0L
            target.walkTopDown().forEach {
                if (it.isDirectory) dirs++ else { files++; bytes += it.length() }
            }
            return BuiltinToolResult.success(
                message = "${target.name}: ${humanBytes(bytes)} across $files file(s), $dirs dir(s).",
                data = buildJsonObject {
                    put("bytes", bytes); put("files", files); put("directories", dirs)
                }
            )
        }
        val stat = StatFs(Environment.getDataDirectory().path)
        val externalStat = try {
            StatFs(Environment.getExternalStorageDirectory().path)
        } catch (t: Throwable) {
            null
        }
        return BuiltinToolResult.success(
            message = "Storage: ${humanBytes(stat.availableBytes)} free of ${humanBytes(stat.totalBytes)}.",
            data = buildJsonObject {
                put("internal_total_bytes", stat.totalBytes)
                put("internal_free_bytes", stat.availableBytes)
                externalStat?.let {
                    put("external_total_bytes", it.totalBytes)
                    put("external_free_bytes", it.availableBytes)
                }
            }
        )
    }

    // --------------------------------------------------------------- zip/unzip

    private fun zip(args: Args): BuiltinToolResult {
        if (args.paths.isEmpty()) return missingField("paths")
        val output = File(args.output.ifBlank {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath +
                    "/zafiro_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.zip"
        })
        output.parentFile?.mkdirs()
        var entryCount = 0
        ZipOutputStream(FileOutputStream(output)).use { zos ->
            args.paths.forEach { rawPath ->
                val src = File(rawPath)
                if (!src.exists()) return@forEach
                if (src.isFile) {
                    putZipEntry(zos, src, src.name)
                    entryCount++
                } else {
                    src.walkTopDown().filter { it.isFile }.forEach { f ->
                        putZipEntry(zos, f, f.relativeTo(src).path)
                        entryCount++
                    }
                }
            }
        }
        return BuiltinToolResult.success(
            message = "Created ${output.name} (${humanBytes(output.length())}, $entryCount file(s)).",
            data = buildJsonObject {
                put("output", output.absolutePath)
                put("bytes", output.length())
                put("files", entryCount)
            },
            hint = "Perfect for automated backups: zip user folders, then move the archive to safe storage."
        )
    }

    private fun putZipEntry(zos: ZipOutputStream, file: File, entryName: String) {
        zos.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zos, 64 * 1024) }
        zos.closeEntry()
    }

    private fun unzip(args: Args): BuiltinToolResult {
        val zipFile = requireFile(args.path)
        val dest = File(args.destination.ifBlank {
            File(zipFile.parentFile, zipFile.nameWithoutExtension).absolutePath
        })
        dest.mkdirs()
        var entryCount = 0
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(dest, entry.name)
                // Zip-slip 防护
                if (!outFile.canonicalPath.startsWith(dest.canonicalPath + File.separator) &&
                    outFile.canonicalPath != dest.canonicalPath
                ) {
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zis.copyTo(it, 64 * 1024) }
                    entryCount++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return BuiltinToolResult.success(
            message = "Extracted $entryCount file(s) to ${dest.absolutePath}.",
            data = buildJsonObject {
                put("destination", dest.absolutePath)
                put("files", entryCount)
            }
        )
    }

    // -------------------------------------------------------------- helpers

    private fun requireDir(path: String): File {
        if (path.isBlank()) throw IllegalArgumentException("'path' is required.")
        val f = File(path)
        if (!f.isDirectory) throw IllegalArgumentException("Not a directory: $path")
        return f
    }

    private fun requireFile(path: String): File {
        if (path.isBlank()) throw IllegalArgumentException("'path' is required.")
        val f = File(path)
        if (!f.isFile) throw IllegalArgumentException("Not a file: $path")
        return f
    }

    private fun requireDestination(destination: String, src: File): File {
        if (destination.isBlank()) throw IllegalArgumentException("'destination' is required.")
        var dst = File(destination)
        if (dst.isDirectory) dst = File(dst, src.name)
        dst.parentFile?.mkdirs()
        return dst
    }

    private fun missingField(field: String): BuiltinToolResult = BuiltinToolResult.failure(
        code = "MISSING_REQUIRED_FIELD",
        message = "Field '$field' is required for this action.",
        fieldErrors = mapOf(field to "required"),
    )

    private fun globToRegex(glob: String): Regex {
        val sb = StringBuilder()
        glob.forEach { ch ->
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '[', ']', '{', '}', '\\' ->
                    sb.append('\\').append(ch)

                else -> sb.append(ch)
            }
        }
        return Regex(sb.toString(), RegexOption.IGNORE_CASE)
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1 shl 30 -> "%.1f GB".format(Locale.US, bytes / 1073741824.0)
        bytes >= 1 shl 20 -> "%.1f MB".format(Locale.US, bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1f KB".format(Locale.US, bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun isPermissionError(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase(Locale.US)
        return msg.contains("permission") || msg.contains("denied") ||
                msg.contains("eacces") || msg.contains("read-only")
    }

    private fun parseArguments(argumentsJson: String): Args {
        val obj = if (argumentsJson.isBlank()) JsonObject(emptyMap())
        else try {
            Json.parseToJsonElement(argumentsJson) as? JsonObject ?: JsonObject(emptyMap())
        } catch (t: Throwable) {
            throw IllegalArgumentException("argumentsJson is not valid JSON.")
        }
        fun str(key: String): String = obj[key]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val pathsArray = obj["paths"] as? kotlinx.serialization.json.JsonArray
        return Args(
            action = str("action"),
            path = str("path"),
            destination = str("destination"),
            newName = str("new_name"),
            content = obj["content"]?.jsonPrimitive?.contentOrNull ?: "",
            append = str("append").toBooleanStrictOrNull() ?: false,
            recursive = str("recursive").toBooleanStrictOrNull() ?: false,
            pattern = str("pattern"),
            mode = str("mode"),
            dryRun = str("dry_run").toBooleanStrictOrNull() ?: false,
            limit = str("limit").toIntOrNull()?.coerceIn(1, 2000) ?: 200,
            maxChars = str("max_chars").toIntOrNull()?.coerceIn(500, 200_000) ?: 60_000,
            paths = pathsArray?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            output = str("output"),
        )
    }

    private data class Args(
        val action: String,
        val path: String,
        val destination: String,
        val newName: String,
        val content: String,
        val append: Boolean,
        val recursive: Boolean,
        val pattern: String,
        val mode: String,
        val dryRun: Boolean,
        val limit: Int,
        val maxChars: Int,
        val paths: List<String>,
        val output: String,
    )

    companion object {
        private const val LOG_TAG = "niki914_nexus_FileManagerBuiltin"
        private const val MAX_READ_BYTES = 10L * 1024 * 1024

        // v1.8.0 samsung 档案模式的扩展名集合
        private val CAMERA_IMAGE_EXT = setOf("jpg", "jpeg", "png", "heic", "dng")
        private val CAMERA_VIDEO_EXT = setOf("mp4", "3gp", "webm")
        private val RECORDING_EXT = setOf("m4a", "3ga", "amr", "mp3", "wav", "ogg")

        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["list", "read_text", "write_text", "move", "copy", "rename", "delete",
               "mkdir", "search", "sort_folder", "disk_usage", "zip", "unzip"],
      "description": "Which file operation to run."
    },
    "path": {
      "type": "string",
      "description": "Absolute path: the file/folder to operate on (zip file for unzip)."
    },
    "destination": {
      "type": "string",
      "description": "move/copy/unzip: absolute destination path (or folder)."
    },
    "new_name": {
      "type": "string",
      "description": "rename only: the new file name (no path)."
    },
    "content": {
      "type": "string",
      "description": "write_text only: text to write (UTF-8)."
    },
    "append": {
      "type": "boolean",
      "description": "write_text only: append instead of overwrite. Default false."
    },
    "recursive": {
      "type": "boolean",
      "description": "delete only: required true to delete non-empty directories."
    },
    "pattern": {
      "type": "string",
      "description": "search only: glob pattern like *.pdf or report*."
    },
    "mode": {
      "type": "string",
      "enum": ["type", "date", "name", "samsung"],
      "description": "sort_folder only: organize by file type (default), by year-month, by first letter, or by Samsung media naming (Screenshots/Camera/Recordings-aware)."
    },
    "dry_run": {
      "type": "boolean",
      "description": "sort_folder only: preview the plan without moving files."
    },
    "paths": {
      "type": "array",
      "items": {"type": "string"},
      "description": "zip only: absolute paths of files/folders to archive."
    },
    "output": {
      "type": "string",
      "description": "zip only: absolute output .zip path. Blank = auto-named backup in Downloads."
    },
    "limit": {
      "type": "integer",
      "description": "list/search: max entries returned (default 200)."
    },
    "max_chars": {
      "type": "integer",
      "description": "read_text: max characters returned (default 60000)."
    }
  },
  "required": ["action"]
}
        """.trimIndent()

        /** 共享存储「所有文件访问」是否已授予（私有目录不受影响，始终可用）。 */
        fun isSharedStorageAccessible(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                        PackageManager.PERMISSION_GRANTED
            }
        }
    }
}
