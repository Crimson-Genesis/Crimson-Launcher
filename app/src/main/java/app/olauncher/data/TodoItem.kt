package app.olauncher.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Calendar

@Entity(tableName = "todo_items")
data class TodoItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val task: String,
    var isCompleted: Boolean = false,
    val type: TodoType,
    val dueDate: Long? = null, // For timed todos
    val time: String? = null, // For daily todos
    val daysOfWeek: String? = null, // For daily todos (e.g., "Mon Tue Wed")
    val completedAt: Long? = null,
    val toDate: Long? = null,
    val toTime: String? = null
) {
    fun isOverdue(prefs: Prefs): Boolean {
        if (isCompleted) return false
        val now = System.currentTimeMillis()
        val endAt = TodoDateTimeHelper.getEndAtMillis(this, now, prefs) ?: return false
        return now > endAt
    }

    fun isOvernight(): Boolean = TodoDateTimeHelper.isOvernight(this)
}

enum class TodoType {
    DAILY,
    TIMED,
    TIMELESS
}
