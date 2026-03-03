package app.olauncher.helper

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Build
import android.os.UserHandle
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import app.olauncher.BuildConfig
import app.olauncher.R
import app.olauncher.data.AppModel
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoTemplate
import app.olauncher.data.TodoTemplateItem
import app.olauncher.data.TodoTemplateWithItems
import app.olauncher.data.TodoType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator

fun Context.showToast(message: String?, duration: Int = Toast.LENGTH_SHORT) {
    if (message.isNullOrBlank()) return
    Toast.makeText(this, message, duration).show()
}

fun Context.showToast(stringResource: Int, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, getString(stringResource), duration).show()
}

suspend fun getAppsList(
    context: Context,
    prefs: Prefs,
    includeRegularApps: Boolean = true,
    includeHiddenApps: Boolean = false,
): MutableList<AppModel> {
    return withContext(Dispatchers.IO) {
        val appList: MutableList<AppModel> = mutableListOf()

        try {
            if (!prefs.hiddenAppsUpdated) upgradeHiddenApps(prefs)
            val hiddenApps = prefs.hiddenApps

            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val launcherApps =
                context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val collator = Collator.getInstance()

            for (profile in userManager.userProfiles) {
                for (app in launcherApps.getActivityList(null, profile)) {
                    val appLabelShown = prefs.getAppRenameLabel(app.applicationInfo.packageName)
                        .ifBlank { app.label.toString() }
                    val appModel = AppModel.App(
                        appLabel = appLabelShown,
                        key = collator.getCollationKey(app.label.toString()),
                        appPackage = app.applicationInfo.packageName,
                        activityClassName = app.componentName.className,
                        isNew = (System.currentTimeMillis() - app.firstInstallTime) < Constants.ONE_HOUR_IN_MILLIS,
                        user = profile
                    )

                    // if the current app is not Crimson
                    if (app.applicationInfo.packageName != BuildConfig.APPLICATION_ID) {
                        // is this a hidden app?
                        if (hiddenApps.contains(app.applicationInfo.packageName + "|" + profile.toString())) {
                            if (includeHiddenApps) {
                                appList.add(appModel)
                            }
                        } else {
                            // this is a regular app
                            if (includeRegularApps) {
                                appList.add(appModel)
                            }
                        }
                    }
                }
            }

            // Add shortcuts if we're getting regular apps
            if (includeRegularApps && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val pinned = try {
                    getPinnedShortcuts(context, prefs, collator)
                } catch (_: Exception) {
                    emptyList()
                }
                appList.addAll(pinned)
            }

            appList.sortWith(compareBy(collator) { it.appLabel })
        } catch (e: Exception) {
            e.printStackTrace()
        }
        appList
    }
}

