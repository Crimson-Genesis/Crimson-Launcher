package app.olauncher.helper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import app.olauncher.data.AppDatabase
import app.olauncher.data.Prefs
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoTemplateWithItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object BackupManager {
    private const val TAG = "BackupManager"
    private const val CONFIG_FILE_NAME = "backup.conf"
    private const val CHANNEL_ID = "backup_channel"
    private const val NOTIFICATION_ID = 2001
    private const val ACTION_CANCEL = "app.olauncher.ACTION_BACKUP_CANCEL"

    @Volatile
    private var isCancelled = false

    suspend fun performBackup(context: Context, backupUri: Uri): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)
        
        val cancelIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_CANCEL).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CANCEL) {
                    isCancelled = true
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_CANCEL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(ACTION_CANCEL))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Crimson Backup")
            .setContentText("Creating backup ZIP...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        notificationManager.notify(NOTIFICATION_ID, builder.build())

        try {
            val prefs = Prefs(context)
            val db = AppDatabase.getDatabase(context)
            val todoItems = db.todoItemDao().getAllTodoItemsSync()
            val templates = db.todoTemplateDao().getAllTemplatesWithItemsSync()

            val storageUriStr = prefs.storageFolderUri
            val rootTree = if (!storageUriStr.isNullOrEmpty()) {
                DocumentFile.fromTreeUri(context, Uri.parse(storageUriStr))
            } else null

            // 1. Generate Metadata
            val configJson = generateConfigJson(context, prefs, todoItems, templates, rootTree)
            
            // 2. Start Zipping
            context.contentResolver.openOutputStream(backupUri)?.use { outputStream ->
                ZipOutputStream(BufferedOutputStream(outputStream)).use { zipOut ->
                    
                    // Add config file
                    val configEntry = ZipEntry(CONFIG_FILE_NAME)
                    zipOut.putNextEntry(configEntry)
                    zipOut.write(configJson.toByteArray(StandardCharsets.UTF_8))
                    zipOut.closeEntry()

                    // Add storage folder contents
                    rootTree?.let { tree ->
                        zipDocumentFile(context, tree, "", zipOut, backupUri)
                    }
                }
            }
            context.unregisterReceiver(receiver)
            notificationManager.cancel(NOTIFICATION_ID)
            
            if (!isCancelled) {
                showCompletionNotification(context, notificationManager, "Backup Complete", "Your data has been backed up successfully.")
            }
            !isCancelled
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            try { context.unregisterReceiver(receiver) } catch (ignore: Exception) {}
            notificationManager.cancel(NOTIFICATION_ID)
            false
        }
    }

    private fun zipDocumentFile(context: Context, docFile: DocumentFile, path: String, zipOut: ZipOutputStream, backupUri: Uri) {
        if (isCancelled) return
        
        // EXCLUDE the backup file itself if it's in the storage folder
        if (docFile.uri == backupUri) {
            Log.d(TAG, "Skipping backup file itself by URI: ${docFile.name}")
            return
        }
        
        val fileName = docFile.name
        if (!docFile.isDirectory && fileName != null && backupUri.path?.endsWith(fileName) == true) {
             Log.d(TAG, "Skipping backup file itself by Name: $fileName")
             return
        }

        if (docFile.isDirectory) {
            val children = docFile.listFiles()
            for (child in children) {
                val childName = child.name ?: continue
                val newPath = if (path.isEmpty()) childName else "$path/$childName"
                zipDocumentFile(context, child, newPath, zipOut, backupUri)
            }
        } else {
            try {
                val entry = ZipEntry(path)
                zipOut.putNextEntry(entry)
                context.contentResolver.openInputStream(docFile.uri)?.use { input ->
                    input.copyTo(zipOut)
                }
                zipOut.closeEntry()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to zip file: $path", e)
            }
        }
    }

    private fun generateConfigJson(
        context: Context,
        prefs: Prefs,
        items: List<TodoItem>,
        templates: List<TodoTemplateWithItems>,
        rootTree: DocumentFile?
    ): String {
        val root = JSONObject()
        root.put("version", 4)
        root.put("timestamp", System.currentTimeMillis())
        root.put("storage_folder_uri", prefs.storageFolderUri)

        // Settings
        val allPrefs = context.getSharedPreferences("app.olauncher", Context.MODE_PRIVATE).all
        val settingsJson = JSONObject()
        val keysToExclude = setOf("ACTIVE_BOILER_ID", "LAUNCHER_RECREATE_TIMESTAMP", "SCREEN_TIME_LAST_UPDATED", "SHOWN_ON_DAY_OF_YEAR")
        allPrefs.forEach { (key, value) ->
            if (key in keysToExclude) return@forEach
            when (value) {
                is Set<*> -> {
                    val array = JSONArray()
                    value.forEach { if (it != null) array.put(it) }
                    settingsJson.put(key, array)
                }
                is Float -> settingsJson.put(key, value.toDouble())
                else -> settingsJson.put(key, value ?: JSONObject.NULL)
            }
        }
        root.put("settings", settingsJson)

        // Todo Items
        val todoArray = JSONArray()
        items.forEach { todoArray.put(serializeTodoItem(it)) }
        root.put("todo_items", todoArray)

        // Templates
        val templatesArray = JSONArray()
        templates.forEach { t ->
            templatesArray.put(JSONObject().apply {
                put("name", t.template.name)
                put("createdAt", t.template.createdAt)
                val itemsArr = JSONArray()
                t.items.forEach { itemsArr.put(serializeTemplateItem(it)) }
                put("items", itemsArr)
            })
        }
        root.put("todo_templates", templatesArray)

        // Chat Metadata
        val chatMetadata = JSONObject()
        var totalChats = 0
        val daysArray = JSONArray()

        val chatDir = rootTree?.findFile("chat")
        chatDir?.listFiles()?.forEach { dateDir ->
            if (dateDir.isDirectory) {
                val dayMeta = JSONObject()
                dayMeta.put("date", dateDir.name)
                
                var msgCount = 0
                var imgCount = 0
                var vidCount = 0
                var audCount = 0
                var totalSize = 0L

                // Walk day directory to count files and sizes
                fun walk(file: DocumentFile) {
                    if (file.isDirectory) {
                        file.listFiles().forEach { walk(it) }
                    } else {
                        totalSize += file.length()
                        val mimeType = file.type
                        when {
                            file.name == "chat.json" -> { /* chat file itself */ }
                            mimeType?.startsWith("image/") == true -> imgCount++
                            mimeType?.startsWith("video/") == true -> vidCount++
                            mimeType?.startsWith("audio/") == true -> audCount++
                        }
                    }
                }
                walk(dateDir)
                
                // Try to get message count from chat.json if it exists
                dateDir.findFile("chat.json")?.let { chatFile ->
                    try {
                        context.contentResolver.openInputStream(chatFile.uri)?.bufferedReader()?.use {
                            val arr = JSONArray(it.readText())
                            msgCount = arr.length()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read chat.json for metadata", e)
                    }
                }

                dayMeta.put("message_count", msgCount)
                dayMeta.put("image_count", imgCount)
                dayMeta.put("video_count", vidCount)
                dayMeta.put("audio_count", audCount)
                dayMeta.put("total_size_bytes", totalSize)
                
                daysArray.put(dayMeta)
                totalChats += msgCount
            }
        }
        chatMetadata.put("total_chats", totalChats)
        chatMetadata.put("days", daysArray)
        root.put("chat_metadata", chatMetadata)

        return root.toString(4)
    }

    private fun serializeTodoItem(item: TodoItem): JSONObject = JSONObject().apply {
        put("task", item.task)
        put("type", item.type.name)
        put("isCompleted", item.isCompleted)
        put("daysOfWeek", item.daysOfWeek ?: JSONObject.NULL)
        put("dueDate", item.dueDate ?: JSONObject.NULL)
        put("time", item.time ?: JSONObject.NULL)
        put("completedAt", item.completedAt ?: JSONObject.NULL)
        put("toDate", item.toDate ?: JSONObject.NULL)
        put("toTime", item.toTime ?: JSONObject.NULL)
    }

    private fun serializeTemplateItem(item: app.olauncher.data.TodoTemplateItem): JSONObject = JSONObject().apply {
        put("task", item.task)
        put("type", item.type.name)
        put("daysOfWeek", item.daysOfWeek ?: JSONObject.NULL)
        put("dueDate", item.dueDate ?: JSONObject.NULL)
        put("time", item.time ?: JSONObject.NULL)
        put("toDate", item.toDate ?: JSONObject.NULL)
        put("toTime", item.toTime ?: JSONObject.NULL)
    }

    suspend fun performRestore(context: Context, backupUri: Uri): Boolean = withContext(Dispatchers.IO) {
        isCancelled = false
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel(notificationManager)

        val cancelIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_CANCEL).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_CANCEL) {
                    isCancelled = true
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, IntentFilter(ACTION_CANCEL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(ACTION_CANCEL))
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Crimson Restore")
            .setContentText("Restoring from ZIP...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)

        notificationManager.notify(NOTIFICATION_ID, builder.build())

        try {
            var configJson: String? = null
            
            // 1. First pass to find config file
            context.contentResolver.openInputStream(backupUri)?.use { input ->
                ZipInputStream(input).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        if (entry.name == CONFIG_FILE_NAME) {
                            configJson = zipIn.bufferedReader().readText()
                            break
                        }
                        entry = zipIn.nextEntry
                    }
                }
            }

            val config = configJson ?: return@withContext false
            val root = JSONObject(config)
            val prefs = Prefs(context)
            
            // 2. Restore settings
            if (root.has("settings")) {
                val settings = root.getJSONObject("settings")
                val editor = context.getSharedPreferences("app.olauncher", Context.MODE_PRIVATE).edit()
                val keys = settings.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = settings.get(key)
                    when (value) {
                        is JSONArray -> {
                            val set = mutableSetOf<String>()
                            for (i in 0 until value.length()) set.add(value.getString(i))
                            editor.putStringSet(key, set)
                        }
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Double -> editor.putFloat(key, value.toFloat())
                        is String -> editor.putString(key, value)
                    }
                }
                editor.apply()
            }

            // 3. Restore Storage Folder (and move files)
            val storageUriStr = root.optString("storage_folder_uri")
            if (storageUriStr.isNotEmpty()) {
                prefs.storageFolderUri = storageUriStr
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(storageUriStr))
                if (rootTree != null && rootTree.exists()) {
                    // Extract files from zip to storage folder
                    context.contentResolver.openInputStream(backupUri)?.use { input ->
                        ZipInputStream(input).use { zipIn ->
                            var entry = zipIn.nextEntry
                            while (entry != null) {
                                if (isCancelled) break
                                if (entry.name != CONFIG_FILE_NAME && !entry.isDirectory) {
                                    extractFile(context, rootTree, entry.name, zipIn)
                                }
                                entry = zipIn.nextEntry
                            }
                        }
                    }
                }
            }

            // 4. Restore Todo Data
            val db = AppDatabase.getDatabase(context)
            if (!isCancelled && root.has("todo_items")) {
                val arr = root.getJSONArray("todo_items")
                val items = mutableListOf<TodoItem>()
                for (i in 0 until arr.length()) {
                    items.add(parseTodoItem(arr.getJSONObject(i)))
                }
                db.todoItemDao().deleteAll()
                db.todoItemDao().insertAll(items)
            }
            
            if (!isCancelled && root.has("todo_templates")) {
                val arr = root.getJSONArray("todo_templates")
                db.todoTemplateDao().deleteAllTemplates()
                for (i in 0 until arr.length()) {
                    val templateObj = arr.getJSONObject(i)
                    val template = app.olauncher.data.TodoTemplate(
                        name = templateObj.getString("name"),
                        createdAt = templateObj.optLong("createdAt", System.currentTimeMillis())
                    )
                    val itemsArray = templateObj.getJSONArray("items")
                    val templateItems = mutableListOf<app.olauncher.data.TodoTemplateItem>()
                    for (j in 0 until itemsArray.length()) {
                        templateItems.add(parseTemplateItem(itemsArray.getJSONObject(j)))
                    }
                    db.todoTemplateDao().insertTemplateWithItems(template, templateItems)
                }
            }

            context.unregisterReceiver(receiver)
            notificationManager.cancel(NOTIFICATION_ID)

            if (!isCancelled) {
                showCompletionNotification(context, notificationManager, "Restore Complete", "Your data has been restored successfully.")
            }
            !isCancelled
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            try { context.unregisterReceiver(receiver) } catch (ignore: Exception) {}
            notificationManager.cancel(NOTIFICATION_ID)
            false
        }
    }

    private fun showCompletionNotification(context: Context, nm: NotificationManager, title: String, text: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        
        nm.notify(NOTIFICATION_ID + 1, builder.build())
    }

    private fun createNotificationChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Backup & Restore"
            val descriptionText = "Notifications for backup and restore progress"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun extractFile(context: Context, root: DocumentFile, entryName: String, zipIn: ZipInputStream) {
        if (isCancelled) return
        try {
            val parts = entryName.split("/")
            var current = root
            for (i in 0 until parts.size - 1) {
                val part = parts[i]
                if (part.isEmpty()) continue
                current = current.findFile(part) ?: current.createDirectory(part) ?: return
            }
            val fileName = parts.last()
            if (fileName.isEmpty()) return
            
            val existing = current.findFile(fileName)
            val file = existing ?: current.createFile("application/octet-stream", fileName) ?: return
            
            context.contentResolver.openOutputStream(file.uri)?.use { output ->
                zipIn.copyTo(output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract file: $entryName", e)
        }
    }

    private fun parseTemplateItem(json: JSONObject): app.olauncher.data.TodoTemplateItem {
        val type = app.olauncher.data.TodoType.valueOf(json.optString("type", "TIMELESS"))
        return app.olauncher.data.TodoTemplateItem(
            templateId = 0,
            task = json.getString("task"),
            type = type,
            daysOfWeek = if (json.isNull("daysOfWeek")) null else json.getString("daysOfWeek"),
            dueDate = if (json.isNull("dueDate")) null else json.getLong("dueDate"),
            time = if (json.isNull("time")) null else json.getString("time"),
            toDate = if (json.isNull("toDate")) null else json.getLong("toDate"),
            toTime = if (json.isNull("toTime")) null else json.getString("toTime")
        )
    }

    private fun parseTodoItem(json: JSONObject): TodoItem {
        val type = app.olauncher.data.TodoType.valueOf(json.optString("type", "TIMELESS"))
        return TodoItem(
            task = json.getString("task"),
            type = type,
            isCompleted = json.getBoolean("isCompleted"),
            daysOfWeek = if (json.isNull("daysOfWeek")) null else json.getString("daysOfWeek"),
            dueDate = if (json.isNull("dueDate")) null else json.getLong("dueDate"),
            time = if (json.isNull("time")) null else json.getString("time"),
            completedAt = if (json.isNull("completedAt")) null else json.getLong("completedAt"),
            toDate = if (json.isNull("toDate")) null else json.getLong("toDate"),
            toTime = if (json.isNull("toTime")) null else json.getString("toTime")
        )
    }
}
