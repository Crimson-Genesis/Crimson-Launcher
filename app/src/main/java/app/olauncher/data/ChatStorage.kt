package app.olauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ChatStorage {
    private const val TAG = "ChatStorage"
    private const val CHAT_DIR_NAME = "chat"
    private const val CHAT_FILE_NAME = "chat.json"
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fullTimestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    sealed class MediaDestination {
        data class Internal(val file: File) : MediaDestination()
        data class Saf(val uri: Uri) : MediaDestination()
    }

    private fun getSafChatDir(root: DocumentFile): DocumentFile {
        return root.findFile(CHAT_DIR_NAME) ?: root.createDirectory(CHAT_DIR_NAME) ?: root
    }

    private fun getOrCreateDir(parent: DocumentFile, path: String): DocumentFile? {
        var current = parent
        path.split("/").forEach { part ->
            if (part.isEmpty()) return@forEach
            current = current.findFile(part) ?: current.createDirectory(part) ?: return null
        }
        return current
    }

    fun getNextMediaDestination(context: Context, prefs: Prefs, type: String, extension: String, timestamp: Long = System.currentTimeMillis(), preferredFileName: String? = null): MediaDestination {
        val dateStr = fileDateFormat.format(Date(timestamp))
        val subDirName = when (type) {
            "IMAGE" -> "image"
            "VIDEO" -> "video"
            "PREVIEW" -> "preview"
            "TEMP_IMAGE" -> "temp/image"
            "TEMP_VIDEO" -> "temp/video"
            "TEMP_PREVIEW" -> "temp/preview"
            "TEMP_PREVIEW" -> "temp/preview"
            "AUDIO" -> "audio"
            "TEMP_AUDIO" -> "temp/audio"
            else -> "misc"
        }
        val fileName = preferredFileName ?: "${UUID.randomUUID()}$extension"
        val mimeType = when (type) {
            "IMAGE" -> "image/jpeg"
            "VIDEO" -> "video/mp4"
            "PREVIEW" -> "image/jpeg"
            "TEMP_IMAGE" -> "image/jpeg"
            "TEMP_VIDEO" -> "video/mp4"
            "TEMP_PREVIEW" -> "image/jpeg"
            "TEMP_PREVIEW" -> "image/jpeg"
            "AUDIO" -> "audio/mp4"
            "TEMP_AUDIO" -> "audio/mp4"
            else -> "application/octet-stream"
        }

        val uriStr = prefs.storageFolderUri
        if (!uriStr.isNullOrEmpty()) {
            try {
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                if (rootTree != null) {
                    val baseDir = getSafChatDir(rootTree)
                    
                    val dateDir = baseDir.findFile(dateStr) ?: baseDir.createDirectory(dateStr)
                    if (dateDir != null) {
                        val mediaTypeDir = getOrCreateDir(dateDir, subDirName)
                        if (mediaTypeDir != null) {
                            val docFile = mediaTypeDir.createFile(mimeType, fileName)
                            if (docFile != null) {
                                return MediaDestination.Saf(docFile.uri)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SAF destination", e)
            }
        }

        // Fallback to internal
        val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
        val dateDir = File(internalChatDir, dateStr)
        val mediaTypeDir = File(dateDir, subDirName)
        if (!mediaTypeDir.exists()) mediaTypeDir.mkdirs()
        val file = File(mediaTypeDir, fileName)
        return MediaDestination.Internal(file)
    }

    fun loadAllMessages(context: Context, prefs: Prefs): List<ChatMessage> {
        val allMessages = mutableListOf<ChatMessage>()
        
        // Load from SAF if available
        val uriStr = prefs.storageFolderUri
        if (!uriStr.isNullOrEmpty()) {
            try {
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                if (rootTree != null) {
                    val baseDir = getSafChatDir(rootTree)
                    baseDir.listFiles().forEach { dateDir ->
                        if (dateDir.isDirectory) {
                            val chatFile = dateDir.findFile(CHAT_FILE_NAME)
                            if (chatFile != null && chatFile.exists()) {
                                val content = context.contentResolver.openInputStream(chatFile.uri)?.bufferedReader()?.use { it.readText() }
                                content?.let { allMessages.addAll(parseJsonToMessages(it)) }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load chat from SAF", e)
            }
        }

        // Load from internal
        try {
            val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
            if (internalChatDir.exists()) {
                internalChatDir.listFiles()?.forEach { dateDir ->
                    if (dateDir.isDirectory) {
                        val chatFile = File(dateDir, CHAT_FILE_NAME)
                        if (chatFile.exists()) {
                            allMessages.addAll(parseJsonToMessages(chatFile.readText()))
                        }
                    } else if (dateDir.name.startsWith("chat_") && dateDir.name.endsWith(".json")) {
                        // Legacy support for chat_yyyy-MM-dd.json in chat/ folder
                        allMessages.addAll(parseJsonToMessages(dateDir.readText()))
                    }
                }
            }
            
            // Legacy single file support
            val legacyFile = File(context.filesDir, "crimson_chat.json")
            if (legacyFile.exists()) {
                allMessages.addAll(parseJsonToMessages(legacyFile.readText()))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load chat from internal", e)
        }

        return allMessages.distinctBy { it.uuid }.sortedBy { it.timestamp }
    }

    private fun parseJsonToMessages(jsonString: String): List<ChatMessage> {
        return try {
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(ChatMessage(
                    id = obj.optLong("id", 0),
                    uuid = obj.optString("uuid", ""), // Leave empty if missing to allow fallback matching
                    text = obj.getString("text"),
                    isUser = obj.getBoolean("isUser"),
                    timestamp = obj.getLong("timestamp"),
                    mediaUri = if (obj.has("mediaUri") && !obj.isNull("mediaUri")) obj.getString("mediaUri") else null,
                    mediaType = if (obj.has("mediaType") && !obj.isNull("mediaType")) obj.getString("mediaType") else null,
                    previewUri = if (obj.has("previewUri") && !obj.isNull("previewUri")) obj.getString("previewUri") else null
                ).let { 
                    // If uuid was missing in JSON, generate a stable one for this session if needed, 
                    // but better to just handle it during save.
                    if (it.uuid.isEmpty()) it.copy(uuid = UUID.nameUUIDFromBytes("${it.timestamp}_${it.text}".toByteArray()).toString())
                    else it
                })
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse chat JSON", e)
            emptyList()
        }
    }

    fun getTempMedia(context: Context, prefs: Prefs, timestamp: Long = System.currentTimeMillis()): List<Triple<String, String, String?>> {
        val dateStr = fileDateFormat.format(Date(timestamp))
        val results = mutableListOf<Triple<String, String, String?>>()

        // SAF
        val uriStr = prefs.storageFolderUri
        if (!uriStr.isNullOrEmpty()) {
            try {
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                if (rootTree != null) {
                    val baseDir = getSafChatDir(rootTree)
                    val dateDir = baseDir.findFile(dateStr)
                    val tempDir = dateDir?.findFile("temp")
                    
                    if (tempDir != null && tempDir.isDirectory) {
                        val imagesDir = tempDir.findFile("image")
                        val videosDir = tempDir.findFile("video")
                        val previewsDir = tempDir.findFile("preview")
                        val audiosDir = tempDir.findFile("audio")

                        imagesDir?.listFiles()?.forEach { file ->
                            if (file.isFile && file.name != null) {
                                val fileName = file.name!!
                                val preview = previewsDir?.findFile(fileName) ?: previewsDir?.findFile(fileName.replace(".jpg", "_preview.jpg"))
                                results.add(Triple(file.uri.toString(), "IMAGE", preview?.uri?.toString()))
                            }
                        }
                        videosDir?.listFiles()?.forEach { file ->
                            if (file.isFile && file.name != null) {
                                val fileName = file.name!!
                                val preview = previewsDir?.findFile(fileName.replace(".mp4", ".jpg"))
                                results.add(Triple(file.uri.toString(), "VIDEO", preview?.uri?.toString()))
                            }
                        }
                        audiosDir?.listFiles()?.forEach { file ->
                            if (file.isFile && file.name != null) {
                                results.add(Triple(file.uri.toString(), "AUDIO", null))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list temp media from SAF", e)
            }
        }

        // Internal
        try {
            val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
            val dateDir = File(internalChatDir, dateStr)
            val tempDir = File(dateDir, "temp")
            if (tempDir.exists() && tempDir.isDirectory) {
                val imagesDir = File(tempDir, "image")
                val videosDir = File(tempDir, "video")
                val previewsDir = File(tempDir, "preview")
                val audiosDir = File(tempDir, "audio")

                imagesDir.listFiles()?.forEach { file ->
                    val preview = File(previewsDir, file.name)
                    val previewUri = if (preview.exists()) {
                         FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", preview).toString()
                    } else null
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
                    results.add(Triple(uri, "IMAGE", previewUri))
                }
                videosDir.listFiles()?.forEach { file ->
                    val previewName = file.name.substringBeforeLast(".") + ".jpg"
                    val preview = File(previewsDir, previewName)
                    val previewUri = if (preview.exists()) {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", preview).toString()
                    } else null
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
                    results.add(Triple(uri, "VIDEO", previewUri))
                }
                audiosDir.listFiles()?.forEach { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()
                    results.add(Triple(uri, "AUDIO", null))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list internal temp media", e)
        }

        return results
    }

    fun saveMessage(context: Context, prefs: Prefs, message: ChatMessage) {
        var msgToSave = message

        // Finalize temp media if present
        if (!msgToSave.mediaUri.isNullOrEmpty()) {
            val uris = msgToSave.mediaUri!!.split("|")
            val types = msgToSave.mediaType?.split("|") ?: List(uris.size) { "IMAGE" }
            val previews = msgToSave.previewUri?.split("|") ?: List(uris.size) { "" }

            val finalizedUris = mutableListOf<String>()
            val finalizedPreviews = mutableListOf<String>()
            var anyFinalized = false

            uris.forEachIndexed { index, uriStr ->
                // Check if it's a temp URI (contains /temp/ or is from our FileProvider and in temp dir)
                val decodedUri = Uri.decode(uriStr)
                val isTemp = decodedUri.contains("/temp/") || uriStr.contains("/temp/")
                
                if (isTemp) {
                    val result = finalizeTempMedia(context, prefs, uriStr, previews.getOrNull(index), types[index], msgToSave.timestamp)
                    if (result != null) {
                        finalizedUris.add(result.first.toString())
                        finalizedPreviews.add(result.second?.toString() ?: "")
                        anyFinalized = true
                    } else {
                        finalizedUris.add(uriStr)
                        finalizedPreviews.add(previews.getOrNull(index) ?: "")
                    }
                } else {
                    finalizedUris.add(uriStr)
                    finalizedPreviews.add(previews.getOrNull(index) ?: "")
                }
            }
            
            if (anyFinalized) {
                msgToSave = msgToSave.copy(
                    mediaUri = finalizedUris.joinToString("|"),
                    previewUri = finalizedPreviews.joinToString("|")
                )
                
                // Only clear temp folder if all media in this message was successfully finalized
                val allFinalized = finalizedUris.none { 
                    val decoded = Uri.decode(it)
                    decoded.contains("/temp/") || it.contains("/temp/")
                }
                if (allFinalized) {
                    clearTempMedia(context, prefs, fileDateFormat.format(Date(msgToSave.timestamp)))
                }
            }
        }

        // Generate previews if they are missing
        if (!msgToSave.mediaUri.isNullOrEmpty() && msgToSave.previewUri.isNullOrEmpty()) {
            try {
                val uris = msgToSave.mediaUri!!.split("|")
                val types = msgToSave.mediaType?.split("|") ?: List(uris.size) { "IMAGE" }
                val previewUris = mutableListOf<String>()

                uris.forEachIndexed { index, uriStr ->
                    val type = types.getOrNull(index) ?: "IMAGE"
                    val previewUri = createPreview(context, prefs, Uri.parse(uriStr), type, msgToSave.timestamp)
                    previewUris.add(previewUri?.toString() ?: "")
                }
                msgToSave = msgToSave.copy(previewUri = previewUris.joinToString("|"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate previews during save", e)
            }
        }

        val dateStr = fileDateFormat.format(Date(msgToSave.timestamp))
        
        // 1. Get current messages for that day
        val dayMessages = getMessagesForDay(context, prefs, dateStr).toMutableList()
        
        // 2. Add or update the message. Use UUID primarily, fallback to timestamp+text for old data
        val existingIndex = dayMessages.indexOfFirst { 
            it.uuid == msgToSave.uuid || (it.timestamp == msgToSave.timestamp && it.text == msgToSave.text)
        }
        
        if (existingIndex != -1) {
            dayMessages[existingIndex] = msgToSave
        } else {
            dayMessages.add(msgToSave)
        }
        dayMessages.sortBy { it.timestamp }

        // 3. Save back to daily folder structure
        val jsonArray = JSONArray()
        dayMessages.forEach { msg ->
            jsonArray.put(JSONObject().apply {
                put("id", msg.id)
                put("uuid", msg.uuid)
                put("text", msg.text)
                put("isUser", msg.isUser)
                put("timestamp", msg.timestamp)
                put("timestamp_detailed", fullTimestampFormat.format(Date(msg.timestamp)))
                put("mediaUri", msg.mediaUri)
                put("mediaType", msg.mediaType)
                put("previewUri", msg.previewUri)
            })
        }
        
        writeDailyFile(context, prefs, dateStr, jsonArray.toString(2))
    }

    fun createPreview(context: Context, prefs: Prefs, sourceUri: Uri, type: String, timestamp: Long, isTemp: Boolean = false): Uri? {
        val destType = if (isTemp) "TEMP_PREVIEW" else "PREVIEW"
        
        // Use source filename if possible for better matching
        val sourceFileName = try {
            if (sourceUri.scheme == "content") {
                DocumentFile.fromSingleUri(context, sourceUri)?.name
            } else {
                File(sourceUri.path ?: "").name
            }
        } catch (e: Exception) { null }
        
        val preferredName = sourceFileName?.let {
            if (type == "VIDEO") it.substringBeforeLast(".") + ".jpg" else it
        }

        val previewDest = getNextMediaDestination(context, prefs, destType, ".jpg", timestamp, preferredName)

        return try {
            val bitmap = if (type == "VIDEO") {
                getVideoThumbnail(context, sourceUri)
            } else {
                getImageThumbnail(context, sourceUri)
            }

            if (bitmap != null) {
                saveBitmapToDestination(context, bitmap, previewDest)
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create preview", e)
            null
        }
    }

    private fun getVideoThumbnail(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (uri.scheme == "content") {
                context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    retriever.setDataSource(it.fileDescriptor)
                }
            } else {
                retriever.setDataSource(uri.path)
            }
            
            // Try to get frame at 1 second to avoid potential black start frames
            var bitmap = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap == null) {
                bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get video frame", e)
            null
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getImageThumbnail(context: Context, uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            
            options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
            options.inJustDecodeBounds = false
            
            var original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }

            if (original == null) return null

            original = rotateBitmapIfRequired(context, original, uri)

            // Scale down for preview
            val maxWidth = 1024
            val maxHeight = 1024
            if (original.width > maxWidth || original.height > maxHeight) {
                val ratio = original.width.toFloat() / original.height.toFloat()
                val width = if (ratio > 1) maxWidth else (maxHeight * ratio).toInt()
                val height = if (ratio > 1) (maxWidth / ratio).toInt() else maxHeight
                Bitmap.createScaledBitmap(original, width, height, true)
            } else {
                original
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to get image thumbnail", e)
            null
        }
    }

    fun rotateBitmapIfRequired(context: Context, bitmap: Bitmap, uri: Uri): Bitmap {
        return try {
            val inputStream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                if (uri.scheme == "file") {
                    java.io.FileInputStream(uri.path)
                } else null
            } ?: return bitmap

            val ei = ExifInterface(inputStream)
            val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            inputStream.close()

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(bitmap, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(bitmap, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(bitmap, 270f)
                else -> bitmap
            }
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun rotateImage(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun saveBitmapToDestination(context: Context, bitmap: Bitmap, destination: MediaDestination): Uri? {
        return try {
            when (destination) {
                is MediaDestination.Internal -> {
                    if (!destination.file.parentFile!!.exists()) destination.file.parentFile!!.mkdirs()
                    FileOutputStream(destination.file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination.file)
                }
                is MediaDestination.Saf -> {
                    context.contentResolver.openOutputStream(destination.uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    destination.uri
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap", e)
            null
        }
    }

    private fun getMessagesForDay(context: Context, prefs: Prefs, dateStr: String): List<ChatMessage> {
        // Try SAF
        val uriStr = prefs.chatFolderUri
        if (!uriStr.isNullOrEmpty()) {
            try {
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                if (rootTree != null) {
                    val baseDir = getSafChatDir(rootTree)
                    val dateDir = baseDir.findFile(dateStr)
                    val file = dateDir?.findFile(CHAT_FILE_NAME)
                    if (file != null) {
                        val content = context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() }
                        if (content != null) return parseJsonToMessages(content)
                    }
                }
            } catch (e: Exception) { /* ignore */ }
        }

        // Try Internal
        try {
            val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
            val dateDir = File(internalChatDir, dateStr)
            val file = File(dateDir, CHAT_FILE_NAME)
            if (file.exists()) return parseJsonToMessages(file.readText())
            
            // Fallback to old filename format chat_yyyy-MM-dd.json in chat/ folder
            val oldFile = File(internalChatDir, "chat_$dateStr.json")
            if (oldFile.exists()) return parseJsonToMessages(oldFile.readText())
        } catch (e: Exception) { /* ignore */ }

        return emptyList()
    }

    fun deleteMessage(context: Context, prefs: Prefs, message: ChatMessage) {
        // Delete media files if they exist
        message.mediaUri?.let { uriStr ->
            deleteMediaFiles(context, uriStr)
        }
        
        // Delete preview files if they exist
        message.previewUri?.let { uriStr ->
            deleteMediaFiles(context, uriStr)
        }

        val dateStr = fileDateFormat.format(Date(message.timestamp))
        val dayMessages = getMessagesForDay(context, prefs, dateStr).filter { 
            it.uuid != message.uuid && (it.timestamp != message.timestamp || it.text != message.text)
        }
        
        if (dayMessages.isEmpty()) {
            deleteDailyFile(context, prefs, dateStr)
        } else {
            val jsonArray = JSONArray()
            dayMessages.forEach { msg ->
                jsonArray.put(JSONObject().apply {
                    put("id", msg.id)
                    put("uuid", msg.uuid)
                    put("text", msg.text)
                    put("isUser", msg.isUser)
                    put("timestamp", msg.timestamp)
                    put("mediaUri", msg.mediaUri)
                    put("mediaType", msg.mediaType)
                    put("previewUri", msg.previewUri)
                })
            }
            writeDailyFile(context, prefs, dateStr, jsonArray.toString(2))
        }
    }

    private fun deleteMediaFiles(context: Context, uriStrList: String) {
        uriStrList.split("|").forEach { uriStr ->
            if (uriStr.isEmpty()) return@forEach
            try {
                val uri = Uri.parse(uriStr)
                if (uri.scheme == "file") {
                    val file = File(uri.path ?: return@forEach)
                    if (file.exists()) file.delete()
                } else if (uri.scheme == "content") {
                    if (uri.authority == "${context.packageName}.fileprovider") {
                        val path = uri.path
                        if (path != null) {
                            val relativePath = if (path.startsWith("/internal_files/")) {
                                path.substring("/internal_files/".length)
                            } else path
                            val file = File(context.filesDir, relativePath)
                            if (file.exists()) file.delete()
                        }
                    } else {
                        val docFile = DocumentFile.fromSingleUri(context, uri)
                        if (docFile != null && docFile.exists()) {
                            docFile.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore deletion errors for missing files
            }
        }
    }

    fun clearAllMessages(context: Context, prefs: Prefs) {
        // Clear SAF
        val uriStr = prefs.chatFolderUri
        if (!uriStr.isNullOrEmpty()) {
            try {
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                if (rootTree != null) {
                    val baseDir = getSafChatDir(rootTree)
                    deleteSafDirectory(baseDir)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear SAF chat", e)
            }
        }

        // Clear Internal
        try {
            val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
            if (internalChatDir.exists()) {
                internalChatDir.deleteRecursively()
            }
            File(context.filesDir, "crimson_chat.json").delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear internal chat", e)
        }
    }

    private fun deleteSafDirectory(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach { deleteSafDirectory(it) }
        }
        file.delete()
    }

    fun finalizeTempMedia(context: Context, prefs: Prefs, tempUriStr: String, tempPreviewUriStr: String?, type: String, timestamp: Long): Pair<Uri, Uri?>? {
        val extension = if (type == "VIDEO") ".mp4" else ".jpg"
        
        // Extract original filename to preserve it in permanent storage
        val originalFileName = try {
            val uri = Uri.parse(tempUriStr)
            if (uri.scheme == "content") {
                DocumentFile.fromSingleUri(context, uri)?.name
            } else {
                File(uri.path ?: "").name
            }
        } catch (e: Exception) { null }

        val destination = getNextMediaDestination(context, prefs, type, extension, timestamp, originalFileName)
        val tempUri = Uri.parse(tempUriStr)

        try {
            // Check if source exists
            context.contentResolver.openInputStream(tempUri)?.use { it.close() } ?: return null

            val finalizedUri = when (destination) {
                is MediaDestination.Internal -> {
                    if (!destination.file.parentFile!!.exists()) destination.file.parentFile!!.mkdirs()
                    context.contentResolver.openInputStream(tempUri)?.use { input ->
                        FileOutputStream(destination.file).use { output ->
                            input.copyTo(output)
                            output.flush()
                        }
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", destination.file)
                }
                is MediaDestination.Saf -> {
                    context.contentResolver.openInputStream(tempUri)?.use { input ->
                        context.contentResolver.openOutputStream(destination.uri, "wt")?.use { output ->
                            input.copyTo(output)
                        }
                    }
                    destination.uri
                }
            }

            var finalizedPreviewUri: Uri? = null
            if (finalizedUri != null && !tempPreviewUriStr.isNullOrEmpty()) {
                val previewFileName = try {
                    val uri = Uri.parse(tempPreviewUriStr)
                    if (uri.scheme == "content") {
                        DocumentFile.fromSingleUri(context, uri)?.name
                    } else {
                        File(uri.path ?: "").name
                    }
                } catch (e: Exception) { null }

                val previewDest = getNextMediaDestination(context, prefs, "PREVIEW", ".jpg", timestamp, previewFileName)
                val tempPreviewUri = Uri.parse(tempPreviewUriStr)
                
                finalizedPreviewUri = when (previewDest) {
                    is MediaDestination.Internal -> {
                        if (!previewDest.file.parentFile!!.exists()) previewDest.file.parentFile!!.mkdirs()
                        context.contentResolver.openInputStream(tempPreviewUri)?.use { input ->
                            FileOutputStream(previewDest.file).use { output ->
                                input.copyTo(output)
                            }
                        }
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", previewDest.file)
                    }
                    is MediaDestination.Saf -> {
                        context.contentResolver.openInputStream(tempPreviewUri)?.use { input ->
                            context.contentResolver.openOutputStream(previewDest.uri, "wt")?.use { output ->
                                input.copyTo(output)
                            }
                        }
                        previewDest.uri
                    }
                }
            }

            return if (finalizedUri != null) Pair(finalizedUri, finalizedPreviewUri) else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize temp media", e)
        }
        return null
    }

    fun clearTempMedia(context: Context, prefs: Prefs, dateStr: String? = null) {
        Thread {
            val uriStr = prefs.chatFolderUri
            val targetDateStr = dateStr ?: fileDateFormat.format(Date())
            
            if (!uriStr.isNullOrEmpty()) {
                try {
                    val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                    if (rootTree != null) {
                        val baseDir = getSafChatDir(rootTree)
                        val dateDir = baseDir.findFile(targetDateStr)
                        val tempDir = dateDir?.findFile("temp")
                        tempDir?.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear SAF temp media", e)
                }
            }

            try {
                val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
                val dateDir = File(internalChatDir, targetDateStr)
                val tempDir = File(dateDir, "temp")
                if (tempDir.exists()) tempDir.deleteRecursively()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear internal temp media", e)
            }
        }.start()
    }

    private fun writeDailyFile(context: Context, prefs: Prefs, dateStr: String, content: String) {
        var success = false
        val uriStr = prefs.chatFolderUri
        if (!uriStr.isNullOrEmpty()) {
            try {
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) ?: return
                val baseDir = getSafChatDir(rootTree)
                
                var dateDir = baseDir.findFile(dateStr)
                if (dateDir == null) {
                    dateDir = baseDir.createDirectory(dateStr)
                }
                
                if (dateDir != null) {
                    var file = dateDir.findFile(CHAT_FILE_NAME)
                    if (file == null) {
                        file = dateDir.createFile("application/json", CHAT_FILE_NAME)
                    }
                    file?.let {
                        context.contentResolver.openOutputStream(it.uri, "wt")?.use { out ->
                            out.write(content.toByteArray())
                            success = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write daily chat to SAF", e)
            }
        }

        if (!success) {
            try {
                val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
                val dateDir = File(internalChatDir, dateStr)
                if (!dateDir.exists()) dateDir.mkdirs()
                val file = File(dateDir, CHAT_FILE_NAME)
                FileOutputStream(file).use { it.write(content.toByteArray()) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write daily chat to internal", e)
            }
        }
    }

    private fun deleteDailyFile(context: Context, prefs: Prefs, dateStr: String) {
        val uriStr = prefs.chatFolderUri
        if (!uriStr.isNullOrEmpty()) {
            try {
                val rootTree = DocumentFile.fromTreeUri(context, Uri.parse(uriStr))
                if (rootTree != null) {
                    val baseDir = getSafChatDir(rootTree)
                    val dateDir = baseDir.findFile(dateStr)
                    dateDir?.findFile(CHAT_FILE_NAME)?.delete()
                    dateDir?.delete()
                }
            } catch (e: Exception) { /* ignore */ }
        }
        try {
            val internalChatDir = File(context.filesDir, CHAT_DIR_NAME)
            val dateDir = File(internalChatDir, dateStr)
            dateDir.deleteRecursively()
        } catch (e: Exception) { /* ignore */ }
    }
}
