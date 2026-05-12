package app.olauncher.ui

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.graphics.BitmapFactory
import androidx.core.net.toUri
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.olauncher.BuildConfig
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.ChatItem
import app.olauncher.data.ChatMessage
import app.olauncher.databinding.FragmentChatBinding
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private lateinit var chatAdapter: ChatAdapter
    private var editingMessage: ChatMessage? = null
    
    data class PendingMedia(
        val uri: String, 
        val type: String, 
        val preview: String?
    )
    private val pendingMedia = mutableListOf<PendingMedia>()

    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false
    private var audioTempFile: File? = null
    private var recordingStartTime = 0L
    private val recordingHandler = Handler(Looper.getMainLooper())
    private val updateRecordingTask = object : Runnable {
        override fun run() {
            if (isRecording) {
                val duration = System.currentTimeMillis() - recordingStartTime
                val seconds = (duration / 1000) % 60
                val minutes = (duration / 1000) / 60
                binding.tvRecordingTimer.text = String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
                
                try {
                    val amplitude = mediaRecorder?.maxAmplitude ?: 0
                    binding.vAudioVisualizer.addAmplitude(amplitude.toFloat())
                } catch (e: Exception) {}
                
                recordingHandler.postDelayed(this, 50)
            }
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startRecording()
        } else {
            requireContext().showToast("Microphone permission required")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupRecyclerView()
        
        binding.btnSendChat.setOnClickListener {
            sendMessage()
        }

        binding.btnCamera.setOnClickListener {
            findNavController().navigate(R.id.action_chatFragment_to_cameraFragment)
        }

        binding.btnMic.setOnClickListener {
            checkAudioPermissionAndStart()
        }

        binding.btnStopRecord.setOnClickListener {
            stopRecording()
        }

        binding.btnCalendar.setOnClickListener {
            showDatePicker()
        }

        binding.btnSelectMode.setOnClickListener {
            chatAdapter.isSelectionMode = !chatAdapter.isSelectionMode
            updateSelectModeUI()
        }

        binding.btnDeleteSelected.setOnClickListener {
            val selected = chatAdapter.getSelectedMessages()
            if (selected.isNotEmpty()) {
                DeleteMessageConfirmDialogFragment.newInstance(selected.map { it.message })
                    .show(childFragmentManager, "delete_selected_confirm")
            }
        }

        // Listen for deletion confirmation to exit selection mode
        childFragmentManager.setFragmentResultListener("delete_confirmed", viewLifecycleOwner) { _, _ ->
            if (chatAdapter.isSelectionMode) {
                chatAdapter.isSelectionMode = false
                updateSelectModeUI()
            }
        }

        parentFragmentManager.setFragmentResultListener("camera_result", viewLifecycleOwner) { _, bundle ->
            val uris = bundle.getStringArrayList("uris") ?: emptyList<String>()
            val previews = bundle.getStringArrayList("previews") ?: emptyList<String>()
            val types = bundle.getStringArrayList("types") ?: emptyList<String>()
            
            bundle.clear()
            
            for (i in uris.indices) {
                val uri = uris[i]
                // Prevent duplicate instances of the same media
                if (pendingMedia.none { it.uri == uri }) {
                    pendingMedia.add(PendingMedia(uri, types[i], previews.getOrNull(i)))
                }
            }
            showMediaPreview()
            binding.etChatInput.requestFocus()
        }

        binding.etChatInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
            (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
        ) {
                sendMessage()
                true
            } else {
                false
            }
        }

        initSwipeListener()
        setupKeyboardListener()
        initObservers()
    }

    private fun checkAudioPermissionAndStart() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startRecording()
        } else {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startRecording() {
        try {
            val internalChatDir = File(requireContext().filesDir, "chat")
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val audioDir = File(internalChatDir, "$dateStr/temp/audio")
            if (!audioDir.exists()) audioDir.mkdirs()
            
            audioTempFile = File(audioDir, "${UUID.randomUUID()}.m4a")
            
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioTempFile!!.absolutePath)
                prepare()
                start()
            }
            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            
            binding.btnCamera.visibility = View.GONE
            binding.btnMic.visibility = View.GONE
            binding.btnSendChat.visibility = View.GONE
            binding.etChatInput.visibility = View.GONE
            binding.llRecordingUi.visibility = View.VISIBLE
            
            recordingHandler.post(updateRecordingTask)
        } catch (e: Exception) {
            android.util.Log.e("ChatFragment", "Failed to start recording", e)
            requireContext().showToast("Failed to start recording")
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            android.util.Log.e("ChatFragment", "Failed to stop recording", e)
        } finally {
            mediaRecorder = null
            isRecording = false
            recordingHandler.removeCallbacks(updateRecordingTask)
            
            binding.btnCamera.visibility = View.VISIBLE
            binding.btnMic.visibility = View.VISIBLE
            binding.btnSendChat.visibility = View.VISIBLE
            binding.etChatInput.visibility = View.VISIBLE
            binding.llRecordingUi.visibility = View.GONE
            
            if (audioTempFile != null && audioTempFile!!.exists() && audioTempFile!!.length() > 0) {
                val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", audioTempFile!!).toString()
                pendingMedia.add(PendingMedia(uri, "AUDIO", null))
                showMediaPreview()
            }
        }
    }

    private fun initObservers() {
        viewModel.chatMessages.observe(viewLifecycleOwner) { messages ->
            refreshDisplayItems(messages)
            updateEmptyState(messages)
        }
        viewModel.loadChatMessages()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(
            items = emptyList(),
            alignment = (requireContext().applicationContext as? android.app.Application)?.let { 
                app.olauncher.data.Prefs(it).homeAlignment 
            } ?: android.view.Gravity.START,
            onDeleteClick = { messageItem ->
                DeleteMessageConfirmDialogFragment.newInstance(messageItem.message)
                    .show(childFragmentManager, "delete_confirm")
            },
            onCopyClick = { messageItem ->
                copyToClipboard(messageItem.message.text)
            },
            onEditClick = { messageItem ->
                enterChatEditMode(messageItem.message)
            },
            onMediaClick = { uri, type ->
                val allMediaMessages = viewModel.chatMessages.value?.filter { !it.mediaUri.isNullOrEmpty() } ?: emptyList()
                val allUris = mutableListOf<String>()
                val allPreviews = mutableListOf<String>()
                val allTypes = mutableListOf<String>()
                
                allMediaMessages.forEach { msg ->
                    val mUris = msg.mediaUri!!.split("|")
                    val mPreviews = msg.previewUri?.split("|") ?: emptyList()
                    val mTypes = msg.mediaType?.split("|") ?: emptyList()
                    
                    for (i in mUris.indices) {
                        val mType = mTypes.getOrNull(i) ?: "IMAGE"
                        if (mType != "AUDIO") {
                            allUris.add(mUris[i])
                            allPreviews.add(mPreviews.getOrNull(i) ?: "")
                            allTypes.add(mType)
                        }
                    }
                }
                
                val position = allUris.indexOf(uri)
                
                MediaPreviewDialogFragment.newInstance(allUris, allPreviews, allTypes, if (position != -1) position else 0)
                    .show(childFragmentManager, "media_preview")
            }
        )
        
        chatAdapter.onSelectionChanged = { count ->
            binding.btnDeleteSelected.visibility = if (count > 0) View.VISIBLE else View.GONE
        }

        binding.rvChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(requireContext()).apply {
                stackFromEnd = true
            }
            setItemViewCacheSize(20)
            setHasFixedSize(true)
        }
    }

    private fun updateSelectModeUI() {
        if (chatAdapter.isSelectionMode) {
            binding.btnSelectMode.setImageResource(R.drawable.ic_close)
            binding.btnCalendar.visibility = View.GONE
        } else {
            binding.btnSelectMode.setImageResource(R.drawable.ic_check)
            binding.btnCalendar.visibility = View.VISIBLE
            binding.btnDeleteSelected.visibility = View.GONE
        }
    }

    private fun enterChatEditMode(message: ChatMessage) {
        editingMessage = message
        binding.etChatInput.setText(message.text)
        binding.etChatInput.requestFocus()
        binding.etChatInput.setSelection(message.text.length)
        binding.btnSendChat.setImageResource(R.drawable.ic_check)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Crimson Note", text)
        clipboard.setPrimaryClip(clip)
        requireContext().showToast(getString(R.string.copied))
    }

    private fun sendMessage() {
        val text = binding.etChatInput.text.toString().trim()
        if (text.isNotEmpty() || pendingMedia.isNotEmpty()) {
            if (editingMessage != null) {
                viewModel.updateChatMessage(editingMessage!!, text)
                editingMessage = null
                binding.btnSendChat.setImageResource(R.drawable.ic_send)
            } else {
                val mediaUriStr = if (pendingMedia.isEmpty()) null else pendingMedia.joinToString("|") { it.uri }
                val previewUriStr = if (pendingMedia.isEmpty()) null else pendingMedia.joinToString("|") { it.preview ?: "" }
                val mediaTypeStr = if (pendingMedia.isEmpty()) null else pendingMedia.joinToString("|") { it.type }

                val newMessage = ChatMessage(
                    text = if (text.isEmpty() && pendingMedia.isNotEmpty()) {
                        val firstType = pendingMedia.first().type
                        if (firstType == "IMAGE") "Photo" else if (firstType == "AUDIO") "Audio" else "Video"
                    } else text,
                    isUser = true,
                    mediaUri = mediaUriStr,
                    previewUri = previewUriStr,
                    mediaType = mediaTypeStr
                )
                viewModel.saveChatMessage(newMessage)
            }
            binding.etChatInput.text.clear()
            pendingMedia.clear()
            showMediaPreview()
        }
    }

    private fun showMediaPreview() {
        binding.llMediaPreviewContainer.removeAllViews()
        if (pendingMedia.isNotEmpty()) {
            binding.hsvMediaPreview.visibility = View.VISIBLE
            pendingMedia.forEach { item ->
                val previewView = layoutInflater.inflate(R.layout.item_media_attachment_preview, binding.llMediaPreviewContainer, false)
                val ivPreview = previewView.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivMediaPreview)
                val ivPlay = previewView.findViewById<android.widget.ImageView>(R.id.ivPlayPreview)
                val btnRemove = previewView.findViewById<android.widget.ImageView>(R.id.btnRemoveMedia)

                val displayUri = item.preview?.toUri() ?: item.uri.toUri()
                ivPreview.setImageDrawable(null)
                
                // Load thumbnail asynchronously to avoid crashes with stale or deep URIs
                Thread {
                    try {
                        val ctx = context ?: return@Thread
                        
                        // Calculate inSampleSize
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        ctx.contentResolver.openInputStream(displayUri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        
                        options.inSampleSize = 4 // Default safe downscale for preview thumbnails
                        if (options.outHeight > 2048 || options.outWidth > 2048) {
                            options.inSampleSize = 8
                        }
                        options.inJustDecodeBounds = false
                        
                        val bitmap: android.graphics.Bitmap? = ctx.contentResolver.openInputStream(displayUri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        if (bitmap != null) {
                            val rotated = app.olauncher.data.ChatStorage.rotateBitmapIfRequired(ctx, bitmap, displayUri)
                            activity?.runOnUiThread {
                                ivPreview.setImageBitmap(rotated)
                            }
                        }
                    } catch (e: Throwable) {
                        activity?.runOnUiThread {
                            ivPreview.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                }.start()

                ivPlay.visibility = if (item.type == "VIDEO") View.VISIBLE else View.GONE
                val ivAudio = previewView.findViewById<android.widget.ImageView>(R.id.ivAudioPreview)
                ivAudio?.visibility = if (item.type == "AUDIO") View.VISIBLE else View.GONE
                
                if (item.type == "AUDIO") {
                    ivPreview.setImageDrawable(null)
                    ivPreview.setBackgroundColor(0x33FFFFFF) // faint grey background for audio
                }

                btnRemove.setOnClickListener {
                    // Unique match by URI
                    val index = pendingMedia.indexOfFirst { it.uri == item.uri }
                    if (index != -1) {
                        pendingMedia.removeAt(index)
                        showMediaPreview()
                    }
                }
                binding.llMediaPreviewContainer.addView(previewView)
            }
        } else {
            binding.hsvMediaPreview.visibility = View.GONE
        }
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val selectedCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                }
                scrollToDate(selectedCal)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun scrollToDate(selectedCal: Calendar) {
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        val dateKey = getDateHeaderKey(selectedCal.timeInMillis, dateFormat)
        
        val adapterItems = chatAdapter.items
        val index = adapterItems.indexOfFirst { 
            it is ChatItem.Header && it.dateText == dateKey 
        }

        if (index != -1) {
            (binding.rvChat.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(index, 0)
        } else {
            requireContext().showToast("No thoughts captured on this date")
        }
    }

    private fun refreshDisplayItems(rawMessages: List<ChatMessage>) {
        if (rawMessages.isEmpty()) {
            chatAdapter.updateItems(emptyList())
            return
        }

        val newItems = mutableListOf<ChatItem>()
        var lastDateKey = ""
        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

        for (msg in rawMessages) {
            val dateKey = getDateHeaderKey(msg.timestamp, dateFormat)
            if (dateKey != lastDateKey) {
                newItems.add(ChatItem.Header(dateKey))
                lastDateKey = dateKey
            }
            newItems.add(ChatItem.Message(msg))
        }
        
        chatAdapter.updateItems(newItems)
        
        if (newItems.isNotEmpty() && editingMessage == null) {
            binding.rvChat.post {
                binding.rvChat.scrollToPosition(newItems.size - 1)
            }
        }
    }

    private fun getDateHeaderKey(timestamp: Long, dateFormat: SimpleDateFormat): String {
        val now = Calendar.getInstance()
        val msgDate = Calendar.getInstance().apply { timeInMillis = timestamp }

        return when {
            isSameDay(now, msgDate) -> "Today"
            isYesterday(now, msgDate) -> "Yesterday"
            else -> dateFormat.format(Date(timestamp))
        }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1[Calendar.YEAR] == cal2[Calendar.YEAR] &&
                cal1[Calendar.DAY_OF_YEAR] == cal2[Calendar.DAY_OF_YEAR]
    }

    private fun isYesterday(now: Calendar, msgDate: Calendar): Boolean {
        val yesterday = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameDay(yesterday, msgDate)
    }

    private fun updateEmptyState(rawMessages: List<ChatMessage>) {
        binding.tvEmptyState.visibility = if (rawMessages.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setupKeyboardListener() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            
            // Adjust padding to move input field above keyboard
            v.setPadding(0, v.paddingTop, 0, imeHeight)
            
            if (imeVisible && chatAdapter.itemCount > 0) {
                binding.rvChat.postDelayed({
                    binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
                }, 100)
            }
            insets
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeListener() {
        val swipeTouchListener = object : OnSwipeTouchListener(requireContext()) {
            override fun onClick() {
                super.onClick()
                if (chatAdapter.isSelectionMode) {
                    // Do nothing on background click in selection mode to avoid accidental exits
                    return
                }
                
                if (chatAdapter.hasSelection()) {
                    chatAdapter.clearSelection()
                } else {
                    binding.etChatInput.hideKeyboard()
                }
            }

            override fun onSwipeLeft() {
                super.onSwipeLeft()
                findNavController().navigate(R.id.action_chatFragment_to_mainFragment)
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                findNavController().navigate(R.id.action_chatFragment_to_todoFragment)
            }
        }
        binding.root.setOnTouchListener(swipeTouchListener)
        binding.chatContainer.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }
        
        // Intercept touches to handle swipes even when starting on chat bubbles or items
        binding.rvChat.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                swipeTouchListener.onTouch(rv, e)
                return false // Don't consume ACTION_DOWN, let children handle clicks
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                swipeTouchListener.onTouch(rv, e)
            }

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })

        binding.tvEmptyState.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }
        binding.llInputContainer.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
