package app.olauncher.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.core.net.toUri
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
import app.olauncher.data.ChatStorage
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.LauncherApp
import app.olauncher.data.AppModel
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.GestureDetector
import android.view.MotionEvent
import kotlin.math.abs
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.TextView
import android.widget.LinearLayout
import app.olauncher.databinding.FragmentSettingsBinding
import app.olauncher.helper.*
import app.olauncher.listener.OnSwipeTouchListener
import java.util.Calendar
import java.util.Locale
import kotlin.math.round

class SettingsFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var swipeDownAdapter: SwipeDownAppAdapter

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

        swipeDownAdapter = SwipeDownAppAdapter(
            mutableListOf(),
            onMoveUp = { position ->
                val list = prefs.getSwipeDownAppList().toMutableList()
                if (position > 0 && position in list.indices) {
                    val item = list.removeAt(position)
                    list.add(position - 1, item)
                    prefs.setSwipeDownAppList(list)
                    swipeDownAdapter.expandedPosition = position - 1
                    populateSwipeDownAction()
                }
            },
            onMoveDown = { position ->
                val list = prefs.getSwipeDownAppList().toMutableList()
                if (position in list.indices && position < list.size - 1) {
                    val item = list.removeAt(position)
                    list.add(position + 1, item)
                    prefs.setSwipeDownAppList(list)
                    swipeDownAdapter.expandedPosition = position + 1
                    populateSwipeDownAction()
                }
            },
            onEdit = { position ->
                showEditSwipeDownAppDialog(position)
            },
            onDelete = { position ->
                val list = prefs.getSwipeDownAppList().toMutableList()
                if (position in list.indices) {
                    list.removeAt(position)
                    prefs.setSwipeDownAppList(list)
                    swipeDownAdapter.expandedPosition = -1
                    populateSwipeDownAction()
                }
            },
            onManageGroup = { position ->
                showManageGroupDialog(position)
            }
        )
        binding.swipeDownAppList.adapter = swipeDownAdapter
        binding.swipeDownAppList.layoutManager = LinearLayoutManager(requireContext())
        


        binding.btnSwipeDownAddApp.setOnClickListener {
            viewModel.swipeDownAppEditingIndex = -1
            selectSwipeDownApp()
        }

        populateKeyboardText()
        populateScreenTimeOnOff()
        populateTextSize()
        populateAlignment()
        populateStatusBar()
        populateDateTime()
        populateSwipeDownAction()
        populateStorageFolderPath()
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
                try { findNavController().navigate(R.id.action_settingsFragment_to_todoFragment) } catch (_: Exception) {}
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                try { findNavController().navigate(R.id.action_settingsFragment_to_rightPageFragment) } catch (_: Exception) {}
            }
        }
        binding.root.setOnTouchListener(swipeTouchListener)
        binding.scrollView.setOnTouchListener(swipeTouchListener)

        // Register at the Activity level so ALL touches (including those consumed by RecyclerView
        // items) are forwarded to the gesture detector before any view can claim them.
        (requireActivity() as MainActivity).touchEventForwarder = { ev ->
            swipeTouchListener.onTouch(binding.root, ev)
        }
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
            R.id.dateTimeOnWithSec -> toggleDateTime(Constants.DateTime.ON_WITH_SEC)

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
            R.id.appLauncher -> updateSwipeDownAction(Constants.SwipeDownAction.APP_LAUNCHER)
            
            R.id.backupData -> {
                (requireActivity() as MainActivity).launchBackupPicker("crimson_launcher_backup.zip")
            }
            R.id.restoreData -> {
                (requireActivity() as MainActivity).launchRestorePicker()
            }
            R.id.clearTodoData -> {
                ClearDataOptionsDialogFragment().show(parentFragmentManager, "clear_data_options")
            }
            R.id.storageFolderPicker -> {
                (requireActivity() as MainActivity).launchStorageFolderPicker()
            }
            R.id.viewLogs -> {
                findNavController().navigate(R.id.logsFragment)
            }
            R.id.clearChat -> {
                ClearChatConfirmDialogFragment().show(parentFragmentManager, "clear_chat_confirm")
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
        binding.dateTimeOnWithSec.setOnClickListener(this)
        binding.swipeDownAction.setOnClickListener(this)
        binding.search.setOnClickListener(this)
        binding.notifications.setOnClickListener(this)
        binding.appLauncher.setOnClickListener(this)
        binding.textSizeValue.setOnClickListener(this)
        binding.textSizeCurrent.setOnClickListener(this)

        binding.textSizeMinus.setOnClickListener(this)
        binding.textSizePlus.setOnClickListener(this)

        binding.backupData.setOnClickListener(this)
        binding.restoreData.setOnClickListener(this)
        binding.clearTodoData.setOnClickListener(this)

        binding.storageFolderPicker?.setOnClickListener(this)
        binding.viewLogs?.setOnClickListener(this)
        binding.clearChat?.setOnClickListener(this)
        binding.loggingToggle.setOnClickListener(this)
        binding.hardcoreMode.setOnClickListener(this)
        binding.dailyResetTime.setOnClickListener(this)
        binding.lockscreenTodoToggle.setOnClickListener(this)

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
                Constants.DateTime.ON_WITH_SEC -> R.string.plus_sec
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

    fun populateSwipeDownAction() {
        binding.swipeDownAction.text = when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> getString(R.string.notifications)
            Constants.SwipeDownAction.APP_LAUNCHER -> getString(R.string.app_launcher)
            else -> getString(R.string.search)
        }
        val isAppLauncher = prefs.swipeDownAction == Constants.SwipeDownAction.APP_LAUNCHER
        binding.flSwipeDownAppSelect.isVisible = isAppLauncher
        if (isAppLauncher) {
            swipeDownAdapter.updateItems(prefs.getSwipeDownAppList())
        }
    }

    private fun updateSwipeDownAction(swipeDownFor: Int) {
        if (prefs.swipeDownAction == swipeDownFor) return
        prefs.swipeDownAction = swipeDownFor
        populateSwipeDownAction()
        EventLogger.log(requireContext(), LogEvent.SettingsChanged("swipe_down_action", swipeDownFor))
    }

    private fun selectSwipeDownApp() {
        AppPickerDialogFragment.newInstance { selectedApp ->
            val list = prefs.getSwipeDownAppList().toMutableList()
            list.add(selectedApp)
            prefs.setSwipeDownAppList(list)
            swipeDownAdapter.updateItems(list)
            populateSwipeDownAction()
        }.show(childFragmentManager, "app_picker")
    }

    private fun showEditSwipeDownAppDialog(position: Int) {
        AppPickerDialogFragment.newInstance { selectedApp ->
            val list = prefs.getSwipeDownAppList().toMutableList()
            if (position in list.indices) {
                val oldApp = list[position]
                list[position] = selectedApp.copy(
                    customLabel = oldApp.customLabel,
                    backgroundApps = oldApp.backgroundApps
                )
                prefs.setSwipeDownAppList(list)
                swipeDownAdapter.updateItems(list)
                populateSwipeDownAction()
            }
        }.show(childFragmentManager, "app_picker")
    }

    private fun showManageGroupDialog(position: Int) {
        AppGroupDialogFragment.newInstance(position).show(childFragmentManager, "manage_group")
    }

    fun setSettingsBlur(enabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enabled) {
                val blurEffect = android.graphics.RenderEffect.createBlurEffect(15f, 15f, android.graphics.Shader.TileMode.CLAMP)
                binding.scrollView.setRenderEffect(blurEffect)
            } else {
                binding.scrollView.setRenderEffect(null)
            }
        }
    }





    private fun populateStorageFolderPath() {
        val uriStr = prefs.storageFolderUri
        binding.storageFolderPath?.text = if (uriStr == null) {
            "Internal storage (default)"
        } else {
            val uri = uriStr.toUri()
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
        binding.lockscreenTodoToggle.text = if (prefs.showLockscreenTodo) getString(R.string.on)
        else getString(R.string.off)
    }

    override fun onResume() {
        super.onResume()
        populateStorageFolderPath()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Unregister the activity-level touch forwarder so it doesn't leak into other fragments
        (activity as? MainActivity)?.touchEventForwarder = null
        _binding = null
    }

    override fun onDestroy() {
        if (::viewModel.isInitialized) {
            viewModel.checkForMessages.call()
        }
        super.onDestroy()
    }
}

