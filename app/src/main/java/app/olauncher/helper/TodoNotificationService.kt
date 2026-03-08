package app.olauncher.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import app.olauncher.R
import app.olauncher.data.AppDatabase
import app.olauncher.data.Prefs
import app.olauncher.data.TodoDateTimeHelper
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoItemRepository
import app.olauncher.data.TodoType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TodoNotificationService : LifecycleService() {

    private lateinit var prefs: Prefs
    private lateinit var repository: TodoItemRepository
    
    private val refreshTrigger = MutableLiveData<Long>()
    private var isDismissedByUser = false
    
    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF, 
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_SCREEN_ON -> {
                    // Reset dismissal state whenever screen state changes (lock/unlock)
                    isDismissedByUser = false
                }
                ACTION_DISMISSED -> {
                    isDismissedByUser = true
                    // When dismissed, we stop updating the notification until the next screen event
                    return 
                }
            }
            refreshTrigger.postValue(System.currentTimeMillis())
        }
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "ACTIVE_BOILER_ID" || key == "SHOW_LOCKSCREEN_TODO" || key == "RESET_TIME_MINUTES") {
            refreshTrigger.postValue(System.currentTimeMillis())
        }
    }

    companion object {
        const val CHANNEL_ID = "todo_today_channel"
        const val NOTIFICATION_ID = 1001
        private const val ACTION_DISMISSED = "app.olauncher.TODO_NOTIFICATION_DISMISSED"
        
        fun start(context: Context) {
            val intent = Intent(context, TodoNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TodoNotificationService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        val database = AppDatabase.getDatabase(this)
        repository = TodoItemRepository(database.todoItemDao())
        
        createNotificationChannel()
        
        // Start foreground immediately
        val initialNotification = createNotification(getString(R.string.no_active_todos), "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(ACTION_DISMISSED)
        }
        
        // Use ContextCompat for safer receiver registration
        ContextCompat.registerReceiver(this, systemEventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        
        val sharedPrefs = getSharedPreferences("app.olauncher", Context.MODE_PRIVATE)
        sharedPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        
        observeTodos()
        refreshTrigger.postValue(System.currentTimeMillis())
    }

    private fun observeTodos() {
        refreshTrigger.switchMap { timestamp ->
            val logicalDate = prefs.getLogicalDayKey(timestamp)
            val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
            val resetMinutes = prefs.resetTimeMinutes
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (currentMinutes < resetMinutes) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            val dayOfWeek = SimpleDateFormat("EEE", Locale.US).format(cal.time)
            val boilerId = prefs.activeBoilerId
            
            repository.getTodayTodoItems(logicalDate, dayOfWeek, boilerId)
        }.observe(this) { items ->
            updateNotification(items)
        }
    }

    private fun updateNotification(items: List<TodoItem>) {
        if (isDismissedByUser) return

        val now = System.currentTimeMillis()
        val uncompleted = items.filter { !it.isCompleted }

        if (uncompleted.isEmpty()) {
            val title = getString(R.string.no_active_todos)
            val text = getString(R.string.enjoy_your_day)
            val notification = createNotification(title, text)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
            return
        }

        // Build task groups from items.filter { !it.isCompleted }
        // 1. overdue: tasks where item.isOverdue(prefs) == true
        val overdue = uncompleted.filter { it.isOverdue(prefs) }
            .sortedWith(compareBy({ TodoDateTimeHelper.getStartAtMillis(it, now, prefs) ?: Long.MAX_VALUE }, { it.id }))

        // 2. activeNow: tasks where startAt <= now <= endAt, or tasks with no time (timeless/daily no-time)
        val activeNow = uncompleted.filter {
            val start = TodoDateTimeHelper.getStartAtMillis(it, now, prefs)
            val end = TodoDateTimeHelper.getEndAtMillis(it, now, prefs)
            if (start != null && end != null) {
                now >= start && now <= end
            } else {
                // Tasks with no time (start == null) are considered active all day if not overdue
                !it.isOverdue(prefs) && start == null
            }
        }.sortedWith(compareBy({ TodoDateTimeHelper.getStartAtMillis(it, now, prefs) ?: Long.MAX_VALUE }, { it.id }))

        // 3. upcoming: tasks where startAt > now
        val upcoming = uncompleted.filter {
            val start = TodoDateTimeHelper.getStartAtMillis(it, now, prefs)
            start != null && start > now
        }.sortedWith(compareBy({ TodoDateTimeHelper.getStartAtMillis(it, now, prefs) ?: Long.MAX_VALUE }, { it.id }))

        // Selection rules
        val current = activeNow.firstOrNull()
        
        // next = first item in upcoming or subsequent active items if current is also active
        val next = activeNow.drop(1).firstOrNull() ?: upcoming.firstOrNull()

        // Notification content rules: Title
        val title: CharSequence = if (current != null) {
            val currentInfo = getTaskInfo(current)
            if (currentInfo.isNotEmpty()) "${current.task} | $currentInfo" else current.task
        } else {
            getString(R.string.no_current_task)
        }

        // Notification content rules: Body
        // Overdue section first
        val overdueLine = if (overdue.isNotEmpty()) {
            getString(R.string.overdue_prefix, overdue.joinToString { it.task })
        } else ""

        // Next section second
        val nextLine = if (next != null) {
            val nextInfo = getTaskInfo(next)
            val nextContent = if (nextInfo.isNotEmpty()) "${next.task} | $nextInfo" else next.task
            getString(R.string.next_task_prefix, nextContent)
        } else {
            getString(R.string.thats_all_for_today)
        }

        val text: String
        val bigText: CharSequence?
        if (overdueLine.isNotEmpty()) {
            text = "$overdueLine • $nextLine"
            bigText = "$overdueLine\n$nextLine"
        } else {
            text = nextLine
            bigText = null
        }

        val notification = createNotification(title, text, bigText)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun getTaskInfo(item: TodoItem): String {
        return when (item.type) {
            TodoType.DAILY -> {
                val fromTime = item.time
                val toTime = item.toTime
                if (fromTime != null && toTime != null) "$fromTime - $toTime"
                else fromTime ?: ""
            }
            TodoType.TIMED -> {
                val fromDate = item.dueDate
                val toDate = item.toDate
                val fromTime = item.time
                val toTime = item.toTime
                
                val dateSdf = SimpleDateFormat("MMM d", Locale.getDefault())
                val timeSdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                val fullSdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                
                if (fromDate != null) {
                    val fromCal = Calendar.getInstance().apply { timeInMillis = fromDate }
                    if (toDate != null) {
                        val toCal = Calendar.getInstance().apply { timeInMillis = toDate }
                        val isSameDay = fromCal.get(Calendar.YEAR) == toCal.get(Calendar.YEAR) &&
                                        fromCal.get(Calendar.DAY_OF_YEAR) == toCal.get(Calendar.DAY_OF_YEAR)
                        
                        if (isSameDay) {
                            val dateStr = dateSdf.format(fromCal.time)
                            val fTime = fromTime ?: timeSdf.format(fromCal.time)
                            val tTime = toTime ?: timeSdf.format(toCal.time)
                            "$dateStr | $fTime - $tTime"
                        } else {
                            val fStr = fullSdf.format(fromCal.time)
                            val tStr = fullSdf.format(toCal.time)
                            "$fStr - $tStr"
                        }
                    } else {
                        if (toTime != null) {
                            val dateStr = dateSdf.format(fromCal.time)
                            val fTime = fromTime ?: timeSdf.format(fromCal.time)
                            "$dateStr | $fTime - $toTime"
                        } else {
                            fullSdf.format(fromCal.time)
                        }
                    }
                } else ""
            }
            else -> ""
        }
    }

    private fun createNotification(title: CharSequence, text: String, bigText: CharSequence? = null): android.app.Notification {
        val deleteIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_DISMISSED).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_todo_notification)
            .setColor(ContextCompat.getColor(this, R.color.black))
            .setOngoing(false) 
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(deleteIntent)
            .setContentIntent(null) // Display-only

        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.todo_today_channel_name)
            val descriptionText = getString(R.string.todo_today_channel_desc)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(systemEventReceiver)
        val sharedPrefs = getSharedPreferences("app.olauncher", Context.MODE_PRIVATE)
        sharedPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
    }
}
