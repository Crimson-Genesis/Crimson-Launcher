package app.olauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import app.olauncher.BuildConfig
import app.olauncher.MainActivity
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.*
import app.olauncher.listener.OnSwipeTouchListener
import java.util.Calendar
import java.util.Locale
import kotlin.math.round

class SettingsFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            toggleLockscreenTodo()
        } else {
            requireContext().showToast(getString(R.string.notification_permission_required))
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")
        viewModel.checkIsCrimsonDefault()

        populateKeyboardText()
        populateScreenTimeOnOff()
        populateTextSize()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeDownAction()
        populateLogFolderPath()
        populateLoggingToggle()
        populateHardcoreMode()
        populateDailyResetTime()
        populateLockscreenTodo()
        initClickListeners()
        initObservers()
        initSwipeListener()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeListener() {
        val swipeTouchListener = object : OnSwipeTouchListener(requireContext()) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                findNavController().navigate(R.id.action_settingsFragment_to_todoFragment)
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                findNavController().navigate(R.id.action_settingsFragment_to_rightPageFragment)
            }
        }
        binding.root.setOnTouchListener(swipeTouchListener)
        binding.scrollView.setOnTouchListener(swipeTouchListener)
    }

    override fun onClick(view: View) {
        binding.dateTimeSelectLayout.isVisible = false
        binding.swipeDownSelectLayout.isVisible = false

        if (binding.textSizesLayout.isVisible) {
            if (view.id != R.id.textSizeMinus && view.id != R.id.textSizePlus && view.id != R.id.textSizeValue && view.id != R.id.textSizeCurrent) {
                binding.textSizesLayout.isVisible = false
                applyTextSizeScale()
            }
        }
        binding.alignmentSelectLayout.isVisible = false

        when (view.id) {
            R.id.olauncherHiddenApps -> showHiddenApps()
            R.id.screenTimeOnOff -> viewModel.showDialog.postValue(Constants.Dialog.DIGITAL_WELLBEING)
            R.id.appInfo -> openAppInfo(requireContext(), Process.myUserHandle(), BuildConfig.APPLICATION_ID)
            R.id.setLauncher -> viewModel.resetLauncherLiveData.call()
            R.id.autoShowKeyboard -> toggleKeyboardText()
            R.id.alignment -> binding.alignmentSelectLayout.isVisible = true
            R.id.alignmentLeft -> viewModel.updateHomeAlignment(Gravity.START)
            R.id.alignmentRight -> viewModel.updateHomeAlignment(Gravity.END)
            R.id.statusBar -> toggleStatusBar()
            R.id.dateTime -> binding.dateTimeSelectLayout.isVisible = true
            R.id.dateTimeOn -> toggleDateTime(Constants.DateTime.ON)
            R.id.dateTimeOff -> toggleDateTime(Constants.DateTime.OFF)
            R.id.dateOnly -> toggleDateTime(Constants.DateTime.DATE_ONLY)

            R.id.textSizeValue -> {
                if (binding.textSizesLayout.isVisible) {
                    binding.textSizesLayout.isVisible = false
                    applyTextSizeScale()
                } else {
                    binding.textSizesLayout.isVisible = true
                }
            }

            R.id.textSizeCurrent -> {
                binding.textSizesLayout.isVisible = false
                applyTextSizeScale()
            }

            R.id.textSizeMinus -> adjustTextSizePreview(-0.1f)
            R.id.textSizePlus -> adjustTextSizePreview(0.1f)

            R.id.swipeDownAction -> binding.swipeDownSelectLayout.isVisible = true
            R.id.notifications -> updateSwipeDownAction(Constants.SwipeDownAction.NOTIFICATIONS)
            R.id.search -> updateSwipeDownAction(Constants.SwipeDownAction.SEARCH)
            
            R.id.backupData -> {
                (requireActivity() as MainActivity).launchBackupPicker("crimson_launcher_backup.json")
            }
            R.id.restoreData -> {
                (requireActivity() as MainActivity).launchRestorePicker()
            }
            R.id.clearTodoData -> {
                ClearDataOptionsDialogFragment().show(parentFragmentManager, "clear_data_options")
            }
            R.id.logFolderPicker -> {
                (requireActivity() as MainActivity).launchLogFolderPicker()
            }
            R.id.loggingToggle -> toggleLogging()
            R.id.hardcoreMode -> toggleHardcoreMode()
            R.id.dailyResetTime -> showTimePickerDialog()
            R.id.lockscreenTodoToggle -> checkAndToggleLockscreenTodo()
        }
    }

    override fun onLongClick(view: View): Boolean {
        return true
    }

    private fun initClickListeners() {
        binding.olauncherHiddenApps.setOnClickListener(this)
        binding.appInfo.setOnClickListener(this)
        binding.setLauncher.setOnClickListener(this)
        binding.autoShowKeyboard.setOnClickListener(this)
        binding.screenTimeOnOff.setOnClickListener(this)
        binding.alignment.setOnClickListener(this)
        binding.alignmentLeft.setOnClickListener(this)
        binding.alignmentRight.setOnClickListener(this)
        binding.statusBar.setOnClickListener(this)
        binding.dateTime.setOnClickListener(this)
        binding.dateTimeOn.setOnClickListener(this)
        binding.dateTimeOff.setOnClickListener(this)
        binding.dateOnly.setOnClickListener(this)
        binding.swipeDownAction.setOnClickListener(this)
        binding.search.setOnClickListener(this)
        binding.notifications.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.textSizeCurrent.setOnClickListener(this)

        binding.textSizeMinus.setOnClickListener(this)
        binding.textSizePlus.setOnClickListener(this)

        binding.backupData.setOnClickListener(this)
        binding.restoreData.setOnClickListener(this)
        binding.clearTodoData.setOnClickListener(this)

        binding.logFolderPicker.setOnClickListener(this)
        binding.loggingToggle.setOnClickListener(this)
        binding.hardcoreMode.setOnClickListener(this)
        binding.dailyResetTime.setOnClickListener(this)
        binding.lockscreenTodoToggle?.setOnClickListener(this)

        binding.alignment.setOnLongClickListener(this)
    }

    private fun initObservers() {
        viewModel.isCrimsonDefault.observe(viewLifecycleOwner) {
            if (it) {
                binding.setLauncher.text = getString(R.string.change_default_launcher)
            }
        }
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            populateAlignment()
        }
    }

    private fun toggleStatusBar() {
        prefs.showStatusBar = !prefs.showStatusBar
        populateStatusBar()
        EventLogger.log(requireContext(), LogEvent.SettingsChanged("status_bar", prefs.showStatusBar))
    }

    private fun populateStatusBar() {
        if (prefs.showStatusBar) {
            showStatusBar()
            binding.statusBar.text = getString(R.string.on)
        } else {
            hideStatusBar()
            binding.statusBar.text = getString(R.string.off)
        }
    }

    private fun toggleDateTime(selected: Int) {
        prefs.dateTimeVisibility = selected
        populateDateTime()
        viewModel.toggleDateTime()
        EventLogger.log(requireContext(), LogEvent.SettingsChanged("date_time_visibility", selected))
    }

    private fun populateDateTime() {
        binding.dateTime.text = getString(
            when (prefs.dateTimeVisibility) {
                Constants.DateTime.DATE_ONLY -> R.string.date
                Constants.DateTime.ON -> R.string.on
                else -> R.string.off
            }
        )
    }

    private fun showStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.show(WindowInsets.Type.statusBars())
        else
            @Suppress("DEPRECATION", "InlinedApi")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            }
    }

    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            requireActivity().window.insetsController?.hide(WindowInsets.Type.statusBars())
        else {
            @Suppress("DEPRECATION")
            requireActivity().window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
    }

    private fun showHiddenApps() {
        if (prefs.hiddenApps.isEmpty()) {
            requireContext().showToast(getString(R.string.no_hidden_apps))
            return
        }
        viewModel.getHiddenApps()
        findNavController().navigate(
            R.id.action_settingsFragment_to_appListFragment,
            bundleOf(Constants.Key.FLAG to Constants.FLAG_HIDDEN_APPS)
        )
    }

    private var pendingTextSizeScale: Float = -1f

    private fun adjustTextSizePreview(delta: Float) {
        val minScale = 0.5f
        val maxScale = 1.2f
        val current = if (pendingTextSizeScale > 0) pendingTextSizeScale else prefs.textSizeScale
        val newScale = round((current + delta) * 10f) / 10f
        val clamped = newScale.coerceIn(minScale, maxScale)
        if (clamped == current) return
        pendingTextSizeScale = clamped
        val formatted = String.format(Locale.US, "%.1f", clamped)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun applyTextSizeScale() {
        val activity = activity ?: return
        if (activity.isFinishing || activity.isDestroyed || !isAdded) return

        if (pendingTextSizeScale < 0 || prefs.textSizeScale == pendingTextSizeScale) {
            pendingTextSizeScale = -1f
            return
        }
        prefs.textSizeScale = pendingTextSizeScale
        EventLogger.log(requireContext(), LogEvent.SettingsChanged("text_size_scale", pendingTextSizeScale))
        pendingTextSizeScale = -1f
        activity.recreate()
    }

    private fun toggleKeyboardText() {
        if (prefs.autoShowKeyboard && prefs.keyboardMessageShown.not()) {
            viewModel.showDialog.postValue(Constants.Dialog.KEYBOARD)
            prefs.keyboardMessageShown = true
        } else {
            prefs.autoShowKeyboard = !prefs.autoShowKeyboard
            populateKeyboardText()
        }
    }

    private fun populateTextSize() {
        val formatted = String.format(Locale.US, "%.1f", prefs.textSizeScale)
        binding.textSizeValue.text = formatted
        binding.textSizeCurrent.text = formatted
    }

    private fun populateScreenTimeOnOff() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (requireContext().appUsagePermissionGranted()) binding.screenTimeOnOff.text = getString(R.string.on)
            else binding.screenTimeOnOff.text = getString(R.string.off)
        } else binding.screenTimeLayout.isVisible = false
    }

    private fun populateKeyboardText() {
        if (prefs.autoShowKeyboard) binding.autoShowKeyboard.text = getString(R.string.on)
        else binding.autoShowKeyboard.text = getString(R.string.off)
    }

    private fun populateAlignment() {
        when (prefs.homeAlignment) {
            Gravity.START -> binding.alignment.text = getString(R.string.left)
            Gravity.END -> binding.alignment.text = getString(R.string.right)
        }
    }

    private fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            else -> getString(R.string.search)
        }
    }

    private fun updateSwipeDownAction(swipeDownFor: Int) {
        if (prefs.swipeDownAction == swipeDownFor) return
        prefs.swipeDownAction = swipeDownFor
        populateSwipeDownAction()
        EventLogger.log(requireContext(), LogEvent.SettingsChanged("swipe_down_action", swipeDownFor))
    }

    private fun populateLogFolderPath() {
        val uriStr = prefs.logFolderUri
        binding.logFolderPath.text = if (uriStr == null) {
            "Internal storage (default)"
        } else {
            val uri = Uri.parse(uriStr)
            val documentFile = DocumentFile.fromTreeUri(requireContext(), uri)
            documentFile?.name ?: uri.path ?: uriStr
        }
    }

    private fun toggleLogging() {
        val ts = System.currentTimeMillis()
        if (prefs.isLoggingEnabled) {
            EventLogger.log(requireContext(), LogEvent.LoggingDisabled(ts))
            prefs.isLoggingEnabled = false
        } else {
            prefs.isLoggingEnabled = true
            EventLogger.log(requireContext(), LogEvent.LoggingEnabled(ts))
        }
        populateLoggingToggle()
    }

    private fun populateLoggingToggle() {
        if (prefs.isLoggingEnabled) binding.loggingToggle.text = getString(R.string.on)
        else binding.loggingToggle.text = getString(R.string.off)
    }

    private fun toggleHardcoreMode() {
        if (prefs.hardcoreMode) {
            prefs.hardcoreMode = false
            EventLogger.log(requireContext(), LogEvent.HardcoreModeToggled(false))
            populateHardcoreMode()
        } else {
            HardcoreModeConfirmDialogFragment.newInstance {
                EventLogger.log(requireContext(), LogEvent.HardcoreModeToggled(true))
                populateHardcoreMode()
            }.show(parentFragmentManager, "hardcore_confirm")
        }
    }

    private fun populateHardcoreMode() {
        if (prefs.hardcoreMode) binding.hardcoreMode.text = getString(R.string.on)
        else binding.hardcoreMode.text = getString(R.string.off)
    }

    private fun populateDailyResetTime() {
        val totalMinutes = prefs.resetTimeMinutes
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        binding.dailyResetTime.text = String.format(Locale.US, "%02d:%02d", hours, minutes)
    }

    private fun showTimePickerDialog() {
        val totalMinutes = prefs.resetTimeMinutes
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60

        TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
            val oldVal = prefs.resetTimeMinutes
            val newVal = selectedHour * 60 + selectedMinute
            prefs.resetTimeMinutes = newVal
            
            // Realign logical day so the next reset happens at the boundary
            prefs.lastResetDayKey = prefs.getLogicalDayKey(System.currentTimeMillis())
            prefs.shownOnDayOfYear = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

            populateDailyResetTime()
            EventLogger.log(requireContext(), LogEvent.ResetTimeChanged(oldVal, newVal))
        }, hours, minutes, true).show()
    }

    private fun checkAndToggleLockscreenTodo() {
        if (!prefs.showLockscreenTodo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        toggleLockscreenTodo()
    }

    private fun toggleLockscreenTodo() {
        prefs.showLockscreenTodo = !prefs.showLockscreenTodo
        populateLockscreenTodo()
        
        if (prefs.showLockscreenTodo) {
            TodoNotificationService.start(requireContext())
        } else {
            TodoNotificationService.stop(requireContext())
        }
        EventLogger.log(requireContext(), LogEvent.SettingsChanged("lockscreen_todo", prefs.showLockscreenTodo))
    }

    private fun populateLockscreenTodo() {
        binding.lockscreenTodoToggle?.text = if (prefs.showLockscreenTodo) getString(R.string.on)
        else getString(R.string.off)
    }

    override fun onResume() {
        super.onResume()
        populateLogFolderPath()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        viewModel.checkForMessages.call()
        super.onDestroy()
    }
}
