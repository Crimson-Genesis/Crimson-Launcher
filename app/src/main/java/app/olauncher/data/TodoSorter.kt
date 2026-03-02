package app.olauncher.data

import java.util.Calendar

object TodoSorter {
    fun sortUpcoming(items: List<TodoItem>): List<TodoItem> {
        val (timeless, timed) = items.partition { it.type == TodoType.TIMELESS }
        return timeless.sortedBy { it.id } + timed.sortedWith(compareBy<TodoItem> { 
            TodoDateTimeHelper.getStartAtMillis(it) ?: Long.MAX_VALUE 
        }.thenBy { it.id })
    }

    fun sortCompleted(items: List<TodoItem>): List<TodoItem> {
        return items.sortedWith(compareByDescending<TodoItem> { it.completedAt ?: 0L }.thenByDescending { it.id })
    }

    fun sortDaily(items: List<TodoItem>): List<TodoItem> {
        val (timeless, timed) = items.partition { it.time == null }
        return timeless.sortedBy { it.id } + timed.sortedWith(compareBy<TodoItem> { 
            TodoDateTimeHelper.getStartAtMillis(it) ?: Long.MAX_VALUE 
        }.thenBy { it.id })
    }

    fun sortToday(items: List<TodoItem>): List<TodoItem> {
        val (timeless, timed) = items.partition { it.type == TodoType.DAILY && it.time == null }
        
        return timeless.sortedBy { it.id } + timed.sortedWith(compareBy<TodoItem> { 
            it.getTimeOfDayMinutes() 
        }.thenBy { it.id })
    }

    private fun TodoItem.getTimeOfDayMinutes(): Int {
        val startAt = TodoDateTimeHelper.getStartAtMillis(this) ?: return -1
        val cal = Calendar.getInstance().apply { timeInMillis = startAt }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }
}
