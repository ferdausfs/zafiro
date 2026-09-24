package com.niki914.zafiro.chat.agentic.buildin.impl

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import com.niki914.logging.Logger
import com.niki914.xposed.api.util.ContextProvider
import com.niki914.zafiro.chat.agentic.buildin.BuiltinTool
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolRequest
import com.niki914.zafiro.chat.agentic.buildin.BuiltinToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * system_data 工具（v1.7.0 Capability 1: Direct System API Integration）。
 *
 * 绕过 UI 自动化，直接调用 Android System API 读取高频数据：
 *  - contacts：通讯录（READ_CONTACTS）
 *  - calendar：日历事件（READ_CALENDAR）
 *  - battery：电池状态（免权限）
 *  - device：设备信息（免权限）
 *  - settings：系统设置项（免权限，只读）
 *  - network：网络状态（ACCESS_NETWORK_STATE，普通权限）
 *
 * 相比 screen_operation 的 UI 遍历，API 读取是 100% 可靠、毫秒级完成。
 */
class SystemDataBuiltin : BuiltinTool() {

    override val name: String = "system_data"

    override val description: String = """
Query device data directly through Android System APIs — 100% reliable and instant,
no UI automation needed. Actions:
- contacts: search the address book (name + phone number).
- calendar: list upcoming calendar events.
- battery: level, charging state, health, temperature.
- device: model, Android version, screen, storage, RAM.
- settings: brightness, screen timeout, airplane mode, auto-rotate, etc.
- network: connectivity type (wifi/cellular), metered state.
ALWAYS prefer this tool over screen scraping for these data types.
    """.trimIndent()

    override val defaultEnabled: Boolean = true

    override val inputSchemaJson: String? = SCHEMA

    override suspend fun invoke(request: BuiltinToolRequest): BuiltinToolResult =
        withContext(Dispatchers.IO) {
            val args = parseArguments(request.argumentsJson)
            val context = try {
                ContextProvider.await().applicationContext
            } catch (t: Throwable) {
                null
            } ?: return@withContext BuiltinToolResult.failure(
                code = "CONTEXT_UNAVAILABLE",
                message = "Application context is not initialized yet.",
                hint = "Retry shortly; the host app may still be starting."
            )

            when (args.action) {
                ACTION_CONTACTS -> readContacts(context, args.query, args.limit)
                ACTION_CALENDAR -> readCalendar(context, args.daysAhead, args.limit)
                ACTION_BATTERY -> readBattery(context)
                ACTION_DEVICE -> readDevice(context)
                ACTION_SETTINGS -> readSettings(context)
                ACTION_NETWORK -> readNetwork(context)
                else -> BuiltinToolResult.failure(
                    code = "UNKNOWN_ACTION",
                    message = "Unknown action '${args.action}'.",
                    hint = "Valid actions: $ACTION_CONTACTS, $ACTION_CALENDAR, $ACTION_BATTERY, " +
                            "$ACTION_DEVICE, $ACTION_SETTINGS, $ACTION_NETWORK."
                )
            }
        }

    // ------------------------------------------------------------- contacts

