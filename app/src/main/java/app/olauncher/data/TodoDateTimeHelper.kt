package app.olauncher.data

import java.text.DateFormat
import java.util.Calendar

object TodoDateTimeHelper {

    fun hasExplicitEnd(item: TodoItem): Boolean {
        return item.toTime != null || item.toDate != null
    }

    fun isOvernight(item: TodoItem): Boolean {
        if (item.time == null || item.toTime == null) return false
        val fromMinutes = parseTimeToMinutes(item.time) ?: return false
        val toMinutes = parseTimeToMinutes(item.toTime) ?: return false
        
        return if (item.dueDate != null && item.toDate != null) {
            // Explicit dates: overnight if fromTime > toTime AND toDate is same or slightly after fromDate
            fromMinutes > toMinutes
        } else {
            // Implied: no toDate or no dates at all
            fromMinutes > toMinutes
        }
    }

    fun getStartAtMillis(item: TodoItem, baseTimestamp: Long = System.currentTimeMillis(), prefs: Prefs? = null): Long? {
        return when (item.type) {
            TodoType.TIMED -> item.dueDate
            TodoType.DAILY -> {
                if (item.time == null) return null
                val cal = Calendar.getInstance().apply { timeInMillis = baseTimestamp }
                if (prefs != null) {
                    val resetMinutes = prefs.resetTimeMinutes
                    val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                    if (currentMinutes < resetMinutes) {
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                    }
                }
                val timeCal = parseTimeToCalendar(item.time) ?: return null
                cal.set(Calendar.HOUR_OF_DAY, timeCal.get(Calendar.HOUR_OF_DAY))
                cal.set(Calendar.MINUTE, timeCal.get(Calendar.MINUTE))
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            TodoType.TIMELESS -> null
        }
    }

    fun getEndAtMillis(item: TodoItem, baseTimestamp: Long = System.currentTimeMillis(), prefs: Prefs? = null): Long? {
        val startAt = getStartAtMillis(item, baseTimestamp, prefs)
        
        return when (item.type) {
            TodoType.TIMED -> {
                val baseEnd = item.toDate ?: item.dueDate ?: return null
                if (item.toTime == null) {
                    // Normalize end date to 23:59:59.999 if it's a date-only range
                    if (item.toDate != null) {
                        val cal = Calendar.getInstance().apply { timeInMillis = baseEnd }
                        cal.set(Calendar.HOUR_OF_DAY, 23)
                        cal.set(Calendar.MINUTE, 59)
                        cal.set(Calendar.SECOND, 59)
                        cal.set(Calendar.MILLISECOND, 999)
                        return cal.timeInMillis
                    }
                    return baseEnd
                }
                
                val startCal = Calendar.getInstance().apply { timeInMillis = item.dueDate ?: baseEnd }
                val endCal = Calendar.getInstance().apply { timeInMillis = baseEnd }
                val toTimeCal = parseTimeToCalendar(item.toTime) ?: return baseEnd
                
                endCal.set(Calendar.HOUR_OF_DAY, toTimeCal.get(Calendar.HOUR_OF_DAY))
                endCal.set(Calendar.MINUTE, toTimeCal.get(Calendar.MINUTE))
                endCal.set(Calendar.SECOND, 0)
                endCal.set(Calendar.MILLISECOND, 0)
                
                // If toTime < fromTime and no explicit different toDate, it's overnight
                if (item.toDate == null || item.toDate == item.dueDate) {
                    val fromTimeMinutes = if (item.time != null) parseTimeToMinutes(item.time) else {
                        startCal.get(Calendar.HOUR_OF_DAY) * 60 + startCal.get(Calendar.MINUTE)
                    }
                    val toTimeMinutes = parseTimeToMinutes(item.toTime)
                    if (fromTimeMinutes != null && toTimeMinutes != null && toTimeMinutes < fromTimeMinutes) {
                        endCal.add(Calendar.DAY_OF_YEAR, 1)
                    }
                }
                endCal.timeInMillis
            }
            TodoType.DAILY -> {
                if (item.toTime == null) return startAt
                val start = startAt ?: return null
                val endCal = Calendar.getInstance().apply { timeInMillis = start }
                val toTimeCal = parseTimeToCalendar(item.toTime) ?: return start
                
                endCal.set(Calendar.HOUR_OF_DAY, toTimeCal.get(Calendar.HOUR_OF_DAY))
                endCal.set(Calendar.MINUTE, toTimeCal.get(Calendar.MINUTE))
                
                val fromM = parseTimeToMinutes(item.time)
                val toM = parseTimeToMinutes(item.toTime)
                if (fromM != null && toM != null && toM < fromM) {
                    endCal.add(Calendar.DAY_OF_YEAR, 1)
                }
                endCal.timeInMillis
            }
            TodoType.TIMELESS -> null
        }
    }

    private fun parseTimeToMinutes(timeStr: String?): Int? {
        if (timeStr == null) return null
        return try {
            val df = DateFormat.getTimeInstance(DateFormat.SHORT)
            val date = df.parse(timeStr) ?: return null
            val cal = Calendar.getInstance()
            cal.time = date
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTimeToCalendar(timeStr: String?): Calendar? {
        if (timeStr == null) return null
        return try {
            val df = DateFormat.getTimeInstance(DateFormat.SHORT)
            val date = df.parse(timeStr) ?: return null
            Calendar.getInstance().apply { time = date }
        } catch (e: Exception) {
            null
        }
    }
}
