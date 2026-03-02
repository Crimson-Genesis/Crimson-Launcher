package app.olauncher.helper

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.olauncher.BuildConfig
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.data.TodoDateTimeHelper
import app.olauncher.data.TodoItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

object EventLogger {
    private const val TAG = "EventLogger"
    private const val LOG_DIR = "progress_logs"
    private val scope = CoroutineScope(Dispatchers.IO)

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val localIsoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    fun log(context: Context, event: LogEvent) {
        val eventName = getEventName(event)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Event triggered: $eventName")
        }

        val prefs = Prefs(context)
        if (!prefs.isLoggingEnabled && event !is LogEvent.LoggingEnabled) return
        
        val appContext = context.applicationContext
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val json = buildJsonObject(appContext, event, now, prefs)
                val logLine = json.toString() + "\n"
                
                val dateStr = fileFormat.format(Date(now))
                val folderUri = prefs.logFolderUri
                
                var success = false
                if (!folderUri.isNullOrEmpty()) {
                    success = writeToSaf(appContext, folderUri, dateStr, logLine)
                    if (!success) {
                        Log.w(TAG, "SAF logging failed for $eventName, falling back to internal storage.")
                    }
                }
                
                if (!success) {
                    success = writeToInternal(appContext, dateStr, logLine)
                }

                if (!success && event !is LogEvent.LogWriteFailed) {
                    // Try to log failure itself (though likely to fail if storage is full)
                    log(appContext, LogEvent.LogWriteFailed("Both SAF and internal failed", if (folderUri.isNullOrEmpty()) "internal" else "saf/internal"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log event: $eventName", e)
            }
        }
    }

