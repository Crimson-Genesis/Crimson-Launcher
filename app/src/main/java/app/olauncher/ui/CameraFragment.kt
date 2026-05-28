package app.olauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.GestureDetector
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.FocusMeteringAction
import android.view.OrientationEventListener
import android.view.Surface
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.ChatMessage
import app.olauncher.data.Prefs
import app.olauncher.data.ChatStorage
import app.olauncher.databinding.FragmentCameraBinding
import app.olauncher.helper.dpToPx
import app.olauncher.listener.OnSwipeTouchListener
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    enum class CameraMode { PHOTO, VIDEO }

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var currentMode = CameraMode.PHOTO
    private var camera: Camera? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewModel: MainViewModel
    private lateinit var prefs: Prefs

    private var orientationEventListener: OrientationEventListener? = null

    private val capturedUris = ArrayList<String>()
    private val capturedPreviews = ArrayList<String>()
    private val capturedTypes = ArrayList<String>()

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val mimeType = requireContext().contentResolver.getType(it) ?: ""
            val type = if (mimeType.startsWith("audio")) "AUDIO"
                       else if (mimeType.startsWith("video")) "VIDEO"
                       else "IMAGE"
            addCapturedMedia(it.toString(), type)
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        permissions.entries.forEach {
            if (it.key in REQUIRED_PERMISSIONS && !it.value) {
                allGranted = false
            }
        }
        if (allGranted) {
            startCamera()
        } else {
            try {
                findNavController().popBackStack()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate back on permission denied", e)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        prefs = Prefs(requireContext())

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
        }

        setupCameraGestures()

        binding.flCapture.setOnClickListener {
            if (currentMode == CameraMode.PHOTO) {
                takePhoto()
            } else {
                captureVideo()
            }
        }

        binding.btnFlipCamera.setOnClickListener { flipCamera() }
        binding.btnClose.setOnClickListener { findNavController().popBackStack() }

        binding.tvPhotoMode.setOnClickListener { switchMode(CameraMode.PHOTO) }
        binding.tvVideoMode.setOnClickListener { switchMode(CameraMode.VIDEO) }

        binding.btnGallery.setOnClickListener {
            if (capturedUris.isNotEmpty()) {
                // Pause camera to release resources for video preview
                val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
                cameraProviderFuture.addListener({
                    val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
                    cameraProvider.unbindAll()
                }, ContextCompat.getMainExecutor(requireContext()))

                val gallery = TempGalleryDialogFragment.newInstance(capturedUris, capturedPreviews, capturedTypes) { selectedUris ->
                    finishWithResultByUris(selectedUris)
                }
                
                // Restart camera when gallery is dismissed
                childFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewDestroyed(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment) {
                        if (f is TempGalleryDialogFragment) {
                            // Only restart if the fragment is still active and not finishing
                            if (_binding != null && isAdded && !isRemoving) {
                                try {
                                    startCamera()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to restart camera after gallery", e)
                                }
                            }
                            fm.unregisterFragmentLifecycleCallbacks(this)
                        }
                    }
                }, false)
                
                gallery.show(childFragmentManager, "temp_gallery")
            } else {
                pickMediaLauncher.launch("*/*")
            }
        }

        binding.btnDone.setOnClickListener {
            finishWithResult()
        }

        setupGalleryAlignment()
        initSwipeListener()
        updateUIForMode()

        orientationEventListener = object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
        orientationEventListener?.enable()

        // Load existing temp media
        Thread {
            try {
                val context = context ?: return@Thread
                val tempMedia = ChatStorage.getTempMedia(context, prefs)
                activity?.runOnUiThread {
                    _binding?.let { b ->
                        tempMedia.forEach { (uri, type, preview) ->
                            if (!capturedUris.contains(uri)) {
                                capturedUris.add(uri)
                                capturedPreviews.add(preview ?: "")
                                capturedTypes.add(type)
                            }
                        }
                        if (capturedUris.isNotEmpty()) {
                            b.btnDone.visibility = View.VISIBLE
                            b.btnGallery.visibility = View.VISIBLE
                            updateCaptureCount()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load existing temp media", e)
            }
        }.start()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupGalleryAlignment() {
        val galleryParams = binding.btnGallery.layoutParams as RelativeLayout.LayoutParams
        val doneParams = binding.btnDone.layoutParams as RelativeLayout.LayoutParams
        val captureParams = binding.flCapture.layoutParams as RelativeLayout.LayoutParams
        val flipParams = binding.btnFlipCamera.layoutParams as RelativeLayout.LayoutParams
        val closeParams = binding.btnClose.layoutParams as RelativeLayout.LayoutParams

        galleryParams.removeRule(RelativeLayout.ALIGN_PARENT_START)
        galleryParams.removeRule(RelativeLayout.ALIGN_PARENT_END)
        galleryParams.removeRule(RelativeLayout.LEFT_OF)
        galleryParams.removeRule(RelativeLayout.RIGHT_OF)
        
        doneParams.removeRule(RelativeLayout.ALIGN_PARENT_START)
        doneParams.removeRule(RelativeLayout.ALIGN_PARENT_END)
        doneParams.removeRule(RelativeLayout.LEFT_OF)
        doneParams.removeRule(RelativeLayout.RIGHT_OF)

        flipParams.removeRule(RelativeLayout.ALIGN_PARENT_START)
        flipParams.removeRule(RelativeLayout.ALIGN_PARENT_END)

        closeParams.removeRule(RelativeLayout.ALIGN_PARENT_START)
        closeParams.removeRule(RelativeLayout.ALIGN_PARENT_END)

        captureParams.addRule(RelativeLayout.CENTER_HORIZONTAL)

        if (prefs.homeAlignment == Gravity.END) {
            // Right alignment: [Capture (Center)] ... [Gallery] [Select (Right Corner)]
            doneParams.addRule(RelativeLayout.ALIGN_PARENT_END)
            galleryParams.addRule(RelativeLayout.LEFT_OF, R.id.btnDone)
            galleryParams.marginEnd = 16.dpToPx()
            
            // Flip button to right corner of its container
            flipParams.addRule(RelativeLayout.ALIGN_PARENT_END)

            // Close button to right corner
            closeParams.addRule(RelativeLayout.ALIGN_PARENT_END)
        } else {
            // Left alignment: [Select (Left Corner)] [Gallery] ... [Capture (Center)]
            doneParams.addRule(RelativeLayout.ALIGN_PARENT_START)
            galleryParams.addRule(RelativeLayout.RIGHT_OF, R.id.btnDone)
            galleryParams.marginStart = 16.dpToPx()
            
            // Flip button to left corner of its container
            flipParams.addRule(RelativeLayout.ALIGN_PARENT_START)

            // Close button to left corner
            closeParams.addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        
        binding.btnGallery.layoutParams = galleryParams
        binding.btnDone.layoutParams = doneParams
        binding.flCapture.layoutParams = captureParams
        binding.btnFlipCamera.layoutParams = flipParams
        binding.btnClose.layoutParams = closeParams
    }

    private fun addCapturedMedia(uri: String, type: String) {
        val safeContext = context ?: return
        val timestamp = System.currentTimeMillis()
        val previewUri = ChatStorage.createPreview(safeContext, prefs, Uri.parse(uri), type, timestamp, isTemp = true)
        
        capturedUris.add(uri)
        capturedPreviews.add(previewUri?.toString() ?: "")
        capturedTypes.add(type)
        
        _binding?.let { b ->
            b.btnDone.visibility = View.VISIBLE
            b.btnGallery.visibility = View.VISIBLE
            updateCaptureCount()
        }
    }

    private fun updateCaptureCount() {
        _binding?.let { b ->
            val count = capturedUris.size
            if (count > 0) {
                b.tvCaptureCount.text = count.toString()
                b.tvCaptureCount.visibility = View.VISIBLE
            } else {
                b.tvCaptureCount.visibility = View.GONE
            }
        }
    }

    private fun finishWithResultByUris(selectedUris: List<String>) {
        if (!isAdded || isRemoving) return

        if (selectedUris.isEmpty()) {
            findNavController().popBackStack()
            return
        }

        val finalizedUris = mutableListOf<String>()
        val finalizedPreviews = mutableListOf<String>()
        val finalTypes = mutableListOf<String>()

        selectedUris.forEach { uri ->
            val index = capturedUris.indexOf(uri)
            if (index != -1) {
                finalizedUris.add(capturedUris[index])
                finalizedPreviews.add(capturedPreviews.getOrNull(index) ?: "")
                finalTypes.add(capturedTypes[index])
            }
        }
        
        val bundle = Bundle().apply {
            putStringArrayList("uris", ArrayList(finalizedUris))
            putStringArrayList("previews", ArrayList(finalizedPreviews))
            putStringArrayList("types", ArrayList(finalTypes))
        }
        parentFragmentManager.setFragmentResult("camera_result", bundle)
        findNavController().popBackStack()
    }

    private fun finishWithResult(selectedIndices: List<Int> = emptyList()) {
        val indices = if (selectedIndices.isNotEmpty()) selectedIndices else {
            if (capturedUris.isNotEmpty()) listOf(capturedUris.lastIndex) else emptyList()
        }

        if (indices.isEmpty()) {
            findNavController().popBackStack()
            return
        }

        val finalizedUris = mutableListOf<String>()
        val finalizedPreviews = mutableListOf<String>()
        val finalTypes = mutableListOf<String>()

        indices.forEach { index ->
            finalizedUris.add(capturedUris[index])
            finalizedPreviews.add(capturedPreviews.getOrNull(index) ?: "")
            finalTypes.add(capturedTypes[index])
        }
        
        val bundle = Bundle().apply {
            putStringArrayList("uris", ArrayList(finalizedUris))
            putStringArrayList("previews", ArrayList(finalizedPreviews))
            putStringArrayList("types", ArrayList(finalTypes))
        }
        parentFragmentManager.setFragmentResult("camera_result", bundle)
        findNavController().popBackStack()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeListener() {
        val swipeTouchListener = object : OnSwipeTouchListener(requireContext()) {
            override fun onSwipeLeft() {
                if (currentMode == CameraMode.PHOTO) {
                    switchMode(CameraMode.VIDEO)
                }
            }

            override fun onSwipeRight() {
                if (currentMode == CameraMode.VIDEO) {
                    switchMode(CameraMode.PHOTO)
                }
            }
        }
        binding.cameraContainer.setOnTouchListener(swipeTouchListener)
    }

    private fun switchMode(mode: CameraMode) {
        if (currentMode == mode || recording != null) return
        currentMode = mode
        updateUIForMode()
        startCamera()
    }

    private fun updateUIForMode() {
        val context = requireContext()
        if (currentMode == CameraMode.PHOTO) {
            binding.tvPhotoMode.alpha = 1.0f
            binding.tvVideoMode.alpha = 0.5f
            binding.ivCaptureIcon.setImageResource(R.drawable.ic_camera)
            binding.ivCaptureIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.black))
            binding.btnMainCapture.setBackgroundResource(R.drawable.circle_white)
        } else {
            binding.tvPhotoMode.alpha = 0.5f
            binding.tvVideoMode.alpha = 1.0f
            binding.ivCaptureIcon.setImageResource(R.drawable.ic_video)
            binding.ivCaptureIcon.setColorFilter(ContextCompat.getColor(context, android.R.color.black))
            binding.btnMainCapture.setBackgroundResource(R.drawable.circle_white)
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val safeContext = context ?: return
        val prefs = Prefs(safeContext)
        val destination = ChatStorage.getNextMediaDestination(safeContext, prefs, "TEMP_IMAGE", ".jpg")

        val outputOptionsBuilder = when (destination) {
            is ChatStorage.MediaDestination.Internal -> ImageCapture.OutputFileOptions.Builder(destination.file)
            is ChatStorage.MediaDestination.Saf -> {
                val os = safeContext.contentResolver.openOutputStream(destination.uri)!!
                ImageCapture.OutputFileOptions.Builder(os)
            }
        }
        val outputOptions = outputOptionsBuilder.build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context ?: return),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedContext = context ?: return
                    val savedUri = when (destination) {
                        is ChatStorage.MediaDestination.Internal -> {
                            FileProvider.getUriForFile(
                                savedContext,
                                "${savedContext.packageName}.fileprovider",
                                destination.file
                            )
                        }
                        is ChatStorage.MediaDestination.Saf -> destination.uri
                    }
                    addCapturedMedia(savedUri.toString(), "IMAGE")
                }
            }
        )
    }

    private fun captureVideo() {
        val videoCapture = this.videoCapture ?: return
        binding.flCapture.isEnabled = false

        val curRecording = recording
        if (curRecording != null) {
            curRecording.stop()
            recording = null
            return
        }

        val safeContext = context ?: return
        val prefs = Prefs(safeContext)
        val destination = ChatStorage.getNextMediaDestination(safeContext, prefs, "TEMP_VIDEO", ".mp4")

        val outputOptions = when (destination) {
            is ChatStorage.MediaDestination.Internal -> FileOutputOptions.Builder(destination.file).build()
            is ChatStorage.MediaDestination.Saf -> {
                val pfd = safeContext.contentResolver.openFileDescriptor(destination.uri, "rw")!!
                FileDescriptorOutputOptions.Builder(pfd).build()
            }
        }

        val pendingRecording = when (outputOptions) {
            is FileOutputOptions -> videoCapture.output.prepareRecording(safeContext, outputOptions)
            is FileDescriptorOutputOptions -> videoCapture.output.prepareRecording(safeContext, outputOptions)
            else -> throw RuntimeException("Unsupported output options")
        }

        recording = pendingRecording
            .apply { if (ActivityCompat.checkSelfPermission(safeContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) withAudioEnabled() }
            .start(ContextCompat.getMainExecutor(safeContext)) { recordEvent ->
                _binding?.let { binding ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            binding.flCapture.isEnabled = true
                            binding.btnMainCapture.setBackgroundResource(R.drawable.circle_red)
                            binding.ivCaptureIcon.setColorFilter(ContextCompat.getColor(safeContext, android.R.color.white))
                            binding.tvRecordingTimer.visibility = View.VISIBLE
                            binding.tvRecordingTimer.text = "00:00:000"
                            binding.btnFlipCamera.visibility = View.INVISIBLE
                            binding.btnClose.visibility = View.INVISIBLE
                            binding.modeSelector.visibility = View.INVISIBLE
                        }
                        is VideoRecordEvent.Status -> {
                            val durationNanos = recordEvent.recordingStats.recordedDurationNanos
                            val millis = (durationNanos / 1_000_000) % 1000
                            val seconds = (durationNanos / 1_000_000_000) % 60
                            val minutes = (durationNanos / 60_000_000_000) % 60
                            val hours = durationNanos / 3_600_000_000_000

                            binding.tvRecordingTimer.text = if (hours > 0) {
                                String.format(Locale.US, "%02d:%02d:%02d:%03d", hours, minutes, seconds, millis)
                            } else {
                                String.format(Locale.US, "%02d:%02d:%03d", minutes, seconds, millis)
                            }
                        }
                        is VideoRecordEvent.Finalize -> {
                            binding.flCapture.isEnabled = true
                            binding.btnMainCapture.setBackgroundResource(R.drawable.circle_white)
                            binding.ivCaptureIcon.setColorFilter(ContextCompat.getColor(requireContext(), android.R.color.black))
                            binding.tvRecordingTimer.visibility = View.GONE
                            binding.btnFlipCamera.visibility = View.VISIBLE
                            binding.btnClose.visibility = View.VISIBLE
                            binding.modeSelector.visibility = View.VISIBLE

                            if (!recordEvent.hasError()) {
                                // Add small delay to ensure file is closed and ready for playback/preview
                                _binding?.root?.postDelayed({
                                    val delayedContext = context ?: return@postDelayed
                                    val savedUri = when (destination) {
                                        is ChatStorage.MediaDestination.Internal -> {
                                            FileProvider.getUriForFile(
                                                delayedContext,
                                                "${delayedContext.packageName}.fileprovider",
                                                destination.file
                                            )
                                        }
                                        is ChatStorage.MediaDestination.Saf -> destination.uri
                                    }
                                    addCapturedMedia(savedUri.toString(), "VIDEO")
                                }, 500)
                            } else {
                                recording?.close()
                                recording = null
                                Log.e(TAG, "Video capture ends with error: ${recordEvent.error}")
                            }
                        }
                    }
                }
            }
    }

    private fun flipCamera() {
        if (recording != null) return
        cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        startCamera()
    }

    private fun startCamera() {
        val safeContext = context ?: return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(safeContext)

        cameraProviderFuture.addListener({
            val safeBinding = _binding ?: return@addListener
            val cameraProvider: ProcessCameraProvider = try {
                cameraProviderFuture.get()
            } catch (e: Exception) {
                return@addListener
            }

            val rotation = safeBinding.viewFinder.display.rotation

            val preview = Preview.Builder()
                .setTargetRotation(rotation)
                .build()
                .also {
                    it.surfaceProvider = safeBinding.viewFinder.surfaceProvider
                }

            try {
                cameraProvider.unbindAll()
                camera = if (currentMode == CameraMode.PHOTO) {
                    videoCapture = null
                    imageCapture = ImageCapture.Builder()
                        .setTargetRotation(rotation)
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build()
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture
                    )
                } else {
                    imageCapture = null
                    val recorder = Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                        .build()
                    videoCapture = VideoCapture.withOutput(recorder)
                    cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, videoCapture
                    )
                }
                setupExposureSlider()
                setupZoomSlider()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(safeContext))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupCameraGestures() {
        val context = context ?: return

        val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val activeCamera = camera ?: return false
                val zoomState = activeCamera.cameraInfo.zoomState.value ?: return false
                val currentZoomRatio = zoomState.zoomRatio
                val delta = detector.scaleFactor
                activeCamera.cameraControl.setZoomRatio(currentZoomRatio * delta)
                return true
            }
        })

        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                performTapToFocus(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoomRatio()
                return true
            }
        })

        binding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress) {
                gestureDetector.onTouchEvent(event)
            }
            true
        }
    }

    private fun toggleZoomRatio() {
        val activeCamera = camera ?: return
        val zoomState = activeCamera.cameraInfo.zoomState.value ?: return
        val minZoom = zoomState.minZoomRatio
        val maxZoom = zoomState.maxZoomRatio
        val currentZoom = zoomState.zoomRatio

        val targetZoom = if (currentZoom < minZoom * 1.5f) {
            (minZoom * 2f).coerceAtMost(maxZoom)
        } else {
            minZoom
        }
        activeCamera.cameraControl.setZoomRatio(targetZoom)
    }

    private fun performTapToFocus(x: Float, y: Float) {
        val activeCamera = camera ?: return
        val factory = binding.viewFinder.meteringPointFactory
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        activeCamera.cameraControl.startFocusAndMetering(action)

        val ring = binding.viewFocusRing
        ring.translationX = x - (ring.width / 2)
        ring.translationY = y - (ring.height / 2)
        ring.visibility = View.VISIBLE
        ring.alpha = 1.0f
        ring.scaleX = 1.3f
        ring.scaleY = 1.3f

        ring.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .setDuration(300)
            .withEndAction {
                ring.animate()
                    .alpha(0.0f)
                    .setStartDelay(1000)
                    .setDuration(300)
                    .withEndAction {
                        ring.visibility = View.GONE
                    }
                    .start()
            }
            .start()
    }

    private fun setupExposureSlider() {
        val activeCamera = camera ?: return
        val exposureState = activeCamera.cameraInfo.exposureState
        if (exposureState.isExposureCompensationSupported) {
            val range = exposureState.exposureCompensationRange
            binding.sbExposure.max = range.upper - range.lower
            val currentExposure = exposureState.exposureCompensationIndex
            binding.sbExposure.progress = currentExposure - range.lower
            binding.llExposureContainer.visibility = View.VISIBLE

            binding.sbExposure.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val newExposureIndex = progress + range.lower
                        activeCamera.cameraControl.setExposureCompensationIndex(newExposureIndex)
                    }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            })
        } else {
            binding.llExposureContainer.visibility = View.GONE
        }
    }

    private fun setupZoomSlider() {
        val activeCamera = camera ?: return

        activeCamera.cameraInfo.zoomState.observe(viewLifecycleOwner) { zoomState ->
            val minZoom = zoomState.minZoomRatio
            val maxZoom = zoomState.maxZoomRatio
            val currentZoom = zoomState.zoomRatio

            val formattedCurrent = String.format(Locale.US, "%.1fx", currentZoom)
            binding.tvZoomIndicator.text = formattedCurrent

            binding.tvZoomMin.text = String.format(Locale.US, "%.1fx", minZoom)
            binding.tvZoomMax.text = String.format(Locale.US, "%.1fx", maxZoom)

            val progress = (zoomState.linearZoom * 100).toInt()
            binding.sbZoom.progress = progress

            binding.tvZoomIndicator.visibility = View.VISIBLE
            binding.llZoomContainer.visibility = View.VISIBLE
        }

        binding.sbZoom.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val linearZoom = progress / 100f
                    activeCamera.cameraControl.setLinearZoom(linearZoom)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        orientationEventListener?.disable()
        recording?.stop()
        recording = null
        cameraExecutor.shutdown()
        _binding = null
    }

    companion object {
        private const val TAG = "CameraXApp"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
