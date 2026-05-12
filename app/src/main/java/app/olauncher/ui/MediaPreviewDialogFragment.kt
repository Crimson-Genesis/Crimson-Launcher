package app.olauncher.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.DialogMediaPreviewBinding
import app.olauncher.databinding.ItemMediaPagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MediaPreviewDialogFragment : DialogFragment() {

    private var _binding: DialogMediaPreviewBinding? = null
    private val binding get() = _binding!!
    
    // Memory cache for high-quality images to avoid re-decoding during swipes
    // Limit memory usage to 25% of available max memory to prevent OutOfMemory crashes
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 4
    private val imageCache = object : android.util.LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    companion object {
        private const val ARG_URIS = "uris"
        private const val ARG_PREVIEWS = "previews"
        private const val ARG_TYPES = "types"
        private const val ARG_POSITION = "position"

        fun newInstance(uris: List<String>, previews: List<String>, types: List<String>, position: Int): MediaPreviewDialogFragment {
            val fragment = MediaPreviewDialogFragment()
            val args = Bundle()
            args.putStringArray(ARG_URIS, uris.toTypedArray())
            args.putStringArray(ARG_PREVIEWS, previews.toTypedArray())
            args.putStringArray(ARG_TYPES, types.toTypedArray())
            args.putInt(ARG_POSITION, position)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.TransparentDialog)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogMediaPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val uris = arguments?.getStringArray(ARG_URIS) ?: emptyArray()
        val previews = arguments?.getStringArray(ARG_PREVIEWS) ?: emptyArray()
        val types = arguments?.getStringArray(ARG_TYPES) ?: emptyArray()
        val startPosition = arguments?.getInt(ARG_POSITION) ?: 0

        val adapter = MediaPagerAdapter(uris.toList(), previews.toList(), types.toList()) {
            toggleUi()
        }
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 1
        binding.viewPager.setCurrentItem(startPosition, false)

        binding.viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                // Stop all other videos if we had a more complex setup, 
                // but since ViewPager2 destroys/recreates views, simple start() in bind is usually enough.
                // However, let's notify the adapter to manage playback if needed.
            }
        })

        binding.btnClose.setOnClickListener {
            dismiss()
        }

        binding.root.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).let { controller ->
                controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun toggleUi() {
        // Do nothing. The close button should always stay visible.
    }

    private fun formatTime(millis: Int): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clear high-quality bitmaps from memory when leaving the fragment
        imageCache.evictAll()
        _binding = null
    }

    inner class MediaPagerAdapter(
        private val uris: List<String>,
        private val previews: List<String>,
        private val types: List<String>,
        private val onMediaClick: () -> Unit
    ) : RecyclerView.Adapter<MediaPagerAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemMediaPagerBinding) : RecyclerView.ViewHolder(binding.root) {
            val updateHandler = Handler(Looper.getMainLooper())
            var updateProgress: Runnable? = null
            var loadJob: kotlinx.coroutines.Job? = null
            var previewJob: kotlinx.coroutines.Job? = null

            /** Tag tracking which URI this holder is currently bound to. */
            var boundUriStr: String = ""

            fun stopUpdating() {
                updateProgress?.let { updateHandler.removeCallbacks(it) }
            }

            fun cancelLoading() {
                loadJob?.cancel()
                loadJob = null
                previewJob?.cancel()
                previewJob = null
            }

            // Zoom and Pan state
            private var scaleFactor = 1.0f
            private var posX = 0f
            private var posY = 0f
            private var lastTouchX = 0f
            private var lastTouchY = 0f
            private var activePointerId = android.view.MotionEvent.INVALID_POINTER_ID

            private val scaleListener = object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    val prevScale = scaleFactor
                    scaleFactor *= detector.scaleFactor
                    scaleFactor = java.lang.Math.max(1.0f, java.lang.Math.min(scaleFactor, 5.0f))
                    
                    if (prevScale != scaleFactor) {
                        val scaleRatio = scaleFactor / prevScale
                        val w = binding.mediaContainer.width.toFloat()
                        val h = binding.mediaContainer.height.toFloat()
                        val dx = detector.focusX - w / 2f - posX
                        val dy = detector.focusY - h / 2f - posY
                        posX -= dx * (scaleRatio - 1f)
                        posY -= dy * (scaleRatio - 1f)
                        clampBounds()
                    }

                    binding.ivPreview.scaleX = scaleFactor
                    binding.ivPreview.scaleY = scaleFactor
                    binding.vvPreview.scaleX = scaleFactor
                    binding.vvPreview.scaleY = scaleFactor
                    binding.ivPreview.translationX = posX
                    binding.ivPreview.translationY = posY
                    binding.vvPreview.translationX = posX
                    binding.vvPreview.translationY = posY
                    return true
                }
            }
            private val scaleDetector = android.view.ScaleGestureDetector(binding.root.context, scaleListener)

            private val gestureListener = object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    val isVideo = binding.vvPreview.visibility == View.VISIBLE || binding.ivPlayPause.visibility == View.VISIBLE
                    if (isVideo || isTouchInsideImage(binding.ivPreview, e.x, e.y)) {
                        onMediaClick()
                    } else {
                        dismiss()
                    }
                    return true
                }

                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    if (scaleFactor > 1.0f) {
                        scaleFactor = 1.0f
                        posX = 0f
                        posY = 0f
                    } else {
                        scaleFactor = 2.5f
                        val w = binding.mediaContainer.width.toFloat()
                        val h = binding.mediaContainer.height.toFloat()
                        posX = (e.x - w / 2f) * (1f - scaleFactor)
                        posY = (e.y - h / 2f) * (1f - scaleFactor)
                        clampBounds()
                    }
                    binding.ivPreview.scaleX = scaleFactor
                    binding.ivPreview.scaleY = scaleFactor
                    binding.ivPreview.translationX = posX
                    binding.ivPreview.translationY = posY
                    binding.vvPreview.scaleX = scaleFactor
                    binding.vvPreview.scaleY = scaleFactor
                    binding.vvPreview.translationX = posX
                    binding.vvPreview.translationY = posY
                    return true
                }
            }
            private val gestureDetector = android.view.GestureDetector(binding.root.context, gestureListener)

            @android.annotation.SuppressLint("ClickableViewAccessibility")
            fun initTouchHandler() {
                val touchListener = View.OnTouchListener { view, event ->
                    scaleDetector.onTouchEvent(event)
                    gestureDetector.onTouchEvent(event)

                    val action = event.actionMasked
                    when (action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            val pointerIndex = event.actionIndex
                            lastTouchX = event.getX(pointerIndex)
                            lastTouchY = event.getY(pointerIndex)
                            activePointerId = event.getPointerId(0)
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val pointerIndex = event.findPointerIndex(activePointerId)
                            if (pointerIndex != -1) {
                                val x = event.getX(pointerIndex)
                                val y = event.getY(pointerIndex)

                                if (!scaleDetector.isInProgress && scaleFactor > 1.0f) {
                                    val dx = x - lastTouchX
                                    val dy = y - lastTouchY
                                    posX += dx
                                    posY += dy
                                    clampBounds()
                                    binding.ivPreview.translationX = posX
                                    binding.ivPreview.translationY = posY
                                    binding.vvPreview.translationX = posX
                                    binding.vvPreview.translationY = posY
                                }

                                lastTouchX = x
                                lastTouchY = y
                            }
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            activePointerId = android.view.MotionEvent.INVALID_POINTER_ID
                            if (scaleFactor <= 1.0f) {
                                posX = 0f
                                posY = 0f
                                binding.ivPreview.translationX = posX
                                binding.ivPreview.translationY = posY
                                binding.vvPreview.translationX = posX
                                binding.vvPreview.translationY = posY
                            }
                        }
                        android.view.MotionEvent.ACTION_POINTER_UP -> {
                            val pointerIndex = event.actionIndex
                            val pointerId = event.getPointerId(pointerIndex)
                            if (pointerId == activePointerId) {
                                val newPointerIndex = if (pointerIndex == 0) 1 else 0
                                lastTouchX = event.getX(newPointerIndex)
                                lastTouchY = event.getY(newPointerIndex)
                                activePointerId = event.getPointerId(newPointerIndex)
                            }
                        }
                    }

                    if (scaleFactor > 1.0f) {
                        binding.root.parent?.requestDisallowInterceptTouchEvent(true)
                    } else {
                        binding.root.parent?.requestDisallowInterceptTouchEvent(false)
                    }

                    true
                }
                binding.mediaContainer.setOnTouchListener(touchListener)
                binding.vvPreview.setOnTouchListener(touchListener)
                binding.root.setOnTouchListener(touchListener)
            }

            private fun clampBounds() {
                val w = binding.mediaContainer.width.toFloat()
                val h = binding.mediaContainer.height.toFloat()
                val maxX = (w / 2f) * (scaleFactor - 1f)
                val maxY = (h / 2f) * (scaleFactor - 1f)
                posX = java.lang.Math.max(-maxX, java.lang.Math.min(posX, maxX))
                posY = java.lang.Math.max(-maxY, java.lang.Math.min(posY, maxY))
            }

            fun resetVideoUI(isForVideo: Boolean) {
                stopUpdating()
                // Cancel in-flight coroutines so a recycled holder cannot
                // overwrite the image of the next item bound to this ViewHolder.
                cancelLoading()
                boundUriStr = ""
                binding.vvPreview.stopPlayback()
                binding.vvPreview.setVideoURI(null)
                binding.vvPreview.visibility = View.INVISIBLE
                binding.vvPreview.alpha = 0f
                binding.ivPreview.visibility = View.VISIBLE
                binding.ivPreview.alpha = 1f
                binding.ivPlayPause.visibility = if (isForVideo) View.VISIBLE else View.GONE
                binding.ivPlayPause.setImageResource(R.drawable.ic_play_white)
                binding.llVideoControls.visibility = View.GONE
                binding.pbLoading.visibility = View.GONE
                android.util.Log.d("MediaPreview", "UI Reset: isForVideo=$isForVideo")
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemMediaPagerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            binding.vvPreview.setZOrderMediaOverlay(true)
            val holder = ViewHolder(binding)
            holder.initTouchHandler()
            return holder
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uriStr = uris[position]
            val previewUriStr = previews.getOrNull(position)
            val type = types[position]

            android.util.Log.d("MediaPreview", "Binding position $position: Type=$type, URI=$uriStr, Preview=$previewUriStr")

            holder.binding.root.setOnClickListener { dismiss() }
            // Reset scale when a new item is bound to a recycled viewholder
            holder.binding.ivPreview.scaleX = 1.0f
            holder.binding.ivPreview.scaleY = 1.0f
            holder.binding.ivPreview.translationX = 0f
            holder.binding.ivPreview.translationY = 0f
            holder.binding.vvPreview.scaleX = 1.0f
            holder.binding.vvPreview.scaleY = 1.0f
            holder.binding.vvPreview.translationX = 0f
            holder.binding.vvPreview.translationY = 0f

            // resetVideoUI already calls cancelLoading() so any previous job for
            // an old position is stopped before we start new ones.
            holder.resetVideoUI(type == "VIDEO")
            holder.boundUriStr = uriStr
            holder.binding.ivPreview.setImageDrawable(null)

            // Show preview thumbnail immediately as a fast placeholder.
            // If the HQ bitmap is already cached we skip the preview entirely.
            if (!previewUriStr.isNullOrEmpty() && imageCache.get(uriStr) == null) {
                loadPreview(holder, previewUriStr, uriStr)
            } else if (previewUriStr.isNullOrEmpty()) {
                android.util.Log.w("MediaPreview", "Preview URI is empty for position $position")
            }

            if (type == "IMAGE") {

                val cached = imageCache[uriStr]
                if (cached != null) {
                    // Instant display from memory cache — hide spinner, no network needed
                    holder.binding.pbLoading.visibility = View.GONE
                    holder.binding.ivPreview.setImageBitmap(cached)
                    holder.binding.ivPreview.alpha = 1.0f
                    android.util.Log.d("MediaPreview", "Using cached bitmap for $uriStr")
                } else {
                    // Show spinner while loading HQ image
                    holder.binding.pbLoading.visibility = View.VISIBLE
                    android.util.Log.d("MediaPreview", "Showing spinner for position $position, URI=$uriStr")
                    holder.loadJob = lifecycleScope.launch {
                        try {
                            android.util.Log.d("MediaPreview", "Starting HQ load job for $uriStr")
                            val bitmap = withContext(Dispatchers.IO) {
                                loadHighQualityBitmap(holder.itemView.context, uriStr)
                            }
                            if (bitmap != null) {
                                imageCache.put(uriStr, bitmap)
                                // Guard: only update this holder if it is still
                                // displaying the URI we loaded (not recycled to another item).
                                if (holder.boundUriStr == uriStr) {
                                    holder.binding.pbLoading.visibility = View.GONE
                                    holder.binding.ivPreview.setImageBitmap(bitmap)
                                    holder.binding.ivPreview.alpha = 1.0f
                                    android.util.Log.d("MediaPreview", "HQ bitmap SET for $uriStr")
                                } else {
                                    android.util.Log.d("MediaPreview", "HQ ready but holder was recycled, skipping UI update for $uriStr")
                                }
                            } else {
                                android.util.Log.e("MediaPreview", "HQ bitmap is NULL for $uriStr")
                                if (holder.boundUriStr == uriStr) {
                                    holder.binding.pbLoading.visibility = View.GONE
                                }
                            }
                        } catch (e: Throwable) { // Catch Throwable to gracefully handle OutOfMemoryError
                            android.util.Log.e("MediaPreview", "Error or OOM in HQ loadJob for $uriStr", e)
                            if (holder.boundUriStr == uriStr) {
                                holder.binding.pbLoading.visibility = View.GONE
                            }
                        }
                    }
                }
            } else {
                // Video logic
                holder.binding.ivPlayPause.setOnClickListener {
                    if (holder.binding.vvPreview.isPlaying) {
                        holder.binding.vvPreview.pause()
                        holder.binding.ivPlayPause.setImageResource(R.drawable.ic_play_white)
                        holder.stopUpdating()
                    } else if (holder.binding.vvPreview.alpha == 1f) {
                        holder.binding.vvPreview.start()
                        holder.binding.ivPlayPause.setImageResource(R.drawable.ic_pause)
                        holder.updateProgress?.let { holder.updateHandler.post(it) }
                    } else {
                        holder.binding.pbLoading.visibility = View.VISIBLE
                        holder.binding.ivPlayPause.visibility = View.GONE
                        holder.binding.vvPreview.visibility = View.VISIBLE
                        holder.binding.vvPreview.alpha = 0f
                        holder.binding.vvPreview.setVideoURI(Uri.parse(uriStr))
                        holder.binding.vvPreview.start()
                        holder.updateProgress?.let { holder.updateHandler.post(it) }
                    }
                }

                holder.updateProgress = object : Runnable {
                    override fun run() {
                        if (holder.binding.vvPreview.isPlaying) {
                            val cp = holder.binding.vvPreview.currentPosition
                            holder.binding.sbVideoProgress.progress = cp
                            holder.binding.tvCurrentTime.text = formatTime(cp)
                        }
                        holder.updateHandler.postDelayed(this, 50)
                    }
                }

                holder.binding.vvPreview.setOnPreparedListener { mp ->
                    mp.isLooping = true
                    holder.binding.sbVideoProgress.max = holder.binding.vvPreview.duration
                    holder.binding.tvTotalTime.text = formatTime(holder.binding.vvPreview.duration)
                    holder.binding.pbLoading.visibility = View.GONE
                    holder.binding.ivPreview.visibility = View.GONE
                    holder.binding.vvPreview.alpha = 1f
                    holder.binding.ivPlayPause.visibility = View.VISIBLE
                    
                    if (mp.isPlaying || holder.binding.vvPreview.isPlaying) {
                        holder.binding.ivPlayPause.setImageResource(R.drawable.ic_pause)
                    } else {
                        holder.binding.ivPlayPause.setImageResource(R.drawable.ic_play_white)
                    }
                    
                    holder.binding.llVideoControls.visibility = View.VISIBLE
                    if (!holder.binding.vvPreview.isPlaying) {
                        holder.binding.vvPreview.seekTo(100)
                    }
                }

                holder.binding.vvPreview.setOnErrorListener { _, _, _ ->
                    holder.binding.pbLoading.visibility = View.GONE
                    holder.binding.ivPlayPause.visibility = View.VISIBLE
                    holder.binding.ivPlayPause.setImageResource(R.drawable.ic_play_white)
                    false
                }

                holder.binding.sbVideoProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                        if (f) holder.binding.vvPreview.seekTo(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
        }

        /**
         * Load a low-resolution thumbnail fast so the user sees something
         * immediately while the HQ image is still decoding.
         *
         * Uses a fixed inSampleSize=4 (or higher if the image is huge) instead
         * of running the full bounds-check + double-open pipeline of
         * loadHighQualityBitmap(), making it ~4× faster.
         */
        private fun loadPreview(holder: ViewHolder, pUri: String, oUri: String) {
            holder.previewJob = lifecycleScope.launch {
                try {
                    android.util.Log.d("MediaPreview", "Loading preview for $oUri, previewUri=$pUri")
                    val bitmap = withContext(Dispatchers.IO) {
                        loadThumbnailBitmap(holder.itemView.context, pUri)
                    }

                    if (bitmap != null) {
                        // Only paint the preview if:
                        //  1. The HQ image is not already cached (would look worse), AND
                        //  2. This holder is still bound to the same original URI.
                        if (imageCache.get(oUri) == null && holder.boundUriStr == oUri) {
                            holder.binding.ivPreview.setImageBitmap(bitmap)
                            holder.binding.ivPreview.alpha = 1.0f
                            android.util.Log.d("MediaPreview", "Preview bitmap SET for $oUri")
                        }
                    } else {
                        android.util.Log.e("MediaPreview", "Preview bitmap is NULL for $pUri")
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MediaPreview", "Error in loadPreview for $pUri", e)
                }
            }
        }

        /**
         * Decode a fast, low-quality thumbnail without the double-stream overhead.
         * Uses inSampleSize=4 (quarter resolution) which is more than enough for
         * a placeholder while the HQ image loads.
         */
        private fun loadThumbnailBitmap(context: android.content.Context, uriStr: String): Bitmap? {
            if (uriStr.isEmpty()) return null
            return try {
                val uri = Uri.parse(uriStr)
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                val stream = try {
                    context.contentResolver.openInputStream(uri)
                } catch (e: Exception) {
                    if (uri.scheme == "file") {
                        val path = uri.path
                        if (path != null) {
                            val file = java.io.File(path)
                            if (file.exists()) java.io.FileInputStream(file) else null
                        } else null
                    } else null
                }
                stream?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
                android.util.Log.e("MediaPreview", "Exception in thumbnail load for $uriStr", e)
                null
            }
        }

        private fun loadHighQualityBitmap(context: android.content.Context, uriStr: String): Bitmap? {
            if (uriStr.isEmpty()) return null
            return try {
                android.util.Log.d("MediaPreview", "Attempting to load HQ bitmap from: $uriStr")
                val uri = Uri.parse(uriStr)
                
                fun openStream(): java.io.InputStream? {
                    return try {
                        context.contentResolver.openInputStream(uri)
                    } catch (e: Exception) {
                        if (uri.scheme == "file") {
                            val path = uri.path
                            if (path != null) {
                                val file = java.io.File(path)
                                if (file.exists()) java.io.FileInputStream(file) else null
                            } else null
                        } else null
                    }
                }

                // Decode bounds first
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                val initialStream = openStream()
                if (initialStream == null) {
                    android.util.Log.e("MediaPreview", "Failed to open initial stream for HQ load: $uriStr")
                    return null
                }
                initialStream.use { BitmapFactory.decodeStream(it, null, options) }
                
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels
                
                var sampleSize = 1
                if (options.outWidth > 0 && options.outHeight > 0) {
                    while (options.outWidth / (sampleSize * 2) >= screenWidth && 
                           options.outHeight / (sampleSize * 2) >= screenHeight) {
                        sampleSize *= 2
                    }
                }
                
                android.util.Log.d("MediaPreview", "Image size: ${options.outWidth}x${options.outHeight}, SampleSize: $sampleSize")

                // Decode actual bitmap
                val finalOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decodeStream = openStream()
                val bitmap = decodeStream?.use { BitmapFactory.decodeStream(it, null, finalOptions) }
                
                if (bitmap != null) {
                    android.util.Log.d("MediaPreview", "Successfully decoded HQ bitmap")
                    app.olauncher.data.ChatStorage.rotateBitmapIfRequired(context, bitmap, uri)
                } else {
                    android.util.Log.e("MediaPreview", "BitmapFactory returned null for HQ load: $uriStr")
                    null
                }
            } catch (e: Throwable) {
                android.util.Log.e("MediaPreview", "Exception or OOM in HQ load for $uriStr", e)
                null
            }
        }

        private fun isTouchInsideImage(imageView: android.widget.ImageView, x: Float, y: Float): Boolean {
            val drawable = imageView.drawable ?: return false
            val values = FloatArray(9)
            imageView.imageMatrix.getValues(values)
            val scaleX = values[android.graphics.Matrix.MSCALE_X]
            val scaleY = values[android.graphics.Matrix.MSCALE_Y]
            val transX = values[android.graphics.Matrix.MTRANS_X]
            val transY = values[android.graphics.Matrix.MTRANS_Y]

            val left = transX
            val top = transY
            val right = left + drawable.intrinsicWidth * scaleX
            val bottom = top + drawable.intrinsicHeight * scaleY

            return x >= left && x <= right && y >= top && y <= bottom
        }

        override fun onViewAttachedToWindow(holder: ViewHolder) {
            super.onViewAttachedToWindow(holder)
            // Do not reset UI here, as it cancels the jobs started in onBindViewHolder.
        }

        override fun onViewDetachedFromWindow(holder: ViewHolder) {
            super.onViewDetachedFromWindow(holder)
            // Always ensure the play button icon matches the paused state when leaving
            if (holder.binding.vvPreview.isPlaying) {
                holder.binding.vvPreview.pause()
                holder.stopUpdating()
            }
            holder.binding.ivPlayPause.setImageResource(R.drawable.ic_play_white)
            
            // If it's a video, always reset the cursor to the start when swiped away
            if (holder.binding.vvPreview.visibility == View.VISIBLE) {
                holder.binding.vvPreview.seekTo(100) // 100ms prevents black frame
                holder.binding.sbVideoProgress.progress = 0
                holder.binding.tvCurrentTime.text = formatTime(0)
                holder.binding.ivPlayPause.visibility = View.VISIBLE
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.resetVideoUI(false)
        }

        override fun getItemCount(): Int = uris.size
    }
}
