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
            ActivityCompat.requestPermissions(
                requireActivity(), REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS
            )
        }

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
                    val safeContext = context ?: return
                    val savedUri = when (destination) {
                        is ChatStorage.MediaDestination.Internal -> {
                            FileProvider.getUriForFile(
                                safeContext,
                                "${safeContext.packageName}.fileprovider",
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

                            if (!recordEvent.hasError()) {
                                // Add small delay to ensure file is closed and ready for playback/preview
                                _binding?.root?.postDelayed({
                                    val safeContext = context ?: return@postDelayed
                                    val savedUri = when (destination) {
                                        is ChatStorage.MediaDestination.Internal -> {
                                            FileProvider.getUriForFile(
                                                safeContext,
                                                "${safeContext.packageName}.fileprovider",
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

            imageCapture = ImageCapture.Builder()
                .setTargetRotation(rotation)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            try {
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture, videoCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

        }, ContextCompat.getMainExecutor(safeContext))
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
