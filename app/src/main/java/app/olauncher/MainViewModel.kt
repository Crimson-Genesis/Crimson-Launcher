package app.olauncher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.UserHandle
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import app.olauncher.data.AppDatabase
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoItemRepository
import app.olauncher.data.TodoTemplate
import app.olauncher.data.TodoTemplateItem
import app.olauncher.data.TodoTemplateRepository
import app.olauncher.data.TodoType
import app.olauncher.helper.DailySnapshot
import app.olauncher.helper.DaySummary
import app.olauncher.helper.EventLogger
import app.olauncher.helper.LogEvent
import app.olauncher.helper.SingleLiveEvent
import app.olauncher.helper.formattedTimeSpent
import app.olauncher.helper.getAppsList
import app.olauncher.helper.isCrimsonDefault as isCrimsonDefaultHelper
import app.olauncher.helper.usageStats.EventLogWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext by lazy { application.applicationContext }
    private val prefs = Prefs(appContext)
    private val launcherApps by lazy { appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps }
    private val resetMutex = Mutex()

    val firstOpen = MutableLiveData<Boolean>()
    val toggleDateTime = MutableLiveData<Unit>()
    val updateSwipeApps = MutableLiveData<Any>()
    val appList = MutableLiveData<List<AppModel>?>()
    val hiddenApps = MutableLiveData<List<AppModel>?>()
    val isCrimsonDefault = MutableLiveData<Boolean>()
    val launcherResetFailed = MutableLiveData<Boolean>()
    val homeAppAlignment = MutableLiveData<Int>()
    val screenTimeValue = MutableLiveData<String>()

    val showDialog = SingleLiveEvent<String>()
    val checkForMessages = SingleLiveEvent<Unit?>()
    val resetLauncherLiveData = SingleLiveEvent<Unit?>()
    val copyTaskEvent = SingleLiveEvent<TodoItem>()

    // Caching the app list
    private var cachedAppList: List<AppModel>? = null
    private var cachedHiddenApps: List<AppModel>? = null

    // Todo list properties
    private val database = AppDatabase.getDatabase(application)
    private val repository = TodoItemRepository(database.todoItemDao())
    private val templateRepository = TodoTemplateRepository(database.todoTemplateDao())
    
    // We use logicalDayTrigger to refresh todayTodoItems when logical day might have changed
    private val logicalDayTrigger = MutableLiveData<Long>(System.currentTimeMillis())
    
    private val activeBoilerId = MutableLiveData<Long>(prefs.activeBoilerId)

    val todayTodoItems: LiveData<List<TodoItem>> = activeBoilerId.switchMap { boilerId ->
        logicalDayTrigger.switchMap { timestamp ->
            val logicalDate = prefs.getLogicalDayKey(timestamp)
            val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
            // Adjust cal if timestamp was before reset time today
            val resetMinutes = prefs.resetTimeMinutes
            val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (currentMinutes < resetMinutes) {
                cal.add(Calendar.DAY_OF_YEAR, -1)
            }
            val dayOfWeek = SimpleDateFormat("EEE", Locale.US).format(cal.time)
            repository.getTodayTodoItems(logicalDate, dayOfWeek, boilerId)
        }
    }

    val completedTodoItems: LiveData<List<TodoItem>> = activeBoilerId.switchMap { boilerId ->
        repository.getCompletedTodoItems(boilerId)
    }
    val upcomingTodoItems: LiveData<List<TodoItem>> = activeBoilerId.switchMap { boilerId ->
        repository.getUpcomingTodoItems(boilerId)
    }
    val allDailyTasks: LiveData<List<TodoItem>> = activeBoilerId.switchMap { boilerId ->
        repository.getDailyTasksForTemplate(boilerId)
    }
    val allTodoItems: LiveData<List<TodoItem>> = database.todoItemDao().getAllTodoItems()
    val allTemplates: LiveData<List<TodoTemplate>> = templateRepository.allTemplates
    
    private val _activeBoilerName = MutableLiveData<String>()
    val activeBoilerName: LiveData<String> get() = _activeBoilerName

    private val appCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
            getAppList(true)
            getHiddenApps(true)
        }

        override fun onPackageAdded(packageName: String?, user: UserHandle?) {
            getAppList(true)
            getHiddenApps(true)
        }

        override fun onPackageChanged(packageName: String?, user: UserHandle?) {
            getAppList(true)
            getHiddenApps(true)
        }

        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
            getAppList(true)
            getHiddenApps(true)
        }

        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
            getAppList(true)
            getHiddenApps(true)
        }

        override fun onShortcutsChanged(packageName: String, shortcuts: List<ShortcutInfo>, user: UserHandle) {
            getAppList(true)
        }
    }

    init {
        _activeBoilerName.value = prefs.activeBoilerName
        checkAndInsertDefaultTemplate()
        launcherApps.registerCallback(appCallback)
    }

    override fun onCleared() {
        super.onCleared()
        launcherApps.unregisterCallback(appCallback)
    }

    private fun checkAndInsertDefaultTemplate() = viewModelScope.launch(Dispatchers.IO) {
        val templates = templateRepository.getAllTemplatesSync()
        if (templates.isEmpty()) {
            val defaultTemplate = TodoTemplate(name = "Default")
            templateRepository.insertTemplateWithItems(defaultTemplate, emptyList())
        }
        
        // If activeBoilerId is -1, try to find the Default template and set it
        if (prefs.activeBoilerId == -1L) {
            val defaultTemplate = database.todoTemplateDao().getDefaultTemplate()
            if (defaultTemplate != null) {
                prefs.activeBoilerId = defaultTemplate.id
                prefs.activeBoilerName = defaultTemplate.name
                _activeBoilerName.postValue(defaultTemplate.name)
                activeBoilerId.postValue(defaultTemplate.id)
            }
        }
    }

    suspend fun getAllTodoItemsSync(): List<TodoItem> = withContext(Dispatchers.IO) {
        repository.getAllTodoItemsSync()
    }

    suspend fun getTodoItemById(id: Long): TodoItem? = withContext(Dispatchers.IO) {
        repository.getById(id)
    }

    fun checkAndPerformMidnightReset() {
        viewModelScope.launch(Dispatchers.IO) {
            resetMutex.withLock {
                val currentLogicalDay = prefs.getLogicalDayKey(System.currentTimeMillis())
                val lastReset = prefs.lastResetDayKey
                
                if (lastReset == null) {
                    prefs.lastResetDayKey = currentLogicalDay
                    return@withLock
                }

                if (lastReset != currentLogicalDay) {
                    Log.d("Midnight", "New logical day detected: $currentLogicalDay (previously $lastReset). Resetting daily tasks.")
                    
                    val allItems = repository.getAllTodoItemsSync()
                    
                    val completedYesterdayCount = allItems.count { it.isCompleted && it.completedAt != null && prefs.getLogicalDayKey(it.completedAt) == lastReset }
                    val totalYesterday = allItems.count { 
                        it.type == TodoType.DAILY || (it.type == TodoType.TIMED && it.dueDate != null && prefs.getLogicalDayKey(it.dueDate) == lastReset)
                    }
                    val dailyTasks = allItems.filter { it.type == TodoType.DAILY }
                    val dailyCompleted = dailyTasks.count { it.isCompleted }
                    val timedOverdue = allItems.count { 
                        it.type == TodoType.TIMED && !it.isCompleted && (it.dueDate ?: 0) < System.currentTimeMillis() && prefs.getLogicalDayKey(it.dueDate ?: 0) != lastReset && prefs.getLogicalDayKey(it.dueDate ?: 0) != currentLogicalDay
                    }

                    if (completedYesterdayCount > 0) {
                        prefs.currentStreakDays++
                        prefs.lastCompletionDate = lastReset
                    } else {
                        prefs.currentStreakDays = 0
                    }

                    val summary = DaySummary(
                        completedToday = completedYesterdayCount,
                        totalToday = totalYesterday,
                        completionRate = if (totalYesterday > 0) completedYesterdayCount.toDouble() / totalYesterday else 0.0,
                        dailyTasksCompleted = dailyCompleted,
                        dailyTasksTotal = dailyTasks.size,
                        timedOverdue = timedOverdue,
                        deletedToday = prefs.dailyStatsDeletedCount,
                        addedToday = prefs.dailyStatsAddedCount,
                        streak = prefs.currentStreakDays
                    )

                    EventLogger.log(appContext, LogEvent.DayReset(summary))
                    
                    prefs.dailyStatsAddedCount = 0
                    prefs.dailyStatsDeletedCount = 0
                    
                    database.todoItemDao().resetDailyTasks()
                    
                    prefs.lastResetDayKey = currentLogicalDay
                    prefs.shownOnDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
                }
                
                // Always trigger refresh at the end of the check, even if reset didn't happen
                refreshTodayList()
            }
        }
    }

    fun resetDailyTasks() = viewModelScope.launch(Dispatchers.IO) {
        database.todoItemDao().resetDailyTasks()
        refreshTodayList()
    }

    fun refreshTodayList() {
        logicalDayTrigger.postValue(System.currentTimeMillis())
    }

    fun insert(todoItem: TodoItem) = viewModelScope.launch(Dispatchers.IO) {
        var itemToInsert = todoItem
        if (todoItem.type == TodoType.DAILY && prefs.activeBoilerId != -1L) {
            val templateItemId = database.todoTemplateDao().insertTemplateItem(TodoTemplateItem(
                templateId = prefs.activeBoilerId,
                task = todoItem.task,
                type = todoItem.type,
                time = todoItem.time,
                daysOfWeek = todoItem.daysOfWeek,
                toDate = todoItem.toDate,
                toTime = todoItem.toTime
            ))
            itemToInsert = todoItem.copy(
                originTemplateId = prefs.activeBoilerId,
                originTemplateItemId = templateItemId
            )
        }

        val id = repository.insert(itemToInsert)
        prefs.dailyStatsAddedCount++

        val insertedItem = itemToInsert.copy(id = id)
        EventLogger.log(appContext, LogEvent.TaskAdded(insertedItem, buildSnapshot()))
    }

    fun update(todoItem: TodoItem) = viewModelScope.launch(Dispatchers.IO) {
        val oldItem = repository.getById(todoItem.id)
        if (oldItem == null) {
            repository.update(todoItem)
            return@launch
        }

        val updatedItem = if (todoItem.isCompleted && !oldItem.isCompleted) {
            todoItem.copy(completedAt = System.currentTimeMillis())
        } else if (!todoItem.isCompleted && oldItem.isCompleted) {
            todoItem.copy(completedAt = null)
        } else {
            todoItem
        }

        repository.update(updatedItem)

        if (updatedItem.type == TodoType.DAILY && updatedItem.originTemplateItemId != null) {
            database.todoTemplateDao().updateDailyTaskInTemplateById(
                id = updatedItem.originTemplateItemId,
                newTask = updatedItem.task,
                newTime = updatedItem.time,
                newDaysOfWeek = updatedItem.daysOfWeek,
                newToDate = updatedItem.toDate,
                newToTime = updatedItem.toTime
            )
        }

        val snapshot = buildSnapshot()
        when {
            updatedItem.isCompleted && !oldItem.isCompleted -> {
                EventLogger.log(appContext, LogEvent.TaskCompleted(updatedItem, snapshot))
            }
            !updatedItem.isCompleted && oldItem.isCompleted -> {
                EventLogger.log(appContext, LogEvent.TaskUncompleted(updatedItem, snapshot))
            }
            else -> {
                val changedFields = mutableListOf<String>()
                if (oldItem.task != updatedItem.task) changedFields.add("task")
                if (oldItem.type != updatedItem.type) changedFields.add("type")
                if (oldItem.dueDate != updatedItem.dueDate) changedFields.add("dueDate")
                if (oldItem.time != updatedItem.time) changedFields.add("time")
                if (oldItem.daysOfWeek != updatedItem.daysOfWeek) changedFields.add("daysOfWeek")
                
                if (changedFields.isNotEmpty()) {
                    EventLogger.log(appContext, LogEvent.TaskEdited(oldItem, updatedItem, changedFields, snapshot))
                }
            }
        }
    }

    fun delete(todoItem: TodoItem) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(todoItem)
        prefs.dailyStatsDeletedCount++
        
        if (todoItem.type == TodoType.DAILY && todoItem.originTemplateItemId != null) {
            database.todoTemplateDao().deleteDailyTaskFromTemplateById(todoItem.originTemplateItemId)
        }

        EventLogger.log(appContext, LogEvent.TaskDeleted(todoItem, snapshot = buildSnapshot()))
    }

    fun deleteAllTodoItems() = viewModelScope.launch(Dispatchers.IO) {
        repository.deleteAll()
        
        val templateDao = database.todoTemplateDao()
        
        // Delete all templates
        templateDao.deleteAllTemplates()
        
        // Reset active boiler to none
        prefs.activeBoilerId = -1L
        prefs.activeBoilerName = "Default"
        _activeBoilerName.postValue("Default")
        activeBoilerId.postValue(-1L)
        
        // Insert a new Default template
        val defaultTemplate = TodoTemplate(name = "Default")
        val newDefaultId = templateDao.insertTemplateWithItems(defaultTemplate, emptyList())
        
        // Set new Default as active
        prefs.activeBoilerId = newDefaultId
        prefs.activeBoilerName = "Default"
        _activeBoilerName.postValue("Default")
        activeBoilerId.postValue(newDefaultId)
    }

    suspend fun replaceTodoItems(items: List<TodoItem>) = withContext(Dispatchers.IO) {
        repository.deleteAll()
        items.forEach { repository.insert(it) }
    }

    fun createNewTemplate(name: String) = viewModelScope.launch(Dispatchers.IO) {
        val template = TodoTemplate(name = name)
        val newTemplateId = templateRepository.insertTemplateWithItems(template, emptyList())
        
        // Switch to the newly created template
        applyTemplate(newTemplateId)
        
        EventLogger.log(appContext, LogEvent.TemplateAdded(name, 0))
    }

    fun renameTemplate(template: TodoTemplate, newName: String) = viewModelScope.launch(Dispatchers.IO) {
        val updatedTemplate = template.copy(name = newName)
        templateRepository.updateTemplate(updatedTemplate)
        
        if (prefs.activeBoilerId == template.id) {
            prefs.activeBoilerName = newName
            _activeBoilerName.postValue(newName)
        }
    }

    fun applyTemplate(templateId: Long) = viewModelScope.launch(Dispatchers.IO) {
        val templateWithItems = templateRepository.getTemplateWithItems(templateId) ?: run {
            Log.e("BoilerSwitch", "Template with ID $templateId not found. Re-initializing default.")
            recreateDefaultTemplate()
            return@launch
        }
        
        val oldTemplateId = prefs.activeBoilerId
        
        prefs.activeBoilerId = templateId
        prefs.activeBoilerName = templateWithItems.template.name
        _activeBoilerName.postValue(templateWithItems.template.name)
        activeBoilerId.postValue(templateId)

        // Clear uncompleted DAILY tasks from the previous template
        if (oldTemplateId != -1L) {
            database.todoItemDao().deleteUncompletedDailyByTemplateId(oldTemplateId)
        }

        // Insert template items as DAILY tasks, deduplicating using originTemplateItemId
        templateWithItems.items.forEach { templateItem ->
            val existingItem = repository.getByTemplateItemId(templateItem.id)
            if (existingItem == null) {
                repository.insert(TodoItem(
                    task = templateItem.task,
                    type = templateItem.type,
                    dueDate = templateItem.dueDate,
                    time = templateItem.time,
                    daysOfWeek = templateItem.daysOfWeek,
                    toDate = templateItem.toDate,
                    toTime = templateItem.toTime,
                    originTemplateId = templateId,
                    originTemplateItemId = templateItem.id
                ))
            } else {
                if (!existingItem.isCompleted) {
                    // Update uncompleted task if it exists (sanity check)
                    repository.update(existingItem.copy(
                        task = templateItem.task,
                        type = templateItem.type,
                        dueDate = templateItem.dueDate,
                        time = templateItem.time,
                        daysOfWeek = templateItem.daysOfWeek,
                        toDate = templateItem.toDate,
                        toTime = templateItem.toTime,
                        originTemplateId = templateId
                    ))
                } else {
                    Log.d("BoilerSwitch", "Skipping re-insertion of completed task: ${existingItem.task} (originId: ${existingItem.originTemplateItemId})")
                }
            }
        }
        EventLogger.log(appContext, LogEvent.TemplateApplied(templateWithItems.template.name, templateWithItems.items.size))
    }

    fun deleteTemplate(template: TodoTemplate) = viewModelScope.launch(Dispatchers.IO) {
        templateRepository.deleteTemplate(template)
        EventLogger.log(appContext, LogEvent.TemplateDeleted(template.name))
        
        val remainingTemplates = templateRepository.getAllTemplatesSync()
        if (remainingTemplates.isEmpty()) {
            recreateDefaultTemplate()
        } else if (prefs.activeBoilerId == template.id) {
            // Switch back to Default if active one is deleted
            val defaultTemplate = database.todoTemplateDao().getDefaultTemplate()
            if (defaultTemplate != null) {
                applyTemplate(defaultTemplate.id)
            } else {
                // If "Default" doesn't exist but other templates do, pick the first one
                applyTemplate(remainingTemplates.first().id)
            }
        }
    }

    private suspend fun recreateDefaultTemplate() {
        val defaultTemplate = TodoTemplate(name = "Default")
        val newDefaultId = templateRepository.insertTemplateWithItems(defaultTemplate, emptyList())
        applyTemplate(newDefaultId)
    }

    private suspend fun buildSnapshot(): DailySnapshot = withContext(Dispatchers.IO) {
        val allItems = repository.getAllTodoItemsSync()
        val logicalToday = prefs.getLogicalDayKey(System.currentTimeMillis())
        
        val dailyTasks = allItems.filter { it.type == TodoType.DAILY }
        val dailyCompleted = dailyTasks.count { it.isCompleted }
        
        val totalToday = allItems.count { it.type == TodoType.DAILY || (it.type == TodoType.TIMED && it.dueDate != null && prefs.getLogicalDayKey(it.dueDate) == logicalToday) }
        val completedTodayCount = allItems.count { (it.type == TodoType.DAILY || (it.type == TodoType.TIMED && it.dueDate != null && prefs.getLogicalDayKey(it.dueDate) == logicalToday)) && it.isCompleted }

        val timedOverdue = allItems.count { it.type == TodoType.TIMED && !it.isCompleted && (it.dueDate ?: 0) < System.currentTimeMillis() && it.dueDate != null && prefs.getLogicalDayKey(it.dueDate) != logicalToday }

        DailySnapshot(
            completedToday = completedTodayCount,
            totalToday = totalToday,
            completionRate = if (totalToday > 0) completedTodayCount.toDouble() / totalToday else 0.0,
            dailyTasksCompleted = dailyCompleted,
            dailyTasksTotal = dailyTasks.size,
            timedOverdue = timedOverdue,
            deletedToday = prefs.dailyStatsDeletedCount,
            addedToday = prefs.dailyStatsAddedCount
        )
    }

    fun selectedApp(appModel: AppModel, flag: Int) {
        when (flag) {
            Constants.FLAG_LAUNCH_APP -> {
                when (appModel) {
                    is AppModel.PinnedShortcut -> launchShortcut(appModel)
                    is AppModel.App ->
                        launchApp(appModel)
                }
            }

            Constants.FLAG_HIDDEN_APPS -> {
                if (appModel is AppModel.App) {
                    launchApp(appModel)
                }
            }

            Constants.FLAG_SET_SWIPE_LEFT_APP -> saveSwipeApp(appModel, isLeft = true)
            Constants.FLAG_SET_SWIPE_RIGHT_APP -> saveSwipeApp(appModel, isLeft = false)
            Constants.FLAG_SET_CLOCK_APP -> saveClockApp(appModel)
            Constants.FLAG_SET_CALENDAR_APP -> saveCalendarApp(appModel)
        }
    }

    private fun launchShortcut(appModel: AppModel.PinnedShortcut) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val query = LauncherApps.ShortcutQuery().apply {
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }
            launcher.getShortcuts(query, appModel.user)?.find { it.id == appModel.shortcutId }
                ?.let { shortcut ->
                    EventLogger.log(appContext, LogEvent.AppLaunched(
                        packageName = appModel.appPackage,
                        activity = null,
                        userHandle = appModel.user.toString(),
                        renamedLabelUsed = prefs.getAppRenameLabel(appModel.shortcutId).isNotEmpty(),
                        isHidden = false
                    ))
                    launcher.startShortcut(shortcut, null, null)
                }
        }
    }

    private fun saveSwipeApp(appModel: AppModel, isLeft: Boolean) {
        when (appModel) {
            is AppModel.App -> {
                if (isLeft) {
                    prefs.appNameSwipeLeft = appModel.appLabel
                    prefs.appPackageSwipeLeft = appModel.appPackage
                    prefs.appUserSwipeLeft = appModel.user.toString()
                    prefs.appActivityClassNameSwipeLeft = appModel.activityClassName
                    prefs.isShortcutSwipeLeft = false
                    prefs.shortcutIdSwipeLeft = ""
                } else {
                    prefs.appNameSwipeRight = appModel.appLabel
                    prefs.appPackageSwipeRight = appModel.appPackage
                    prefs.appUserSwipeRight = appModel.user.toString()
                    prefs.appActivityClassNameRight = appModel.activityClassName
                    prefs.isShortcutSwipeRight = false
                    prefs.shortcutIdSwipeRight = ""
                }
            }

            is AppModel.PinnedShortcut -> {
                if (isLeft) {
                    prefs.appNameSwipeLeft = appModel.appLabel
                    prefs.appPackageSwipeLeft = appModel.appPackage
                    prefs.appUserSwipeLeft = appModel.user.toString()
                    prefs.appActivityClassNameSwipeLeft = null
                    prefs.isShortcutSwipeLeft = true
                    prefs.shortcutIdSwipeLeft = appModel.shortcutId
                } else {
                    prefs.appNameSwipeRight = appModel.appLabel
                    prefs.appPackageSwipeRight = appModel.appPackage
                    prefs.appUserSwipeRight = appModel.user.toString()
                    prefs.appActivityClassNameRight = null
                    prefs.isShortcutSwipeRight = true
                    prefs.shortcutIdSwipeRight = appModel.shortcutId
                }
            }
        }
        updateSwipeApps()
    }

    private fun saveClockApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.clockAppPackage = appModel.appPackage
            prefs.clockAppUser = appModel.user.toString()
            prefs.clockAppClassName = appModel.activityClassName
        }
    }

    private fun saveCalendarApp(appModel: AppModel) {
        if (appModel is AppModel.App) {
            prefs.calendarAppPackage = appModel.appPackage
            prefs.calendarAppUser = appModel.user.toString()
            prefs.calendarAppClassName = appModel.activityClassName
        }
    }

    fun firstOpen(value: Boolean) {
        firstOpen.postValue(value)
    }

    fun toggleDateTime() {
        toggleDateTime.postValue(Unit)
    }

    fun updateSwipeApps() {
        updateSwipeApps.postValue(Unit)
    }

    fun getAppList(forceRefresh: Boolean) {
        if (!forceRefresh && cachedAppList != null) {
            appList.postValue(cachedAppList)
            return
        }
        viewModelScope.launch {
            val list = getAppsList(appContext, prefs, includeRegularApps = true)
            cachedAppList = list
            appList.postValue(list)
        }
    }

    fun getHiddenApps(forceRefresh: Boolean = false) {
        if (!forceRefresh && cachedHiddenApps != null) {
            hiddenApps.postValue(cachedHiddenApps)
            return
        }
        viewModelScope.launch {
            val list = getAppsList(appContext, prefs, includeRegularApps = false, includeHiddenApps = true)
            cachedHiddenApps = list
            hiddenApps.postValue(list)
        }
    }

    fun launchApp(appModel: AppModel.App) {
        val launcher = appContext.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val componentName = ComponentName(appModel.appPackage, appModel.activityClassName.toString())
        
        EventLogger.log(appContext, LogEvent.AppLaunched(
            packageName = appModel.appPackage,
            activity = appModel.activityClassName,
            userHandle = appModel.user.toString(),
            renamedLabelUsed = prefs.getAppRenameLabel(appModel.appPackage).isNotEmpty(),
            isHidden = appModel.isHidden
        ))

        launcher.startMainActivity(componentName, appModel.user, null, null)
    }

    fun checkIsCrimsonDefault() {
        isCrimsonDefault.postValue(isCrimsonDefaultHelper(appContext))
    }

    fun updateHomeAlignment(alignment: Int) {
        prefs.homeAlignment = alignment
        homeAppAlignment.postValue(alignment)
        EventLogger.log(appContext, LogEvent.SettingsChanged("home_alignment", alignment))
    }

    fun getTodayScreenTime() {
        viewModelScope.launch(Dispatchers.IO) {
            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfDay = cal.timeInMillis
            
            val eventLog = EventLogWrapper(appContext)
            val result = eventLog.getUsageStatsResult(startOfDay, System.currentTimeMillis())
            
            prefs.screenTimeLastUpdated = System.currentTimeMillis()
            val formattedTime = appContext.formattedTimeSpent(result.totalTime)
            val displayValue = "$formattedTime   [${result.unlocks}]"
            screenTimeValue.postValue(displayValue)
        }
    }
}
