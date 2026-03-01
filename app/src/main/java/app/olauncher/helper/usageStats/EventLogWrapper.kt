package app.olauncher.helper.usageStats

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.icu.util.Calendar
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.function.BiConsumer
import kotlin.math.max
import kotlin.math.min

class EventLogWrapper(private val context: Context) {
    private val usageStatsManager by lazy { context.getSystemService("usagestats") as UsageStatsManager }
    private val guardian = UnmatchedCloseEventGuardian(usageStatsManager)


    /**
     * Collects event information from system to calculate and aggregate precise
     * foreground time statistics for the specified period.
     */
    fun getForegroundStatsByTimestamps(start: Long, end: Long): List<ComponentForegroundStat> {
        var queryStart = start 

        val foregroundProcesses = mutableListOf<String>()
        if (end >= System.currentTimeMillis() - 1500) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.runningAppProcesses?.forEach { appProcess ->
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                ) {
                    if (context.packageName != appProcess.processName) {
                        foregroundProcesses.add(appProcess.processName)
                    }
                }
            }
        }

        val events = usageStatsManager.queryEvents(queryStart, end)
        val moveToForegroundMap = mutableMapOf<AppClass, Long?>()
        val componentForegroundStats = mutableListOf<ComponentForegroundStat>()
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (context.packageName == event.packageName) continue

            val appClass = AppClass(event.packageName, event.className)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 4 -> {
                    moveToForegroundMap[appClass] = event.timeStamp
                }

                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, 3 -> {
                    val eventBeginTime: Long? = moveToForegroundMap[appClass]?.also {
                        moveToForegroundMap[appClass] = null
                    } ?: if (
                        moveToForegroundMap.keys.none { it.packageName == event.packageName } &&
                        guardian.test(event, queryStart)
                    ) {
                        queryStart
                    } else {
                        null
                    }

                    if (eventBeginTime != null) {
                        val endTime = moveToForegroundMap.entries
                            .filter { (key, value) -> key.packageName == event.packageName && value != null }
                            .mapNotNull { it.value }
                            .minOrNull() ?: event.timeStamp

                        componentForegroundStats.add(
                            ComponentForegroundStat(eventBeginTime, endTime, event.packageName)
                        )
                    }
                }

                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    moveToForegroundMap.forEach { (key, value) ->
                        if (value != null) {
                            componentForegroundStats.add(
                                ComponentForegroundStat(value, event.timeStamp, key.packageName)
                            )
                            moveToForegroundMap.keys
                                .filter { it.packageName == key.packageName }
                                .forEach { samePackageKey -> moveToForegroundMap[samePackageKey] = null }
                        }
                    }
                }

                UsageEvents.Event.DEVICE_STARTUP -> {
                    moveToForegroundMap.clear()
                    queryStart = event.timeStamp
                }
            }
        }

        moveToForegroundMap.forEach { (key, value) ->
            if (value != null) {
                if (foregroundProcesses.any { it.contains(key.packageName) }) {
                    componentForegroundStats.add(
                        ComponentForegroundStat(
                            value,
                            min(System.currentTimeMillis(), end),
                            key.packageName
                        )
                    )
                }
            }
        }

        return componentForegroundStats
    }

    /**
     * Enhanced version to get both time and unlock counts.
     */
    fun getUsageStatsResult(start: Long, end: Long): UsageStatsResult {
        val stats = getForegroundStatsByTimestamps(start, end)
        val totalTime = stats.sumOf { it.endTime - it.beginTime }
        
        var unlocks = 0
        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            // SCREEN_INTERACTIVE is type 15, available from API 28
            if (event.eventType == 15) {
                unlocks++
            }
        }
        
        return UsageStatsResult(totalTime, unlocks)
    }

    fun getForegroundStatsByPartialDay(start: Long): List<ComponentForegroundStat> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = start
        cal.add(Calendar.DAY_OF_YEAR, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val endTime = cal.timeInMillis
        return getForegroundStatsByTimestamps(start, endTime)
    }

    private data class AppClass(val packageName: String, val className: String?)
}