    private fun readContacts(context: Context, query: String, limit: Int): BuiltinToolResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return permissionDeniedResult(
                permission = "READ_CONTACTS",
                message = "Contacts cannot be read: READ_CONTACTS permission is not granted.",
            )
        }
        val contacts = mutableListOf<JsonObject>()
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        val selection = if (query.isNotBlank())
            "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
        else null
        val selectionArgs = if (query.isNotBlank()) arrayOf("%$query%") else null

        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE ASC LIMIT $limit",
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIdx =
                    cursor.getColumnIndexOrThrow(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                while (cursor.moveToNext() && contacts.size < limit) {
                    val id = cursor.getLong(idIdx)
                    val name = cursor.getString(nameIdx).orEmpty()
                    val hasPhone = cursor.getInt(hasPhoneIdx) > 0
                    val numbers = mutableListOf<String>()
                    if (hasPhone) {
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(id.toString()),
                            null,
                        )?.use { phoneCursor ->
                            val numIdx = phoneCursor.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                            )
                            while (phoneCursor.moveToNext() && numbers.size < 3) {
                                phoneCursor.getString(numIdx)?.let(numbers::add)
                            }
                        }
                    }
                    contacts += buildJsonObject {
                        put("name", name)
                        put("phones", numbers.joinToString(" / "))
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "contacts query failed: ${t.message}")
            return BuiltinToolResult.failure(
                code = "QUERY_FAILED",
                message = "Contacts query failed: ${t.message ?: "unknown error"}.",
                hint = "The contacts provider may be locked by the system; retry or inform the user."
            )
        }

        return BuiltinToolResult.success(
            message = "Found ${contacts.size} contact(s)" +
                    if (query.isNotBlank()) " matching \"$query\"." else ".",
            data = buildJsonObject {
                put("count", contacts.size)
                put("contacts", kotlinx.serialization.json.JsonArray(contacts))
            },
            hint = "Data came straight from the ContactsProvider via API — no UI interaction needed."
        )
    }

    // ------------------------------------------------------------- calendar

    private fun readCalendar(context: Context, daysAhead: Int, limit: Int): BuiltinToolResult {
        if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return permissionDeniedResult(
                permission = "READ_CALENDAR",
                message = "Calendar cannot be read: READ_CALENDAR permission is not granted.",
            )
        }
        val events = mutableListOf<JsonObject>()
        val now = System.currentTimeMillis()
        val end = now + daysAhead.toLong() * 24 * 60 * 60 * 1000
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        try {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC LIMIT $limit",
            )?.use { cursor ->
                while (cursor.moveToNext() && events.size < limit) {
                    val begin = cursor.getLongOrNull(
                        cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                    ) ?: continue
                    val endMs = cursor.getLongOrNull(
                        cursor.getColumnIndex(CalendarContract.Instances.END)
                    ) ?: begin
                    events += buildJsonObject {
                        put("title", cursor.getStringOrNull(cursor.getColumnIndex(
                            CalendarContract.Instances.TITLE)).orEmpty())
                        put("start", fmt.format(Date(begin)))
                        put("end", fmt.format(Date(endMs)))
                        put("location", cursor.getStringOrNull(cursor.getColumnIndex(
                            CalendarContract.Instances.EVENT_LOCATION)).orEmpty())
                        put("calendar", cursor.getStringOrNull(cursor.getColumnIndex(
                            CalendarContract.Instances.CALENDAR_DISPLAY_NAME)).orEmpty())
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.w(LOG_TAG, "calendar query failed: ${t.message}")
            return BuiltinToolResult.failure(
                code = "QUERY_FAILED",
                message = "Calendar query failed: ${t.message ?: "unknown error"}.",
                hint = "Retry later or inform the user."
            )
        }

        return BuiltinToolResult.success(
            message = "Found ${events.size} event(s) in the next $daysAhead day(s).",
            data = buildJsonObject {
                put("count", events.size)
                put("events", kotlinx.serialization.json.JsonArray(events))
            },
            hint = "Times are device-local, read directly from the CalendarProvider."
        )
    }

    // -------------------------------------------------------------- battery

    private fun readBattery(context: Context): BuiltinToolResult {
        val intent = context.registerReceiver(null, android.content.IntentFilter(
            android.content.Intent.ACTION_BATTERY_CHANGED
        )) ?: return BuiltinToolResult.failure(
            code = "BATTERY_UNAVAILABLE",
            message = "Battery state broadcast is unavailable.",
            hint = "Retry once; this sticky broadcast is almost always present."
        )
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val health = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = plugged != 0
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "battery"
        }
        val healthText = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        return BuiltinToolResult.success(
            message = "Battery: $pct%, $statusText via $source.",
            data = buildJsonObject {
                put("percent", pct)
                put("status", statusText)
                put("power_source", source)
                put("health", healthText)
                if (temp != Int.MIN_VALUE) put("temperature_c", temp / 10.0)
                if (voltage > 0) put("voltage_mv", voltage)
            },
            hint = "Direct BatteryManager read — no permission required."
        )
    }

    // --------------------------------------------------------------- device

    private fun readDevice(context: Context): BuiltinToolResult {
        val metrics = context.resources.displayMetrics
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { info ->
            activityManager?.getMemoryInfo(info)
        }
        val dataDir = Environment.getDataDirectory()
        val internalStat = StatFs(dataDir.path)
        val external = Environment.getExternalStorageDirectory()
        val externalStat = try { StatFs(external.path) } catch (t: Throwable) { null }
        fun bytesToGb(bytes: Long): Double = (bytes / (1024.0 * 1024 * 1024) * 10).toInt() / 10.0

        return BuiltinToolResult.success(
            message = "Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}.",
            data = buildJsonObject {
                put("manufacturer", Build.MANUFACTURER)
                put("model", Build.MODEL)
                put("brand", Build.BRAND)
                put("android_version", Build.VERSION.RELEASE)
                put("sdk_int", Build.VERSION.SDK_INT)
                put("screen_px", "${metrics.widthPixels}x${metrics.heightPixels}")
                put("density_dpi", metrics.densityDpi)
                put("ram_total_gb", bytesToGb(memInfo.totalMem))
                put("ram_available_gb", bytesToGb(memInfo.availMem))
                put(
                    "internal_free_gb",
                    bytesToGb(internalStat.availableBytes)
                )
                externalStat?.let {
                    put("external_free_gb", bytesToGb(it.availableBytes))
                }
            },
            hint = "Direct Build/StatFs/ActivityManager reads — no permission required."
        )
    }

    // ------------------------------------------------------------- settings

    private fun readSettings(context: Context): BuiltinToolResult {
        val secure = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1
        )
        val brightnessMode = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1
        )
        val screenTimeout = Settings.System.getInt(
            context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1
        )
        val autoRotate = Settings.System.getInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, -1
        )
        val airplane = try {
            Settings.Global.getInt(
                context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
            )
        } catch (t: Throwable) {
            0
        }
        val durationFmt = { ms: Int ->
            if (ms <= 0) "never" else "${ms / 1000}s"
        }
        return BuiltinToolResult.success(
            message = "System settings read (API, read-only).",
            data = buildJsonObject {
                put("brightness", if (secure >= 0) secure else -1)
                put("brightness_mode", if (brightnessMode == 1) "auto" else "manual")
                put("screen_off_timeout", durationFmt(screenTimeout))
                put("auto_rotate", autoRotate == 1)
                put("airplane_mode", airplane == 1)
            },
            hint = "Curated read-only snapshot of common system settings."
        )
    }

    // -------------------------------------------------------------- network

    private fun readNetwork(context: Context): BuiltinToolResult {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return BuiltinToolResult.failure(
                code = "NETWORK_UNAVAILABLE",
                message = "ConnectivityManager is unavailable on this device.",
                hint = "Report to the user that network state cannot be determined."
            )
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val connected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return BuiltinToolResult.success(
            message = "Network: ${if (connected) "connected" else "not validated"} via $type.",
            data = buildJsonObject {
                put("connected", connected)
                put("type", type)
                put("metered", caps?.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
            },
            hint = "Direct ConnectivityManager read — no location permission involved."
        )
    }

    // -------------------------------------------------------------- helpers

    private fun permissionDeniedResult(permission: String, message: String): BuiltinToolResult {
        return BuiltinToolResult.failure(
            code = "PERMISSION_NOT_GRANTED",
            message = message,
            hint = "Tell the user to grant the $permission permission to Zafiro in system settings, " +
                    "then retry. Do NOT fall back to screen-scraping contacts/calendar apps."
        )
    }

    private fun parseArguments(argumentsJson: String): SystemDataArguments {
        val obj = if (argumentsJson.isBlank()) JsonObject(emptyMap())
        else try {
            Json.parseToJsonElement(argumentsJson) as? JsonObject
                ?: JsonObject(emptyMap())
        } catch (t: Throwable) {
            JsonObject(emptyMap())
        }
        val action = obj.string("action").ifBlank { "" }
        return SystemDataArguments(
            action = action,
            query = obj.string("query").trim(),
            limit = obj.string("limit").toIntOrNull()?.coerceIn(1, 100) ?: 20,
            daysAhead = obj.string("days_ahead").toIntOrNull()?.coerceIn(1, 365) ?: 7,
        )
    }

    private fun JsonObject.string(key: String): String {
        return this[key]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? {
        return if (index >= 0) getString(index) else null
    }

    private fun android.database.Cursor.getLongOrNull(index: Int): Long? {
        return if (index >= 0 && !isNull(index)) getLong(index) else null
    }

    private data class SystemDataArguments(
        val action: String,
        val query: String,
        val limit: Int,
        val daysAhead: Int,
    )

    private companion object {
        private const val LOG_TAG = "niki914_nexus_SystemDataBuiltin"

        private const val ACTION_CONTACTS = "contacts"
        private const val ACTION_CALENDAR = "calendar"
        private const val ACTION_BATTERY = "battery"
        private const val ACTION_DEVICE = "device"
        private const val ACTION_SETTINGS = "settings"
        private const val ACTION_NETWORK = "network"

        private val SCHEMA = """
{
  "type": "object",
  "properties": {
    "action": {
      "type": "string",
      "enum": ["contacts", "calendar", "battery", "device", "settings", "network"],
      "description": "Which system data to query."
    },
    "query": {
      "type": "string",
      "description": "contacts only: filter by contact name substring. Blank = all."
    },
    "limit": {
      "type": "integer",
      "description": "contacts/calendar only: max results (1-100, default 20)."
    },
    "days_ahead": {
      "type": "integer",
      "description": "calendar only: how many days ahead to scan (1-365, default 7)."
    }
  },
  "required": ["action"]
}
        """.trimIndent()
    }
}
