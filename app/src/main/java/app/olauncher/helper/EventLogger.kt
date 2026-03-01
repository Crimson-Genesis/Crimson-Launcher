package app.olauncher.helper

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.olauncher.BuildConfig
import app.olauncher.MainActivity
import app.olauncher.data.Prefs
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

    fun log(context: Context, event: LogEvent) {
        val eventName = getEventName(event)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Event triggered: $eventName")
        }

        val prefs = Prefs(context)
        if (!prefs.isLoggingEnabled) return
        
        val appContext = context.applicationContext
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val json = buildJsonObject(appContext, event, now)
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
                    writeToInternal(appContext, dateStr, logLine)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log event: $eventName", e)
            }
        }
    }

    private fun buildJsonObject(context: Context, event: LogEvent, now: Long): JSONObject {
        val date = Date(now)
        val cal = Calendar.getInstance()
        cal.time = date
        
        val json = JSONObject().apply {
            put("ts", now)
            put("ts_human", isoFormat.format(date))
            put("event", getEventName(event))
            put("session_id", MainActivity.sessionId)
            put("app_version", BuildConfig.VERSION_NAME)
            put("device_id", getDeviceId(context))
        }

        when (event) {
            is LogEvent.TaskAdded -> {
                json.put("todo", todoToJson(event.todo))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskDeleted -> {
                json.put("todo", todoToJson(event.todo))
                event.reason?.let { json.put("reason", it) }
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskCompleted -> {
                json.put("todo", todoToJson(event.todo))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskUncompleted -> {
                json.put("todo", todoToJson(event.todo))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.TaskEdited -> {
                json.put("old_todo", todoToJson(event.old))
                json.put("todo", todoToJson(event.updated))
                json.put("changed_fields", JSONArray(event.changedFields))
                json.put("daily_stats_snapshot", snapshotToJson(event.snapshot))
            }
            is LogEvent.DayReset -> {
                json.put("day_summary", summaryToJson(event.summary))
            }
            is LogEvent.AppOpened -> {}
            is LogEvent.BackupCreated -> {
                json.put("bytes", event.bytes)
            }
            is LogEvent.RestorePerformed -> {
                json.put("restored_count", event.restoredCount)
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
        is LogEvent.BackupCreated -> "backup_created"
        is LogEvent.RestorePerformed -> "restore_performed"
        is LogEvent.LogFolderChanged -> "log_folder_changed"
        is LogEvent.LoggingEnabled -> "logging_enabled"
        is LogEvent.LoggingDisabled -> "logging_disabled"
        is LogEvent.ResetTimeChanged -> "reset_time_changed"
        is LogEvent.TemplateAdded -> "template_added"
        is LogEvent.TemplateApplied -> "template_applied"
        is LogEvent.TemplateDeleted -> "template_deleted"
    }

    private fun todoToJson(todo: TodoItem): JSONObject = JSONObject().apply {
        put("id", todo.id)
        put("task", todo.task)
        put("type", todo.type.name)
        put("days_of_week", todo.daysOfWeek)
        put("time", todo.time)
        put("due_date", todo.dueDate)
        todo.dueDate?.let {
            put("due_date_human", isoFormat.format(Date(it)))
        }
        put("is_completed", todo.isCompleted)
        put("completed_at", todo.completedAt)
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

    private fun writeToInternal(context: Context, dateStr: String, content: String) {
        try {
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "crimson_log_$dateStr.jsonl")
            FileOutputStream(file, true).use { it.write(content.toByteArray()) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write to internal storage", e)
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