class SwipeDownAppAdapter(
    private var items: MutableList<LauncherApp>,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onManageGroup: (Int) -> Unit
) : RecyclerView.Adapter<SwipeDownAppAdapter.ViewHolder>() {

    var expandedPosition = -1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appLabel: android.widget.TextView = view.findViewById(R.id.appLabel)
        val controlLayout: View = view.findViewById(R.id.controlLayout)
        val btnMoveUp: View = view.findViewById(R.id.btnMoveUp)
        val btnMoveDown: View = view.findViewById(R.id.btnMoveDown)
        val btnEdit: View = view.findViewById(R.id.btnEdit)
        val btnDelete: View = view.findViewById(R.id.btnDelete)
        val btnGroup: View = view.findViewById(R.id.btnGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_swipe_down_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.appLabel.text = item.displayName

        val isExpanded = position == expandedPosition
        holder.controlLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            val previousExpanded = expandedPosition
            expandedPosition = if (isExpanded) -1 else holder.bindingAdapterPosition
            notifyItemChanged(previousExpanded)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        holder.btnMoveUp.setOnClickListener { onMoveUp(holder.bindingAdapterPosition) }
        holder.btnMoveDown.setOnClickListener { onMoveDown(holder.bindingAdapterPosition) }
        holder.btnEdit.setOnClickListener { onEdit(holder.bindingAdapterPosition) }
        holder.btnDelete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
        holder.btnGroup.setOnClickListener { onManageGroup(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<LauncherApp>) {
        items.clear()
        items.addAll(newItems)
        if (expandedPosition >= items.size) {
            expandedPosition = -1
        }
        notifyDataSetChanged()
    }
}

class AppGroupDialogFragment : DialogFragment() {
    private var groupIndex: Int = -1
    private lateinit var prefs: Prefs
    private lateinit var groupApps: MutableList<LauncherApp>
    private lateinit var adapter: GroupAppAdapter

    companion object {
        fun newInstance(index: Int): AppGroupDialogFragment {
            return AppGroupDialogFragment().apply {
                arguments = bundleOf("index" to index)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupIndex = arguments?.getInt("index") ?: -1
        prefs = Prefs(requireContext())
        val mainAppList = prefs.getSwipeDownAppList()
        val mainApp = mainAppList.getOrNull(groupIndex)
        groupApps = mutableListOf()
        if (mainApp != null) {
            groupApps.add(mainApp.copy(backgroundApps = emptyList()))
            groupApps.addAll(mainApp.backgroundApps)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        dialog?.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        (parentFragment as? SettingsFragment)?.setSettingsBlur(true)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        (parentFragment as? SettingsFragment)?.setSettingsBlur(false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.dialog_manage_app_group, container, false)
        val groupTitle: TextView = view.findViewById(R.id.groupTitle)
        val groupTitleEditLayout: View = view.findViewById(R.id.groupTitleEditLayout)
        val groupTitleInput: android.widget.EditText = view.findViewById(R.id.groupTitleInput)
        val btnSaveTitle: View = view.findViewById(R.id.btnSaveTitle)
        val groupAppList: RecyclerView = view.findViewById(R.id.groupAppList)
        val btnGroupAddApp: TextView = view.findViewById(R.id.btnGroupAddApp)
        val btnGroupDone: TextView = view.findViewById(R.id.btnGroupDone)

        val mainAppList = prefs.getSwipeDownAppList()
        val mainApp = mainAppList.getOrNull(groupIndex)
        var currentTitle = mainApp?.customLabel ?: mainApp?.label ?: "Unnamed Group"
        groupTitle.text = currentTitle

        groupTitle.setOnClickListener {
            groupTitle.visibility = View.GONE
            groupTitleEditLayout.visibility = View.VISIBLE
            groupTitleInput.setText(currentTitle)
            groupTitleInput.setSelection(currentTitle.length)
            groupTitleInput.requestFocus()
            
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(groupTitleInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }

        val saveAction = {
            val newName = groupTitleInput.text.toString().trim()
            if (newName.isNotEmpty()) {
                currentTitle = newName
                groupTitle.text = newName
                saveGroupChanges(newName)
            }
            groupTitle.visibility = View.VISIBLE
            groupTitleEditLayout.visibility = View.GONE
            
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(groupTitleInput.windowToken, 0)
        }

        btnSaveTitle.setOnClickListener {
            saveAction()
        }

        groupTitleInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveAction()
                true
            } else {
                false
            }
        }

        adapter = GroupAppAdapter(
            groupApps,
            onMoveUp = { pos ->
                if (pos > 0 && pos in groupApps.indices) {
                    val item = groupApps.removeAt(pos)
                    groupApps.add(pos - 1, item)
                    adapter.expandedPosition = pos - 1
                    adapter.notifyDataSetChanged()
                    saveGroupChanges(null)
                }
            },
            onMoveDown = { pos ->
                if (pos in groupApps.indices && pos < groupApps.size - 1) {
                    val item = groupApps.removeAt(pos)
                    groupApps.add(pos + 1, item)
                    adapter.expandedPosition = pos + 1
                    adapter.notifyDataSetChanged()
                    saveGroupChanges(null)
                }
            },
            onDelete = { pos ->
                if (pos in groupApps.indices) {
                    groupApps.removeAt(pos)
                    adapter.expandedPosition = -1
                    adapter.notifyDataSetChanged()
                    saveGroupChanges(null)
                }
            }
        )
        groupAppList.adapter = adapter
        groupAppList.layoutManager = LinearLayoutManager(requireContext())

        btnGroupAddApp.setOnClickListener {
            AppPickerDialogFragment.newInstance { selectedApp ->
                groupApps.add(selectedApp)
                adapter.notifyDataSetChanged()
                saveGroupChanges(null)
            }.show(childFragmentManager, "app_picker")
        }

        btnGroupDone.setOnClickListener {
            dismiss()
        }

        return view
    }

    private fun saveGroupChanges(customName: String?) {
        val mainAppList = prefs.getSwipeDownAppList().toMutableList()
        val mainApp = mainAppList.getOrNull(groupIndex) ?: return
        
        if (groupApps.isNotEmpty()) {
            val primaryApp = groupApps[0]
            val bgApps = groupApps.subList(1, groupApps.size)
            val finalCustomName = customName ?: mainApp.customLabel
            
            mainAppList[groupIndex] = LauncherApp(
                packageName = primaryApp.packageName,
                label = primaryApp.label,
                userHandle = primaryApp.userHandle,
                customLabel = finalCustomName,
                backgroundApps = bgApps
            )
        } else {
            mainAppList[groupIndex] = LauncherApp(
                packageName = "",
                label = "",
                userHandle = "",
                customLabel = customName ?: mainApp.customLabel,
                backgroundApps = emptyList()
            )
        }
        
        prefs.setSwipeDownAppList(mainAppList)
        (parentFragment as? SettingsFragment)?.populateSwipeDownAction()
    }
}

class GroupAppAdapter(
    private var items: MutableList<LauncherApp>,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<GroupAppAdapter.ViewHolder>() {

    var expandedPosition = -1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val appLabel: android.widget.TextView = view.findViewById(R.id.appLabel)
        val controlLayout: View = view.findViewById(R.id.controlLayout)
        val btnMoveUp: View = view.findViewById(R.id.btnMoveUp)
        val btnMoveDown: View = view.findViewById(R.id.btnMoveDown)
        val btnDelete: View = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        if (position == 0) {
            holder.appLabel.text = "${item.label} (Primary)"
        } else {
            holder.appLabel.text = item.displayName
        }

        val isExpanded = position == expandedPosition
        holder.controlLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            val previousExpanded = expandedPosition
            expandedPosition = if (isExpanded) -1 else holder.bindingAdapterPosition
            notifyItemChanged(previousExpanded)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        holder.btnMoveUp.setOnClickListener { onMoveUp(holder.bindingAdapterPosition) }
        holder.btnMoveDown.setOnClickListener { onMoveDown(holder.bindingAdapterPosition) }
        holder.btnDelete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = items.size
}

class AppPickerDialogFragment : DialogFragment() {
    private var onAppSelected: ((LauncherApp) -> Unit)? = null
    private val searchResults = mutableListOf<AppModel>()
    private val allApps = mutableListOf<AppModel>()
    private lateinit var adapter: PickerAdapter

    companion object {
        fun newInstance(onAppSelected: (LauncherApp) -> Unit): AppPickerDialogFragment {
            return AppPickerDialogFragment().apply {
                this.onAppSelected = onAppSelected
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.60).toInt()
        dialog?.window?.setLayout(width, height)
        
        val parent = parentFragment
        if (parent is SettingsFragment) {
            parent.setSettingsBlur(true)
        } else if (parent is AppGroupDialogFragment) {
            (parent.parentFragment as? SettingsFragment)?.setSettingsBlur(true)
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        val parent = parentFragment
        if (parent is SettingsFragment) {
            parent.setSettingsBlur(false)
        } else if (parent is AppGroupDialogFragment) {
            (parent.parentFragment as? SettingsFragment)?.setSettingsBlur(true)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val context = requireContext()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundResource(R.drawable.rounded_rect_shade_color)
        }

        val searchInput = android.widget.EditText(context).apply {
            hint = "Search apps..."
            setSingleLine(true)
            textSize = 18f
            setPadding(24, 24, 24, 24)
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundResource(R.drawable.rounded_rect_transparent)
        }
        layout.addView(searchInput)

        val list = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = 16
            }
        }
        layout.addView(list)

        adapter = PickerAdapter(searchResults) { selectedAppModel ->
            onAppSelected?.invoke(LauncherApp(
                packageName = selectedAppModel.appPackage,
                label = selectedAppModel.appLabel,
                userHandle = selectedAppModel.user.toString()
            ))
            dismiss()
        }
        list.adapter = adapter
        list.layoutManager = LinearLayoutManager(context)

        lifecycleScope.launch {
            val loaded = getAppsList(context, Prefs(context), includeRegularApps = true)
            allApps.clear()
            allApps.addAll(loaded)
            searchResults.clear()
            searchResults.addAll(loaded)
            adapter.notifyDataSetChanged()
        }

        searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString().orEmpty().lowercase()
                searchResults.clear()
                if (query.isEmpty()) {
                    searchResults.addAll(allApps)
                } else {
                    searchResults.addAll(allApps.filter { it.appLabel.lowercase().contains(query) })
                }
                adapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        return layout
    }

    private class PickerAdapter(
        private val items: List<AppModel>,
        private val onClick: (AppModel.App) -> Unit
    ) : RecyclerView.Adapter<PickerAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val text: TextView = view as TextView
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                textSize = 18f
                setPadding(24, 24, 24, 24)
                setTextColor(android.graphics.Color.WHITE)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val typedValue = android.util.TypedValue()
                parent.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.text.text = item.appLabel
            holder.itemView.setOnClickListener {
                if (item is AppModel.App) {
                    onClick(item)
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
