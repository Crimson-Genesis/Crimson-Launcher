package app.olauncher.ui

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.data.ChatItem
import app.olauncher.data.ChatStorage
import app.olauncher.databinding.ItemChatDateHeaderBinding
import app.olauncher.databinding.ItemChatMediaBinding
import app.olauncher.databinding.ItemChatMessageBinding
import app.olauncher.helper.dpToPx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class ChatAdapter(
    var items: List<ChatItem>,
    private val alignment: Int,
    private val onDeleteClick: (ChatItem.Message) -> Unit,
    private val onCopyClick: (ChatItem.Message) -> Unit,
    private val onEditClick: (ChatItem.Message) -> Unit,
    private val onMediaClick: (String, String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MESSAGE = 1
        private val mediaExecutor = Executors.newFixedThreadPool(4)
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var selectedMessageUuid: String? = null // For single message tray
    private val selectedMessageUuids = mutableSetOf<String>() // For multi-select mode
    var isSelectionMode = false
        set(value) {
            field = value
            if (!value) {
                selectedMessageUuids.clear()
                selectedMessageUuid = null
            }
            notifyDataSetChanged()
            onSelectionChanged?.invoke(selectedMessageUuids.size)
        }
    
    var onSelectionChanged: ((Int) -> Unit)? = null
    private val sharedPool = RecyclerView.RecycledViewPool()

    fun getSelectedMessages(): List<ChatItem.Message> {
        return items.filterIsInstance<ChatItem.Message>().filter { selectedMessageUuids.contains(it.message.uuid) }
    }

    private fun toggleMultiSelection(uuid: String) {
        if (selectedMessageUuids.contains(uuid)) {
            selectedMessageUuids.remove(uuid)
        } else {
            selectedMessageUuids.add(uuid)
        }
        val pos = items.indexOfFirst { it is ChatItem.Message && it.message.uuid == uuid }
        if (pos != -1) notifyItemChanged(pos)
        onSelectionChanged?.invoke(selectedMessageUuids.size)
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ChatItem.Header -> TYPE_HEADER
            is ChatItem.Message -> TYPE_MESSAGE
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> {
                val binding = ItemChatDateHeaderBinding.inflate(inflater, parent, false)
                HeaderViewHolder(binding)
            }
            else -> {
                val binding = ItemChatMessageBinding.inflate(inflater, parent, false)
                MessageViewHolder(binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChatItem.Header -> (holder as HeaderViewHolder).bind(item)
            is ChatItem.Message -> (holder as MessageViewHolder).bind(item)
        }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<ChatItem>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = newItems[newItemPosition]
                return if (old is ChatItem.Message && new is ChatItem.Message) {
                    old.message.timestamp == new.message.timestamp && 
                            old.message.text == new.message.text
                } else if (old is ChatItem.Header && new is ChatItem.Header) {
                    old.dateText == new.dateText
                } else {
                    false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return items[oldItemPosition] == newItems[newItemPosition]
            }
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems.toList()
        diffResult.dispatchUpdatesTo(this)
    }

    fun clearSelection() {
        if (isSelectionMode) {
            isSelectionMode = false
        } else if (selectedMessageUuid != null) {
            val oldUuid = selectedMessageUuid
            selectedMessageUuid = null
            val oldPos = items.indexOfFirst { it is ChatItem.Message && it.message.uuid == oldUuid }
            if (oldPos != -1) notifyItemChanged(oldPos)
        }
    }

    fun hasSelection(): Boolean = selectedMessageUuid != null

    inner class HeaderViewHolder(private val binding: ItemChatDateHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Header) {
            binding.tvDateHeader.text = item.dateText
        }
    }

    inner class MessageViewHolder(private val binding: ItemChatMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ChatItem.Message) {
            val message = item.message
            val density = binding.root.context.resources.displayMetrics.density
            val isSingleSelected = selectedMessageUuid == message.uuid
            val isMultiSelected = selectedMessageUuids.contains(message.uuid)

            binding.tvMessage.text = message.text
            binding.tvTime.text = timeFormat.format(Date(message.timestamp))

            // UI feedback for selection
            if (isSelectionMode) {
                binding.llMessageContainer.alpha = if (isMultiSelected) 1.0f else 0.6f
                binding.root.setBackgroundColor(if (isMultiSelected) 0x33FFFFFF else 0x00000000)
            } else {
                binding.llMessageContainer.alpha = 1.0f
                binding.root.setBackgroundColor(0x00000000)
            }

            if (message.mediaUri != null) {
                val uris = message.mediaUri.split("|")
                val previews = message.previewUri?.split("|") ?: emptyList()
                val rawTypes = message.mediaType?.split("|") ?: emptyList()
                val types = List(uris.size) { i -> rawTypes.getOrNull(i) ?: "IMAGE" }
                
                val visualUris = mutableListOf<String>()
                val visualPreviews = mutableListOf<String>()
                val visualTypes = mutableListOf<String>()
                val audioUris = mutableListOf<String>()
                
                for (i in uris.indices) {
                    if (types[i] == "AUDIO") {
                        audioUris.add(uris[i])
                    } else {
                        visualUris.add(uris[i])
                        visualPreviews.add(previews.getOrNull(i) ?: "")
                        visualTypes.add(types[i])
                    }
                }
                
                if (visualUris.isNotEmpty()) {
                    binding.rvMedia.visibility = View.VISIBLE
                    if (binding.rvMedia.layoutManager == null) {
                        binding.rvMedia.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
                    }
                    binding.rvMedia.setRecycledViewPool(sharedPool)
                    binding.rvMedia.setHasFixedSize(true)
                    binding.rvMedia.adapter = MediaAdapter(visualUris, visualPreviews, visualTypes, { clickedUri, clickedType ->
                        if (isSelectionMode) {
                            toggleMultiSelection(message.uuid)
                        } else {
                            onMediaClick(clickedUri, clickedType)
                        }
                    }, {
                        if (!isSelectionMode) {
                            binding.llMessageContainer.performLongClick()
                        }
                    })
                } else {
                    binding.rvMedia.visibility = View.GONE
                }
                
                if (audioUris.isNotEmpty()) {
                    binding.llAudioContainer.visibility = View.VISIBLE
                    binding.llAudioContainer.removeAllViews()
                    for (audioUri in audioUris) {
                        val audioView = android.view.LayoutInflater.from(itemView.context).inflate(app.olauncher.R.layout.item_chat_audio, binding.llAudioContainer, false)
                        val tvDuration = audioView.findViewById<android.widget.TextView>(app.olauncher.R.id.tvAudioDuration)
                        val vPlaybackVisualizer = audioView.findViewById<app.olauncher.ui.AudioVisualizerView>(app.olauncher.R.id.vPlaybackVisualizer)
                        val btnPlayPause = audioView.findViewById<android.widget.ImageView>(app.olauncher.R.id.btnPlayPauseAudio)
                        val sbProgress = audioView.findViewById<android.widget.SeekBar>(app.olauncher.R.id.sbAudioProgress)
                        
                        // Handle alignment-based reordering
                        if (alignment == android.view.Gravity.END) {
                            val audioContent = audioView as android.widget.LinearLayout
                            val playBtn = audioContent.findViewById<android.view.View>(app.olauncher.R.id.btnPlayPauseAudio)
                            val visualizerFrame = audioContent.getChildAt(1) // The FrameLayout
                            val durationTxt = audioContent.findViewById<android.view.View>(app.olauncher.R.id.tvAudioDuration)
                            
                            audioContent.removeAllViews()
                            audioContent.addView(durationTxt)
                            audioContent.addView(visualizerFrame)
                            audioContent.addView(playBtn)
                            
                            // Adjust margins for reversed layout
                            (playBtn.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                                marginStart = (8 * density).toInt()
                                marginEnd = (12 * density).toInt()
                            }
                            (durationTxt.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                                marginStart = (12 * density).toInt()
                                marginEnd = (8 * density).toInt()
                            }
                        }
                        
                        val random = java.util.Random(audioUri.hashCode().toLong())
                        val amps = FloatArray(120) { 0.1f + random.nextFloat() * 0.9f }
                        vPlaybackVisualizer.setStaticAmplitudes(amps)

                        // Set initial duration
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            retriever.setDataSource(itemView.context, android.net.Uri.parse(audioUri))
                            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            val durationMs = durationStr?.toLong() ?: 0L
                            tvDuration.text = java.lang.String.format(java.util.Locale.US, "%02d:%02d", (durationMs / 1000) / 60, (durationMs / 1000) % 60)
                            retriever.release()
                        } catch (e: Exception) {
                            tvDuration.text = "00:00"
                        }
                        
                        var mediaPlayer: android.media.MediaPlayer? = null
                        var isPlaying = false
                        val updateHandler = android.os.Handler(android.os.Looper.getMainLooper())
                        var updateRunnable: Runnable? = null
                        
                        fun releasePlayer() {
                            mediaPlayer?.release()
                            mediaPlayer = null
                            isPlaying = false
                            btnPlayPause.setImageResource(app.olauncher.R.drawable.ic_play_white)
                            updateRunnable?.let { updateHandler.removeCallbacks(it) }
                        }
                        
                        btnPlayPause.setOnClickListener {
                            if (mediaPlayer == null) {
                                mediaPlayer = android.media.MediaPlayer().apply {
                                    setDataSource(itemView.context, android.net.Uri.parse(audioUri))
                                    prepare()
                                    tvDuration.text = java.lang.String.format(java.util.Locale.US, "%02d:%02d", (duration / 1000) / 60, (duration / 1000) % 60)
                                    sbProgress.max = duration
                                }
                                mediaPlayer?.setOnCompletionListener {
                                    isPlaying = false
                                    btnPlayPause.setImageResource(app.olauncher.R.drawable.ic_play_white)
                                    sbProgress.progress = 0
                                    vPlaybackVisualizer.setPlaybackProgress(0f)
                                    mediaPlayer?.seekTo(0)
                                }
                            }
                            
                            if (isPlaying) {
                                mediaPlayer?.pause()
                                isPlaying = false
                                btnPlayPause.setImageResource(app.olauncher.R.drawable.ic_play_white)
                            } else {
                                mediaPlayer?.start()
                                isPlaying = true
                                btnPlayPause.setImageResource(app.olauncher.R.drawable.ic_pause)
                                
                                updateRunnable = object : Runnable {
                                    override fun run() {
                                        if (isPlaying) {
                                            val currentPos = mediaPlayer?.currentPosition ?: 0
                                            val maxDuration = mediaPlayer?.duration ?: 1
                                            sbProgress.progress = currentPos
                                            vPlaybackVisualizer.setPlaybackProgress(currentPos.toFloat() / maxDuration.toFloat())
                                            updateHandler.postDelayed(this, 50)
                                        }
                                    }
                                }
                                updateHandler.post(updateRunnable!!)
                            }
                        }
                        
                        sbProgress.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                                if (fromUser) {
                                    mediaPlayer?.seekTo(progress)
                                    val maxDuration = seekBar?.max ?: 1
                                    vPlaybackVisualizer.setPlaybackProgress(progress.toFloat() / maxDuration.toFloat())
                                }
                            }
                            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                        })
                        
                        binding.llAudioContainer.addView(audioView)
                    }
                } else {
                    binding.llAudioContainer.visibility = View.GONE
                }
            } else {
                binding.rvMedia.visibility = View.GONE
                binding.llAudioContainer.visibility = View.GONE
            }

            binding.llChatTray.visibility = if (!isSelectionMode && isSingleSelected) View.VISIBLE else View.GONE

            val longClickListener = View.OnLongClickListener {
                if (isSelectionMode) {
                    toggleMultiSelection(message.uuid)
                    return@OnLongClickListener true
                }
                
                val currentPos = bindingAdapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@OnLongClickListener false
                val currentItem = items.getOrNull(currentPos) as? ChatItem.Message ?: return@OnLongClickListener false
                
                val oldUuid = selectedMessageUuid
                selectedMessageUuid = if (selectedMessageUuid == currentItem.message.uuid) null else currentItem.message.uuid
                
                // Refresh old item to hide tray
                if (oldUuid != null && oldUuid != selectedMessageUuid) {
                    val oldPos = items.indexOfFirst { it is ChatItem.Message && it.message.uuid == oldUuid }
                    if (oldPos != -1) notifyItemChanged(oldPos)
                }
                // Refresh new item to show/hide tray
                notifyItemChanged(currentPos)
                true
            }

            binding.root.setOnLongClickListener(longClickListener)
            binding.llMessageContainer.setOnLongClickListener(longClickListener)

            binding.root.setOnClickListener {
                if (isSelectionMode) {
                    toggleMultiSelection(message.uuid)
                }
            }

            binding.llMessageContainer.setOnClickListener {
                if (isSelectionMode) {
                    toggleMultiSelection(message.uuid)
                } else {
                    val currentPos = bindingAdapterPosition
                    if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                    val currentItem = items.getOrNull(currentPos) as? ChatItem.Message ?: return@setOnClickListener
                    
                    if (selectedMessageUuid == currentItem.message.uuid) {
                        selectedMessageUuid = null
                        notifyItemChanged(currentPos)
                    }
                }
            }

            binding.rvMedia.setOnLongClickListener {
                if (isSelectionMode) {
                    toggleMultiSelection(message.uuid)
                    true
                } else {
                    longClickListener.onLongClick(binding.llMessageContainer)
                }
            }

            binding.btnEditChat.setOnClickListener {
                onEditClick(item)
                selectedMessageUuid = null
                notifyItemChanged(bindingAdapterPosition)
            }

            binding.btnDeleteChat.setOnClickListener {
                onDeleteClick(item)
                selectedMessageUuid = null
                notifyDataSetChanged()
            }

            binding.btnCopyChat.setOnClickListener {
                onCopyClick(item)
                selectedMessageUuid = null
                notifyItemChanged(bindingAdapterPosition)
            }

            // Align message bubble
            val bubbleParams = binding.llMessageContainer.layoutParams as FrameLayout.LayoutParams
            bubbleParams.gravity = alignment
            
            val margin8 = (8 * density).toInt()
            
            if (message.mediaType?.contains("AUDIO") == true) {
                bubbleParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                bubbleParams.setMargins(0, margin8, 0, margin8)
            } else {
                bubbleParams.width = FrameLayout.LayoutParams.WRAP_CONTENT
                bubbleParams.setMargins(margin8, margin8, margin8, margin8)
            }
            
            binding.llMessageContainer.layoutParams = bubbleParams
        }
    }

    inner class MediaAdapter(
        private val uris: List<String>,
        private val previews: List<String>,
        private val types: List<String>,
        private val onClick: (String, String) -> Unit,
        private val onLongClick: () -> Unit
    ) : RecyclerView.Adapter<MediaAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemChatMediaBinding) : RecyclerView.ViewHolder(binding.root) {
            var currentLoadTask: java.util.concurrent.Future<*>? = null
            val handler = Handler(Looper.getMainLooper())

            fun cancelLoad() {
                currentLoadTask?.cancel(true)
                currentLoadTask = null
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemChatMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            // Set fixed width for items in horizontal chat bubbles
            binding.root.layoutParams.width = 160.dpToPx()
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uri = uris[position]
            val previewUri = previews.getOrNull(position)
            val type = types[position]

            val displayUri = if (!previewUri.isNullOrEmpty()) previewUri else uri
            
            holder.cancelLoad()
            holder.binding.ivMedia.setImageDrawable(null)

            if (type == "IMAGE") {
                holder.binding.ivPlayVideo.visibility = View.GONE
            } else {
                holder.binding.ivPlayVideo.visibility = View.VISIBLE
            }

                // Load bitmap asynchronously
            holder.currentLoadTask = mediaExecutor.submit {
                try {
                    val context = holder.itemView.context
                    val uri = Uri.parse(displayUri)
                    
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    try {
                        context.contentResolver.openInputStream(uri)
                    } catch (e: Exception) {
                        if (uri.scheme == "file") uri.path?.let { java.io.FileInputStream(it) } else null
                    }?.use { BitmapFactory.decodeStream(it, null, options) }

                    options.inSampleSize = 4 // Downscale for chat bubbles
                    if (options.outHeight > 2048 || options.outWidth > 2048) {
                        options.inSampleSize = 8
                    }
                    options.inJustDecodeBounds = false

                    val inputStream = try {
                        context.contentResolver.openInputStream(uri)
                    } catch (e: Exception) {
                        if (uri.scheme == "file") uri.path?.let { java.io.FileInputStream(it) } else null
                    }
                    var bitmap = inputStream?.use { BitmapFactory.decodeStream(it, null, options) }

                    if (bitmap != null) {
                        bitmap = ChatStorage.rotateBitmapIfRequired(context, bitmap, uri)
                        holder.handler.post {
                            holder.binding.ivMedia.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Throwable) {
                    if (type != "IMAGE" && previewUri.isNullOrEmpty()) {
                        holder.handler.post {
                            holder.binding.ivMedia.setImageResource(android.R.drawable.ic_menu_gallery)
                        }
                    }
                }
            }

            holder.binding.root.setOnClickListener { onClick(uri, type) }
            holder.binding.root.setOnLongClickListener {
                onLongClick()
                true
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.cancelLoad()
        }

        override fun getItemCount() = uris.size
    }
}
