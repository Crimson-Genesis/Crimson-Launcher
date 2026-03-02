package app.olauncher.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.text.DateFormat
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
        
        // Use toDate/toTime for overdue check if they exist
        val effectiveDueDate = toDate ?: dueDate
        val effectiveTime = toTime ?: time

        return when (type) {
            TodoType.TIMED -> {
                // If we have a time range on a single date, it's overdue only after the toTime
                effectiveDueDate != null && effectiveDueDate < now
            }
            TodoType.DAILY -> {
                if (effectiveTime == null) return false
                try {
                    val df = DateFormat.getTimeInstance(DateFormat.SHORT)
                    val parsedTime = df.parse(effectiveTime) ?: return false
                    val itemCal = Calendar.getInstance().apply { time = parsedTime }
                    
                    val targetCal = Calendar.getInstance().apply { timeInMillis = now }
                    val resetMinutes = prefs.resetTimeMinutes
                    val currentMinutes = targetCal.get(Calendar.HOUR_OF_DAY) * 60 + targetCal.get(Calendar.MINUTE)
                    if (currentMinutes < resetMinutes) {
                        targetCal.add(Calendar.DAY_OF_YEAR, -1)
                    }
                    
                    targetCal.set(Calendar.HOUR_OF_DAY, itemCal.get(Calendar.HOUR_OF_DAY))
                    targetCal.set(Calendar.MINUTE, itemCal.get(Calendar.MINUTE))
                    targetCal.set(Calendar.SECOND, 0)
                    targetCal.set(Calendar.MILLISECOND, 0)
                    
                    now > targetCal.timeInMillis
                } catch (e: Exception) {
                    false
                }
            }
            TodoType.TIMELESS -> false
        }
    }
}

enum class TodoType {
    DAILY,
    TIMED,
    TIMELESS
}
