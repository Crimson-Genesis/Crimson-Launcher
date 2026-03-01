package app.olauncher

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.findNavController
import app.olauncher.data.AppDatabase
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.TodoType
import app.olauncher.databinding.ActivityMainBinding
import app.olauncher.helper.DaySummary
import app.olauncher.helper.EventLogger
import app.olauncher.helper.LogEvent
import app.olauncher.helper.generateBackupJson
import app.olauncher.helper.isDefaultLauncher
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.parseBackupJson
import app.olauncher.helper.resetLauncherViaFakeActivity
import app.olauncher.helper.showLauncherSelector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.util.Random

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var navController: NavController
    private lateinit var viewModel: MainViewModel
    private lateinit var binding: ActivityMainBinding

    private var isPickerActive = false

    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        isPickerActive = false
        uri ?: return@registerForActivityResult
        Log.d("Backup", "Activity launcher callback (backup): $uri")
        performBackupDirectly(uri)
    }

    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        isPickerActive = false
        uri ?: return@registerForActivityResult
        Log.d("Backup", "Activity launcher callback (restore): $uri")
        performRestoreDirectly(uri)
    }

    private val logFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        isPickerActive = false
        uri ?: return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        val old = prefs.logFolderUri
        prefs.logFolderUri = uri.toString()
        EventLogger.log(this, LogEvent.LogFolderChanged(old, uri.toString()))
    }

    fun launchLogFolderPicker() {
        isPickerActive = true
        logFolderLauncher.launch(null)
    }

    private fun performBackupDirectly(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val items = viewModel.getAllTodoItemsSync()
                val templates = AppDatabase.getDatabase(this@MainActivity).todoTemplateDao().getAllTemplatesWithItemsSync()
                Log.d("Backup", "Direct backup: ${items.size} items, ${templates.size} templates")
                val json = generateBackupJson(this@MainActivity, items, templates)
                Log.d("Backup", "Direct backup JSON length: ${json.length}")
                val bytes = json.toByteArray(StandardCharsets.UTF_8)
                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(bytes)
                    output.flush()
                    Log.d("Backup", "Direct backup written successfully")
                }
                EventLogger.log(this@MainActivity, LogEvent.BackupCreated(bytes.size.toLong()))
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Backup saved!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("Backup", "Direct backup failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun performRestoreDirectly(uri: Uri) {
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                val json = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (json.isNullOrBlank()) {
                    Toast.makeText(this@MainActivity, "File is empty or could not be read", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val (settings, items, templates) = parseBackupJson(json)

                if (settings == null && items == null && templates == null) {
                    Toast.makeText(this@MainActivity, "Invalid backup file", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                settings?.let { applySettings(it) }

                if (!items.isNullOrEmpty()) {
                    viewModel.replaceTodoItems(items)
                }

                if (!templates.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        val dao = AppDatabase.getDatabase(this@MainActivity).todoTemplateDao()
                        dao.deleteAllTemplates()
                        templates.forEach { templateWithItems ->
                            dao.insertTemplateWithItems(templateWithItems.template, templateWithItems.items)
                        }
                        
                        // Reset activeBoilerId to avoid stale reference post-restore
                        // We set it to -1 so MainViewModel can re-initialize it correctly from Default
                        prefs.activeBoilerId = -1L
                        prefs.activeBoilerName = "Default"
                    }
                }
                
                EventLogger.log(this@MainActivity, LogEvent.RestorePerformed(items?.size ?: 0))

                Toast.makeText(this@MainActivity, "Restore complete.", Toast.LENGTH_SHORT).show()
                recreate()
            } catch (e: Exception) {
                Log.e("Restore", "Restore error", e)
                Toast.makeText(this@MainActivity, "Restore failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applySettings(settings: Map<String, Any>) {
        val sharedPrefs = getSharedPreferences("app.olauncher", MODE_PRIVATE)
        sharedPrefs.edit {
            settings.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Float -> putFloat(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is String -> putString(key, value)
                    is Double -> putFloat(key, value.toFloat())
                    is Set<*> -> @Suppress("UNCHECKED_CAST") putStringSet(key, value as Set<String>)
                }
            }
        }
    }

    fun launchBackupPicker(filename: String) {
        isPickerActive = true
        backupLauncher.launch(filename)
    }

    fun launchRestorePicker() {
        isPickerActive = true
        restoreLauncher.launch(arrayOf("application/json"))
    }

    override fun attachBaseContext(context: Context) {
        val newConfig = Configuration(context.resources.configuration)
        newConfig.fontScale = Prefs(context).textSizeScale
        applyOverrideConfiguration(newConfig)
        super.attachBaseContext(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        if (isEinkDisplay()) prefs.appTheme = AppCompatDelegate.MODE_NIGHT_NO
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        navController = findNavController(R.id.nav_host_fragment)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (navController.currentDestination?.id != R.id.mainFragment) {
                    navController.popBackStack()
                } else {
                    binding.messageLayout.visibility = View.GONE
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        if (prefs.firstOpen) {
            viewModel.firstOpen(true)
            prefs.firstOpen = false
            prefs.firstOpenTime = System.currentTimeMillis()
            viewModel.resetLauncherLiveData.postValue(Unit)
        }

        initClickListeners()
        initObservers(viewModel)
        viewModel.getAppList(false)

        window.addFlags(FLAG_LAYOUT_NO_LIMITS)

        EventLogger.log(this, LogEvent.AppOpened)

        checkMidnightUpdate()
    }

    private fun getLogicalDayKey(timestamp: Long): String {
        val resetMinutes = prefs.resetTimeMinutes
        val cal = Calendar.getInstance()
        cal.timeInMillis = timestamp
        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (currentMinutes < resetMinutes) {
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return String.format(Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    private fun checkMidnightUpdate() {
        val currentLogicalDay = getLogicalDayKey(System.currentTimeMillis())
        
        // Initial setup or migration
        if (prefs.lastResetDayKey == null) {
            prefs.lastResetDayKey = currentLogicalDay
            // Also update the old shownOnDayOfYear for consistency if needed,
            // but we'll use lastResetDayKey from now on.
            return
        }

        if (prefs.lastResetDayKey != currentLogicalDay) {
            val previousLogicalDay = prefs.lastResetDayKey!!
            Log.d("Midnight", "New logical day detected: $currentLogicalDay (previously $previousLogicalDay). Resetting daily tasks.")
            
            lifecycleScope.launch(Dispatchers.IO) {
                val allItems = viewModel.getAllTodoItemsSync()
                
                val completedYesterdayCount = allItems.count { it.isCompleted && it.completedAt != null && getLogicalDayKey(it.completedAt) == previousLogicalDay }
                val totalYesterday = allItems.count { 
                    it.type == TodoType.DAILY || (it.type == TodoType.TIMED && it.dueDate != null && getLogicalDayKey(it.dueDate) == previousLogicalDay)
                }
                val dailyTasks = allItems.filter { it.type == TodoType.DAILY }
                val dailyCompleted = dailyTasks.count { it.isCompleted }
                val timedOverdue = allItems.count { 
                    it.type == TodoType.TIMED && !it.isCompleted && (it.dueDate ?: 0) < System.currentTimeMillis() && getLogicalDayKey(it.dueDate ?: 0) != previousLogicalDay && getLogicalDayKey(it.dueDate ?: 0) != currentLogicalDay
                }

                if (completedYesterdayCount > 0) {
                    prefs.currentStreakDays++
                    prefs.lastCompletionDate = previousLogicalDay
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

                EventLogger.log(this@MainActivity, LogEvent.DayReset(summary))
                
                prefs.dailyStatsAddedCount = 0
                prefs.dailyStatsDeletedCount = 0
                viewModel.resetDailyTasks()
                prefs.lastResetDayKey = currentLogicalDay
                // Update legacy pref for backward compatibility if any other part of app uses it
                prefs.shownOnDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
            }
        }
    }

    private fun isYesterday(timestamp: Long?): Boolean {
        if (timestamp == null) return false
        val yesterday = Calendar.getInstance()
        yesterday.add(Calendar.DAY_OF_YEAR, -1)
        val date = Calendar.getInstance()
        date.timeInMillis = timestamp
        return yesterday.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)
    }

    private fun isToday(timestamp: Long?): Boolean {
        if (timestamp == null) return false
        val today = Calendar.getInstance()
        val date = Calendar.getInstance()
        date.timeInMillis = timestamp
        return today.get(Calendar.YEAR) == date.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == date.get(Calendar.DAY_OF_YEAR)
    }

    override fun onResume() {
        super.onResume()
        checkMidnightUpdate()
    }

    override fun onStop() {
        if (!isPickerActive) backToHomeScreen()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        backToHomeScreen()
        super.onUserLeaveHint()
    }

    override fun onNewIntent(intent: Intent?) {
        backToHomeScreen()
        super.onNewIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppCompatDelegate.setDefaultNightMode(prefs.appTheme)
    }

    private fun initClickListeners() {
        binding.ivClose.setOnClickListener { 
            binding.messageLayout.visibility = View.GONE
        }
    }

    private fun initObservers(viewModel: MainViewModel) {
        viewModel.launcherResetFailed.observe(this) {
            openLauncherChooser(it)
        }
        viewModel.resetLauncherLiveData.observe(this) {
            if (isDefaultLauncher() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
                resetLauncherViaFakeActivity()
            else
                showLauncherSelector(Constants.REQUEST_CODE_LAUNCHER_SELECTOR)
        }
        viewModel.showDialog.observe(this) {
            when (it) {
                Constants.Dialog.HIDDEN -> {
                    showMessageDialog(R.string.hidden_apps, R.string.hidden_apps_message, R.string.okay) {
                    }
                }

                Constants.Dialog.KEYBOARD -> {
                    showMessageDialog(R.string.app_name, R.string.keyboard_message, R.string.okay) {
                    }
                }

                Constants.Dialog.DIGITAL_WELLBEING -> {
                    showMessageDialog(R.string.screen_time, R.string.app_usage_message, R.string.permission) {
                        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                }
            }
        }
    }

    private fun showMessageDialog(title: Int, message: Int, action: Int, clickListener: () -> Unit) {
        binding.tvTitle.text = getString(title)
        binding.tvMessage.text = getString(message)
        binding.tvAction.text = getString(action)
        binding.tvAction.setOnClickListener { 
            clickListener()
            binding.messageLayout.visibility = View.GONE
        }
        binding.messageLayout.visibility = View.VISIBLE
    }

    private fun backToHomeScreen() {
        binding.messageLayout.visibility = View.GONE
        if (navController.currentDestination?.id != R.id.mainFragment)
            navController.popBackStack(R.id.mainFragment, false)
    }

    private fun openLauncherChooser(resetFailed: Boolean) {
        if (resetFailed) {
            val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(intent)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            Constants.REQUEST_CODE_LAUNCHER_SELECTOR -> {
                if (resultCode == RESULT_OK)
                    resetLauncherViaFakeActivity()
            }
        }
    }

    companion object {
        val sessionId: String by lazy {
            val chars = "0123456789abcdef"
            val random = Random()
            (1..6).map { chars[random.nextInt(chars.length)] }.joinToString("")
        }
    }
}