@RequiresApi(Build.VERSION_CODES.O)
private suspend fun getPinnedShortcuts(
    context: Context,
    prefs: Prefs,
    collator: Collator,
): List<AppModel.PinnedShortcut> =
    withContext(Dispatchers.IO) {
        val pinnedShortcuts = mutableListOf<AppModel.PinnedShortcut>()
        val shortcuts = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
        if (shortcuts?.hasShortcutHostPermission() == true) {
            val query = LauncherApps.ShortcutQuery().apply {
                setPackage(null)
                setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            }
            shortcuts.profiles.forEach { profile ->
                try {
                    shortcuts.getShortcuts(query, profile)?.forEach { shortcut ->
                        if (shortcut.isPinned && pinnedShortcuts.none { it.shortcutId == shortcut.id }) {
                            val label = prefs.getAppRenameLabel(shortcut.id)
                                .takeIf { it.isNotBlank() }
                                ?: shortcut.shortLabel?.toString()
                                ?: shortcut.longLabel?.toString().orEmpty()
                            pinnedShortcuts.add(
                                AppModel.PinnedShortcut(
                                    appLabel = label,
                                    key = collator.getCollationKey(label),
                                    appPackage = shortcut.`package`,
                                    shortcutId = shortcut.id,
                                    isNew = false,
                                    user = profile
                                )
                            )
                        }
                    }
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
        }
        pinnedShortcuts
    }

private fun upgradeHiddenApps(prefs: Prefs) {
    val hiddenAppsSet = prefs.hiddenApps
    val newHiddenAppsSet = mutableSetOf<String>()
    for (hiddenPackage in hiddenAppsSet) {
        if (hiddenPackage.contains("|")) newHiddenAppsSet.add(hiddenPackage)
        else newHiddenAppsSet.add(hiddenPackage + android.os.Process.myUserHandle().toString())
    }
    prefs.hiddenApps = newHiddenAppsSet
    prefs.hiddenAppsUpdated = true
}

fun isCrimsonDefault(context: Context): Boolean {
    val launcherPackageName = getDefaultLauncherPackage(context)
    return BuildConfig.APPLICATION_ID == launcherPackageName
}

fun getDefaultLauncherPackage(context: Context): String {
    val intent = Intent(Intent.ACTION_MAIN)
    intent.addCategory(Intent.CATEGORY_HOME)
    val packageManager = context.packageManager
    val result = packageManager.resolveActivity(intent, 0)
    return result?.activityInfo?.packageName ?: "android"
}

fun openAppInfo(context: Context, userHandle: UserHandle, packageName: String) {
    val launcher = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    val intent: Intent? = context.packageManager.getLaunchIntentForPackage(packageName)

    intent?.let {
        launcher.startAppDetailsActivity(intent.component!!, userHandle, null, null)
    } ?: context.showToast(context.getString(R.string.unable_to_open_app))
}

fun openSearch(context: Context) {
    val intent = Intent(Intent.ACTION_WEB_SEARCH)
    intent.putExtra(SearchManager.QUERY, "")
    context.startActivity(intent)
}

@SuppressLint("WrongConstant", "PrivateApi")
fun expandNotificationDrawer(context: Context) {
    // Source: https://stackoverflow.com/a/51132142
    try {
        val statusBarService = context.getSystemService(Context.STATUS_BAR_SERVICE)
        val statusBarManager = Class.forName("android.app.StatusBarManager")
        val method = statusBarManager.getMethod("expandNotificationsPanel")
        method.invoke(statusBarService)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

fun openAlarmApp(context: Context) {
    try {
        val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        context.startActivity(intent)
    } catch (e: Exception) {
        Log.d("TAG", e.toString())
    }
}

fun openCalendar(context: Context) {
    try {
        val calendarUri = CalendarContract.CONTENT_URI
            .buildUpon()
            .appendPath("time")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, calendarUri))
    } catch (_: Exception) {
        try {
            val intent = Intent(Intent.ACTION_MAIN)
            intent.addCategory(Intent.CATEGORY_APP_CALENDAR)
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun Context.isDarkThemeOn(): Boolean {
    return resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES
}

fun Context.openUrl(url: String) {
    if (url.isEmpty()) return
    val intent = Intent(Intent.ACTION_VIEW)
    intent.data = url.toUri()
    startActivity(intent)
}

fun Context.isSystemApp(packageName: String): Boolean {
    if (packageName.isBlank()) return true
    return try {
        val packageManager = this.packageManager
        val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
        ((applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                || (applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0))
    } catch (e: Exception) {
        e.printStackTrace()
        false
    }
}

fun Context.uninstall(packageName: String) {
    val intent = Intent(Intent.ACTION_DELETE)
    intent.data = "package:$packageName".toUri()
    startActivity(intent)
}

@RequiresApi(Build.VERSION_CODES.N_MR1)
fun Context.deletePinnedShortcut(packageName: String, shortcutIdToDelete: String, user: UserHandle) {
    val launcherApps = getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps

    // 1. Query for existing pinned shortcuts for the package
    val query = LauncherApps.ShortcutQuery().apply {
        setPackage(null)
        // Query only for pinned shortcuts
        setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
    }

    try {
        val pinnedShortcuts = launcherApps.getShortcuts(query, user)

        if (pinnedShortcuts != null) {
            // 2. Filter out the shortcut to be deleted
            val updatedPinnedIds = pinnedShortcuts
                .filter { it.id != shortcutIdToDelete }
                .map { it.id }

            // 3. Re-pin the remaining shortcuts
            // This replaces the existing set of pinned shortcuts for this package
            launcherApps.pinShortcuts(packageName, updatedPinnedIds, user)
        }
    } catch (e: Exception) {
        Log.e("ShortcutHelper", "Failed to modify pinned shortcuts for $packageName", e)
    }
}

fun generateBackupJson(context: Context, items: List<TodoItem>, templates: List<TodoTemplateWithItems> = emptyList()): String {
    Log.d("BackupLog", "generateBackupJson started with ${items.size} items and ${templates.size} templates")
    return try {
        val root = JSONObject()
        root.put("version", 2)
        root.put("timestamp", System.currentTimeMillis())

        // Export Settings from SharedPreferences
        val prefs = context.getSharedPreferences("app.olauncher", Context.MODE_PRIVATE)
        val settingsJson = JSONObject()
        val allPrefs = prefs.all
        Log.d("BackupLog", "Backing up ${allPrefs.size} prefs")

        // Exclude specific local/temporary preferences from backup
        val keysToExclude = setOf("ACTIVE_BOILER_ID", "LAUNCHER_RECREATE_TIMESTAMP", "SCREEN_TIME_LAST_UPDATED", "SHOWN_ON_DAY_OF_YEAR")

        allPrefs.forEach { (key, value) ->
            if (key in keysToExclude) return@forEach
            runCatching {
                when (value) {
                    is Set<*> -> {
                        val array = JSONArray()
                        value.forEach { if (it != null) array.put(it) }
                        settingsJson.put(key, array)
                    }
                    is Float -> settingsJson.put(key, value.toDouble())
                    null -> settingsJson.put(key, JSONObject.NULL)
                    else -> settingsJson.put(key, value)
                }
            }.onFailure { Log.e("BackupLog", "Skipping setting '$key'", it) }
        }
        root.put("settings", settingsJson)

        // Export Todo Items
        val todoArray = JSONArray()
        items.forEach { item ->
            runCatching {
                todoArray.put(serializeTodoItem(item))
            }.onFailure { Log.e("BackupLog", "Skipping todo id=${item.id}", it) }
        }
        root.put("todo_items", todoArray)

        // Export Templates
        val templatesArray = JSONArray()
        templates.forEach { templateWithItems ->
            runCatching {
                val templateJson = JSONObject().apply {
                    put("name", templateWithItems.template.name)
                    put("createdAt", templateWithItems.template.createdAt)
                    val itemsArray = JSONArray()
                    templateWithItems.items.forEach { item ->
                        itemsArray.put(serializeTemplateItem(item))
                    }
                    put("items", itemsArray)
                }
                templatesArray.put(templateJson)
            }.onFailure { Log.e("BackupLog", "Skipping template ${templateWithItems.template.name}", it) }
        }
        root.put("todo_templates", templatesArray)

        val jsonString = root.toString(4)
        Log.d("BackupLog", "JSON generation complete. Length: ${jsonString.length}")
        jsonString
    } catch (e: Exception) {
        Log.e("BackupLog", "Error generating backup JSON", e)
        val fallback = JSONObject().apply {
            put("version", 2)
            put("timestamp", System.currentTimeMillis())
            put("error", e.message)
        }.toString()
        Log.d("BackupLog", "Returning fallback JSON: $fallback")
        fallback
    }
}

private fun serializeTodoItem(item: TodoItem): JSONObject {
    return JSONObject().apply {
        put("task", item.task)
        put("type", item.type.name)
        put("isCompleted", item.isCompleted)
        put("daysOfWeek", item.daysOfWeek ?: JSONObject.NULL)
        put("dueDate", item.dueDate ?: JSONObject.NULL)
        put("time", item.time ?: JSONObject.NULL)
        put("completedAt", item.completedAt ?: JSONObject.NULL)
        put("toDate", item.toDate ?: JSONObject.NULL)
        put("toTime", item.toTime ?: JSONObject.NULL)
        put("originTemplateId", item.originTemplateId ?: JSONObject.NULL)
        put("originTemplateItemId", item.originTemplateItemId ?: JSONObject.NULL)
    }
}

private fun serializeTemplateItem(item: TodoTemplateItem): JSONObject {
    return JSONObject().apply {
        put("task", item.task)
        put("type", item.type.name)
        put("daysOfWeek", item.daysOfWeek ?: JSONObject.NULL)
        put("dueDate", item.dueDate ?: JSONObject.NULL)
        put("time", item.time ?: JSONObject.NULL)
        put("toDate", item.toDate ?: JSONObject.NULL)
        put("toTime", item.toTime ?: JSONObject.NULL)
    }
}

fun parseBackupJson(jsonString: String): Triple<Map<String, Any>?, List<TodoItem>?, List<TodoTemplateWithItems>?> {
    return try {
        val root = JSONObject(jsonString)
        val settingsMap = mutableMapOf<String, Any>()
        if (root.has("settings")) {
            val settingsJson = root.getJSONObject("settings")
            val keys = settingsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = settingsJson.get(key)
                if (value is JSONArray) {
                    val set = mutableSetOf<String>()
                    for (i in 0 until value.length()) {
                        set.add(value.getString(i))
                    }
                    settingsMap[key] = set
                } else if (value != JSONObject.NULL) {
                    settingsMap[key] = value
                }
            }
        }

        val items = mutableListOf<TodoItem>()
        if (root.has("todo_items")) {
            val todoArray = root.getJSONArray("todo_items")
            for (i in 0 until todoArray.length()) {
                items.add(parseTodoItem(todoArray.getJSONObject(i)))
            }
        }

        val templates = mutableListOf<TodoTemplateWithItems>()
        if (root.has("todo_templates")) {
            val templatesArray = root.getJSONArray("todo_templates")
            for (i in 0 until templatesArray.length()) {
                val templateObj = templatesArray.getJSONObject(i)
                val template = TodoTemplate(
                    name = templateObj.getString("name"),
                    createdAt = templateObj.optLong("createdAt", System.currentTimeMillis())
                )
                val itemsArray = templateObj.getJSONArray("items")
                val templateItems = mutableListOf<TodoTemplateItem>()
                for (j in 0 until itemsArray.length()) {
                    templateItems.add(parseTemplateItem(itemsArray.getJSONObject(j)))
                }
                templates.add(TodoTemplateWithItems(template, templateItems))
            }
        }

        Triple(settingsMap, items, templates)
    } catch (_: Exception) {
        try {
            val items = mutableListOf<TodoItem>()
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                items.add(parseTodoItem(jsonArray.getJSONObject(i)))
            }
            Triple(null, items, null)
        } catch (e2: Exception) {
            Log.e("Backup", "Error importing data", e2)
            Triple(null, null, null)
        }
    }
}

private fun parseTodoItem(jsonObject: JSONObject): TodoItem {
    val typeString = jsonObject.optString("type", TodoType.TIMELESS.name)
    val type = runCatching { TodoType.valueOf(typeString) }.getOrDefault(TodoType.TIMELESS)

    return TodoItem(
        task = jsonObject.optString("task", ""),
        type = type,
        isCompleted = jsonObject.optBoolean("isCompleted", false),
        daysOfWeek = if (jsonObject.isNull("daysOfWeek")) null else jsonObject.optString("daysOfWeek"),
        dueDate = if (jsonObject.isNull("dueDate")) null else jsonObject.optLong("dueDate"),
        time = if (jsonObject.isNull("time")) null else jsonObject.optString("time"),
        completedAt = if (jsonObject.isNull("completedAt")) null else jsonObject.optLong("completedAt"),
        toDate = if (jsonObject.isNull("toDate")) null else jsonObject.optLong("toDate"),
        toTime = if (jsonObject.isNull("toTime")) null else jsonObject.optString("toTime"),
        originTemplateId = if (jsonObject.isNull("originTemplateId")) null else jsonObject.optLong("originTemplateId"),
        originTemplateItemId = if (jsonObject.isNull("originTemplateItemId")) null else jsonObject.optLong("originTemplateItemId")
    )
}

private fun parseTemplateItem(jsonObject: JSONObject): TodoTemplateItem {
    val typeString = jsonObject.optString("type", TodoType.TIMELESS.name)
    val type = runCatching { TodoType.valueOf(typeString) }.getOrDefault(TodoType.TIMELESS)

    return TodoTemplateItem(
        templateId = 0,
        task = jsonObject.optString("task", ""),
        type = type,
        daysOfWeek = if (jsonObject.isNull("daysOfWeek")) null else jsonObject.optString("daysOfWeek"),
        dueDate = if (jsonObject.isNull("dueDate")) null else jsonObject.optLong("dueDate"),
        time = if (jsonObject.isNull("time")) null else jsonObject.optString("time"),
        toDate = if (jsonObject.isNull("toDate")) null else jsonObject.optLong("toDate"),
        toTime = if (jsonObject.isNull("toTime")) null else jsonObject.optString("toTime")
    )
}