    private fun buildJsonObject(context: Context, event: LogEvent, now: Long, prefs: Prefs): JSONObject {
        val date = Date(now)
        val cal = Calendar.getInstance()
        cal.time = date
        
        val json = JSONObject().apply {
            put("ts", now)
            put("ts_human", isoFormat.format(date))
            put("ts_local_human", localIsoFormat.format(date))
            put("event", getEventName(event))
            put("session_id", MainActivity.sessionId)
            put("app_version", BuildConfig.VERSION_NAME)
            put("device_id", getDeviceId(context))
            put("reset_time_minutes", prefs.resetTimeMinutes)
            put("hardcore_mode", prefs.hardcoreMode)
        }

        if (event is LogEvent.AppOpened || event is LogEvent.AppClosed) {
            json.put("app_label", context.getString(R.string.app_name))
        }

        when (event) {
            is LogEvent.TaskAdded -> {
                json.put("todo", todoToJson(event.todo, prefs))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskDeleted -> {
                json.put("todo", todoToJson(event.todo, prefs))
                event.reason?.let { json.put("reason", it) }
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskCompleted -> {
                json.put("todo", todoToJson(event.todo, prefs))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskUncompleted -> {
                json.put("todo", todoToJson(event.todo, prefs))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskEdited -> {
                json.put("old_todo", todoToJson(event.old, prefs))
                json.put("todo", todoToJson(event.updated, prefs))
                json.put("changed_fields", JSONArray(event.changedFields))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.DayReset -> {
                json.put("day_summary", summaryToJson(event.summary))
            }
            is LogEvent.AppOpened -> {}
            is LogEvent.AppClosed -> {
                json.put("uptime_ms", event.uptimeMs)
            }
            is LogEvent.DrawerOpened -> {
                json.put("source", event.source)
                json.put("search_query_length", event.queryLength)
                json.put("results_count", event.resultsCount)
            }
            is LogEvent.AppLaunched -> {
                json.put("package", event.packageName)
                json.put("activity", event.activity)
                json.put("user_handle", event.userHandle)
                json.put("renamed_label_used", event.renamedLabelUsed)
                json.put("is_hidden", event.isHidden)
            }
            is LogEvent.HardcoreModeToggled -> {
                json.put("new_state", if (event.newState) "on" else "off")
            }
            is LogEvent.BackupCreated -> {
                json.put("bytes", event.bytes)
            }
            is LogEvent.BackupFailed -> {
                json.put("error_message", event.errorMessage)
            }
            is LogEvent.RestorePerformed -> {
                json.put("restored_count", event.restoredCount)
            }
            is LogEvent.RestoreFailed -> {
                json.put("error_message", event.errorMessage)
            }
            is LogEvent.LogFolderChanged -> {
                json.put("old_uri", event.oldUri)
                json.put("new_uri", event.newUri)
            }
            is LogEvent.LoggingEnabled -> {
                json.put("enabled_at", event.ts)
            }
            is LogEvent.LoggingDisabled -> {
                json.put("disabled_at", event.ts)
            }
            is LogEvent.ResetTimeChanged -> {
                json.put("old_minutes", event.oldMinutes)
                json.put("new_minutes", event.newMinutes)
            }
            is LogEvent.LogWriteFailed -> {
                json.put("error_message", event.errorMessage)
                json.put("target", event.target)
            }
            is LogEvent.SettingsChanged -> {
                json.put("key", event.key)
                json.put("value", event.value)
            }
            is LogEvent.TemplateAdded -> {
                json.put("name", event.name)
                json.put("task_count", event.taskCount)
            }
            is LogEvent.TemplateApplied -> {
                json.put("name", event.name)
                json.put("task_count", event.taskCount)
            }
            is LogEvent.TemplateDeleted -> {
                json.put("name", event.name)
            }
        }

        val calendarObj = JSONObject().apply {
            put("day_of_week", SimpleDateFormat("EEEE", Locale.US).format(date))
            put("week_of_year", cal.get(Calendar.WEEK_OF_YEAR))
            put("day_of_year", cal.get(Calendar.DAY_OF_YEAR))
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            put("is_weekend", dow == Calendar.SATURDAY || dow == Calendar.SUNDAY)
        }
        json.put("calendar", calendarObj)

        return json
    }

    private fun getEventName(event: LogEvent): String = when (event) {
        is LogEvent.TaskAdded -> "task_added"
        is LogEvent.TaskDeleted -> "task_deleted"
        is LogEvent.TaskCompleted -> "task_completed"
        is LogEvent.TaskUncompleted -> "task_uncompleted"
        is LogEvent.TaskEdited -> "task_edited"
        is LogEvent.DayReset -> "day_reset"
        is LogEvent.AppOpened -> "app_opened"
        is LogEvent.AppClosed -> "app_closed"
        is LogEvent.DrawerOpened -> "drawer_opened"
        is LogEvent.AppLaunched -> "app_launched"
        is LogEvent.HardcoreModeToggled -> "hardcore_mode_toggled"
        is LogEvent.BackupCreated -> "backup_created"
        is LogEvent.BackupFailed -> "backup_failed"
        is LogEvent.RestorePerformed -> "restore_performed"
        is LogEvent.RestoreFailed -> "restore_failed"
        is LogEvent.LogFolderChanged -> "log_folder_changed"
        is LogEvent.LoggingEnabled -> "logging_enabled"
        is LogEvent.LoggingDisabled -> "logging_disabled"
        is LogEvent.ResetTimeChanged -> "reset_time_changed"
        is LogEvent.LogWriteFailed -> "log_write_failed"
        is LogEvent.SettingsChanged -> "settings_changed"
        is LogEvent.TemplateAdded -> "template_added"
        is LogEvent.TemplateApplied -> "template_applied"
        is LogEvent.TemplateDeleted -> "template_deleted"
    }

    private fun todoToJson(todo: TodoItem, prefs: Prefs): JSONObject = JSONObject().apply {
        put("id", todo.id)
        put("task", todo.task)
        put("type", todo.type.name)
        put("days_of_week", todo.daysOfWeek)
        put("time", todo.time)
        put("due_date", todo.dueDate)
        todo.dueDate?.let {
            put("due_date_human", isoFormat.format(Date(it)))
        }
        put("to_time", todo.toTime)
        put("to_date", todo.toDate)
        todo.toDate?.let {
            put("to_date_human", isoFormat.format(Date(it)))
        }
        put("is_completed", todo.isCompleted)
        put("completed_at", todo.completedAt)
        
        // Overnight/Range info
        put("is_overnight", todo.isOvernight())
        val startAt = TodoDateTimeHelper.getStartAtMillis(todo, prefs = prefs)
        val endAt = TodoDateTimeHelper.getEndAtMillis(todo, prefs = prefs)
        put("computed_start_ts", startAt)
        put("computed_end_ts", endAt)
        startAt?.let { put("computed_start_human", isoFormat.format(Date(it))) }
        endAt?.let { put("computed_end_human", isoFormat.format(Date(it))) }
    }

    private fun snapshotToJson(s: DailySnapshot): JSONObject = JSONObject().apply {
        put("completed_today", s.completedToday)
        put("total_today", s.totalToday)
        put("completion_rate", s.completionRate)
        put("daily_tasks_completed", s.dailyTasksCompleted)
        put("daily_tasks_total", s.dailyTasksTotal)
        put("timed_overdue", s.timedOverdue)
        put("deleted_today", s.deletedToday)
        put("added_today", s.addedToday)
    }

    private fun summaryToJson(s: DaySummary): JSONObject = JSONObject().apply {
        put("completed_today", s.completedToday)
        put("total_today", s.totalToday)
        put("completion_rate", s.completionRate)
        put("daily_tasks_completed", s.dailyTasksCompleted)
        put("daily_tasks_total", s.dailyTasksTotal)
        put("timed_overdue", s.timedOverdue)
        put("deleted_today", s.deletedToday)
        put("added_today", s.addedToday)
        put("streak", s.streak)
    }

    private fun getDeviceId(context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return try {
            val bytes = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
            bytes.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun writeToInternal(context: Context, dateStr: String, content: String): Boolean {
        return try {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "crimson_log_$dateStr.jsonl")
            FileOutputStream(file, true).use { it.write(content.toByteArray()) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to internal storage", e)
            false
        }
    }

    private fun writeToSaf(context: Context, treeUri: String, dateStr: String, content: String): Boolean {
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return false
            if (!tree.exists() || !tree.canWrite()) return false

            val fileName = "crimson_log_$dateStr.jsonl"
            var file = tree.findFile(fileName)
            if (file == null) {
                file = tree.createFile("application/x-jsonlines", fileName)
            }
            file?.let {
                context.contentResolver.openOutputStream(it.uri, "wa")?.use { out ->
                    out.write(content.toByteArray())
                    true
                } ?: false
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to SAF", e)
            false
        }
    }
}
