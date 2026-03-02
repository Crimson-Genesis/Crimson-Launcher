package app.olauncher.helper

import app.olauncher.data.TodoItem

sealed class LogEvent {
    data class TaskAdded(val todo: TodoItem, val snapshot: DailySnapshot) : LogEvent()
    data class TaskDeleted(val todo: TodoItem, val reason: String? = null, val snapshot: DailySnapshot) : LogEvent()
    data class TaskCompleted(val todo: TodoItem, val snapshot: DailySnapshot) : LogEvent()
    data class TaskUncompleted(val todo: TodoItem, val snapshot: DailySnapshot) : LogEvent()
    data class TaskEdited(val old: TodoItem, val updated: TodoItem, val changedFields: List<String>, val snapshot: DailySnapshot) : LogEvent()
    data class DayReset(val summary: DaySummary) : LogEvent()
    object AppOpened : LogEvent()
    data class AppClosed(val uptimeMs: Long) : LogEvent()
    data class DrawerOpened(val source: String, val queryLength: Int, val resultsCount: Int) : LogEvent()
    data class AppLaunched(
        val packageName: String,
        val activity: String?,
        val userHandle: String,
        val renamedLabelUsed: Boolean,
        val isHidden: Boolean
    ) : LogEvent()
    data class HardcoreModeToggled(val newState: Boolean) : LogEvent()
    data class BackupCreated(val bytes: Long) : LogEvent()
    data class BackupFailed(val errorMessage: String) : LogEvent()
    data class RestorePerformed(val restoredCount: Int) : LogEvent()
    data class RestoreFailed(val errorMessage: String) : LogEvent()
    data class LogFolderChanged(val oldUri: String?, val newUri: String?) : LogEvent()
    data class LoggingEnabled(val ts: Long) : LogEvent()
    data class LoggingDisabled(val ts: Long) : LogEvent()
    data class ResetTimeChanged(val oldMinutes: Int, val newMinutes: Int) : LogEvent()
    data class LogWriteFailed(val errorMessage: String, val target: String) : LogEvent()
    data class SettingsChanged(val key: String, val value: Any) : LogEvent()
    
    // Template Events
    data class TemplateAdded(val name: String, val taskCount: Int) : LogEvent()
    data class TemplateApplied(val name: String, val taskCount: Int) : LogEvent()
    data class TemplateDeleted(val name: String) : LogEvent()
}

data class DailySnapshot(
    val completedToday: Int,
    val totalToday: Int,
    val completionRate: Double,
    val dailyTasksCompleted: Int,
    val dailyTasksTotal: Int,
    val timedOverdue: Int,
    val deletedToday: Int,
    val addedToday: Int
)

data class DaySummary(
    val completedToday: Int,
    val totalToday: Int,
    val completionRate: Double,
    val dailyTasksCompleted: Int,
    val dailyTasksTotal: Int,
    val timedOverdue: Int,
    val deletedToday: Int,
    val addedToday: Int,
    val streak: Int
)
