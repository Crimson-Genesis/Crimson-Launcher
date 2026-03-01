package app.olauncher.data

import java.text.DateFormat
import java.util.Calendar

object TodoSorter {
    fun sortUpcoming(items: List<TodoItem>): List<TodoItem> {
        val (timeless, timed) = items.partition { it.type == TodoType.TIMELESS }
        return timeless.sortedBy { it.id } + timed.sortedWith(compareBy<TodoItem> { it.dueDate ?: Long.MAX_VALUE }.thenBy { it.id })
    }

    fun sortCompleted(items: List<TodoItem>): List<TodoItem> {
        return items.sortedWith(compareByDescending<TodoItem> { it.completedAt ?: 0L }.thenByDescending { it.id })
    }

    fun sortDaily(items: List<TodoItem>): List<TodoItem> {
        val (timeless, timed) = items.partition { it.time == null }
        return timeless.sortedBy { it.id } + timed.sortedWith(compareBy<TodoItem> { parseTimeToMinutes(it.time) }.thenBy { it.id })
    }

    fun sortToday(items: List<TodoItem>): List<TodoItem> {
        // Timeless first: DAILY with no time. (TIMED tasks today always have a time part in dueDate)
        val (timeless, timed) = items.partition { it.type == TodoType.DAILY && it.time == null }
        
        return timeless.sortedBy { it.id } + timed.sortedWith(compareBy<TodoItem> { it.getTimeOfDayMinutes() }.thenBy { it.id })
    }

    private fun TodoItem.getTimeOfDayMinutes(): Int {
        return when (type) {
            TodoType.TIMED -> {
                val cal = Calendar.getInstance()
                cal.timeInMillis = dueDate ?: 0L
                cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            }
            TodoType.DAILY -> parseTimeToMinutes(time)
            TodoType.TIMELESS -> -1
        }
    }

    private fun parseTimeToMinutes(timeStr: String?): Int {
        if (timeStr == null) return -1
        return try {
            val df = DateFormat.getTimeInstance(DateFormat.SHORT)
            val date = df.parse(timeStr) ?: return 0
            val cal = Calendar.getInstance()
            cal.time = date
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        } catch (e: Exception) {
            0
        }
    }
}
