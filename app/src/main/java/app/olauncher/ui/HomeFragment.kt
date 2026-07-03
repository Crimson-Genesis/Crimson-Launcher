package app.olauncher.ui

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.pm.LauncherApps
import android.os.UserManager
import android.graphics.RenderEffect
import android.graphics.Shader
import kotlin.math.abs
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.LauncherApp
import app.olauncher.data.TodoItem
import app.olauncher.databinding.FragmentHomeBinding
import app.olauncher.helper.EventLogger
import app.olauncher.helper.LogEvent
import app.olauncher.helper.appUsagePermissionGranted
import app.olauncher.helper.dpToPx
import app.olauncher.helper.expandNotificationDrawer
import app.olauncher.helper.openAlarmApp
import app.olauncher.helper.openCalendar
import app.olauncher.helper.openSearch
import app.olauncher.helper.showToast
import app.olauncher.helper.filterHiddenAppsFromSwipeDownList
import app.olauncher.listener.OnSwipeTouchListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeFragment : Fragment(), View.OnClickListener, View.OnLongClickListener {

    private lateinit var prefs: Prefs
    private lateinit var viewModel: MainViewModel
    private lateinit var deviceManager: DevicePolicyManager

    private var startY = 0f
    private var startX = 0f
    private var isDraggingLauncherMenu = false
    private var gestureSelection = 1 // 0 = Close, 1 = App Name/None, 2 = Launch
    private var lastGestureSelection = -1
    private var velocityTracker: VelocityTracker? = null

    private var swipeDownAppList: List<LauncherApp> = emptyList()
    private var swipeDownAppIndex: Int = 0
    private var lastCycleX = 0f
    private val recentTouchPoints = mutableListOf<Pair<Long, Float>>()

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        viewModel = activity?.run {
            ViewModelProvider(this)[MainViewModel::class.java]
        } ?: throw Exception("Invalid Activity")

        deviceManager = context?.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

        initObservers()
        setHomeAlignment(prefs.homeAlignment)
        initClickListeners()
        initChecklist()
        initSwipeTouchListener()
    }

    override fun onResume() {
        super.onResume()
        populateHomeScreen()
        viewModel.checkIsCrimsonDefault()
        if (prefs.showStatusBar) showStatusBar()
        else hideStatusBar()
        
        // Refresh the checklist every time we return to home (e.g. device unlock)
        viewModel.refreshTodayList()
        
        // Ensure database is reset if day changed while we were away
        checkDayResetAndRefresh()
    }

    private fun checkDayResetAndRefresh() {
        val currentLogicalDay = prefs.getLogicalDayKey(System.currentTimeMillis())
        if (prefs.lastResetDayKey != currentLogicalDay) {
            // Trigger refresh - the actual reset logic is in MainActivity.checkMidnightUpdate 
            // which runs onResume too, but we force a trigger here to be sure.
            viewModel.refreshTodayList()
        }
    }

    override fun onClick(view: View) {
        when (view.id) {
            R.id.lock -> {}
            R.id.clock -> openClockApp()
            R.id.date -> openCalendarApp()
            R.id.setDefaultLauncher -> viewModel.resetLauncherLiveData.postValue(Unit)
            R.id.tvScreenTime -> openScreenTimeDigitalWellbeing()
            R.id.btnRefresh -> manualRefresh()
        }
    }

    private fun manualRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            // Ensure reset logic runs before refresh if day has changed
            val currentLogicalDay = prefs.getLogicalDayKey(System.currentTimeMillis())
            if (prefs.lastResetDayKey != currentLogicalDay) {
                // If MainActivity hasn't reset yet, do it now
                viewModel.resetDailyTasks()
            }
            launch(Dispatchers.Main) {
                populateHomeScreen()
                viewModel.refreshTodayList()
            }
        }
    }

    private fun openClockApp() {
        openAlarmApp(requireContext())
    }

    private fun openCalendarApp() {
        openCalendar(requireContext())
    }

    override fun onLongClick(view: View): Boolean {
        when (view.id) {
            R.id.setDefaultLauncher -> {
                prefs.hideSetDefaultLauncher = true
                binding.setDefaultLauncher.visibility = View.GONE
                if (viewModel.isCrimsonDefault.value != true) {
                    requireContext().showToast(R.string.set_as_default_launcher)
                    findNavController().navigate(R.id.action_mainFragment_to_settingsFragment)
                }
            }
        }
        return true
    }

    private fun initObservers() {
        viewModel.isCrimsonDefault.observe(viewLifecycleOwner, Observer {
            if (it != true) {
                setHomeAlignment()
            }
            binding.setDefaultLauncher.isVisible = it.not() && prefs.hideSetDefaultLauncher.not()
        })
        viewModel.homeAppAlignment.observe(viewLifecycleOwner) {
            setHomeAlignment(it)
        }
        viewModel.toggleDateTime.observe(viewLifecycleOwner) {
            populateDateTime()
        }
        viewModel.screenTimeValue.observe(viewLifecycleOwner) {
            it?.let { binding.tvScreenTime.text = it }
        }

        viewModel.todayTodoItems.observe(viewLifecycleOwner, Observer {
            val adapter = binding.checklist.adapter as ChecklistAdapter
            adapter.setItems(it)
        })
    }

    private fun initClickListeners() {
        binding.lock.setOnClickListener(this)
        binding.clock.setOnClickListener(this)
        binding.date.setOnClickListener(this)
        binding.setDefaultLauncher.setOnClickListener(this)
        binding.setDefaultLauncher.setOnLongClickListener(this)
        binding.tvScreenTime.setOnClickListener(this)
        binding.btnRefresh.setOnClickListener(this)
    }

    private fun setHomeAlignment(horizontalGravity: Int = prefs.homeAlignment) {
        binding.dateTimeLayout.gravity = horizontalGravity
        
        // Align refresh button
        val params = binding.btnRefresh.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.BOTTOM or horizontalGravity
        binding.btnRefresh.layoutParams = params
    }

    private fun populateDateTime() {
        binding.dateTimeLayout.isVisible = prefs.dateTimeVisibility != Constants.DateTime.OFF
        binding.clock.isVisible = Constants.DateTime.isTimeVisible(prefs.dateTimeVisibility)
        binding.date.isVisible = Constants.DateTime.isDateVisible(prefs.dateTimeVisibility)

        if (prefs.dateTimeVisibility == Constants.DateTime.ON_WITH_SEC) {
            binding.clock.format12Hour = "h:mm:ss a"
            binding.clock.format24Hour = "HH:mm:ss"
        } else {
            binding.clock.format12Hour = "h:mm a"
            binding.clock.format24Hour = "HH:mm"
        }

        val dateFormat = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
        var dateText = dateFormat.format(Date())

        if (!prefs.showStatusBar) {
            val battery = (requireContext().getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery > 0)
                dateText = getString(R.string.day_battery, dateText, battery)
        }
        binding.date.text = dateText.replace(".,", ",")
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun populateScreenTime() {
        if (requireContext().appUsagePermissionGranted().not()) return

        viewModel.getTodayScreenTime()
        binding.tvScreenTime.visibility = View.VISIBLE

        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val horizontalMargin = if (isLandscape) 64.dpToPx() else 10.dpToPx()
        val marginTop = if (isLandscape) {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 36.dpToPx() else 56.dpToPx()
        } else {
            if (prefs.dateTimeVisibility == Constants.DateTime.DATE_ONLY) 45.dpToPx() else 72.dpToPx()
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = marginTop
            marginStart = horizontalMargin
            marginEnd = horizontalMargin
            gravity = if (prefs.homeAlignment == Gravity.END) Gravity.START else Gravity.END
        }
        binding.tvScreenTime.layoutParams = params
        binding.tvScreenTime.setPadding(10.dpToPx())
    }

    private fun populateHomeScreen() {
        populateDateTime()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            populateScreenTime()
    }

    private fun initChecklist() {
        val adapter = ChecklistAdapter(
            items = emptyList(),
            prefs = prefs,
            onEditClickListener = { item ->
                EditTaskDialogFragment.newInstance(item).show(parentFragmentManager, "edit_task")
            },
            onCopyClickListener = { item ->
                viewModel.copyTaskEvent.postValue(item)
            },
            onDeleteClickListener = { item ->
                viewModel.delete(item)
            }
        ) { todoItem, isChecked ->
            todoItem.isCompleted = isChecked
            viewModel.update(todoItem)
        }
        binding.checklist.adapter = adapter
        binding.checklist.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun showOverlayMenu() {
        lastGestureSelection = -1
        val settingsList = filterHiddenAppsFromSwipeDownList(prefs.getSwipeDownAppList(), prefs.hiddenApps)
        if (settingsList.isNotEmpty()) {
            val defaultApp = settingsList[0]
            val remainingList = settingsList.subList(1, settingsList.size)
            val n = settingsList.size
            val centerIndex = n / 2
            val leftList = remainingList.subList(0, centerIndex.coerceAtMost(remainingList.size))
            val rightList = remainingList.subList(centerIndex.coerceAtMost(remainingList.size), remainingList.size)
            
            val combined = mutableListOf<LauncherApp>()
            combined.addAll(leftList)
            combined.add(defaultApp)
            combined.addAll(rightList)
            
            swipeDownAppList = combined
            swipeDownAppIndex = centerIndex
        } else {
            swipeDownAppList = emptyList()
            swipeDownAppIndex = 0
        }
        lastCycleX = startX

        updateCarouselTexts()

        binding.gestureLauncherOverlay.visibility = View.VISIBLE
        binding.gestureLauncherOverlay.alpha = 0f
        
        binding.overlayClose.scaleX = 1f
        binding.overlayClose.scaleY = 1f
        binding.overlayClose.alpha = 1f
        
        binding.overlayAppContainer.scaleX = 1f
        binding.overlayAppContainer.scaleY = 1f
        binding.overlayAppContainer.alpha = 1f
        
        binding.overlayAppName.scaleX = 1f
        binding.overlayAppName.scaleY = 1f
        binding.overlayAppName.alpha = 1f
        
        binding.overlayLaunch.scaleX = 1f
        binding.overlayLaunch.scaleY = 1f
        binding.overlayLaunch.alpha = 1f
    }

    private fun updateOverlayDrag(rawY: Float) {
        val screenHeight = resources.displayMetrics.heightPixels
        
        if (rawY <= screenHeight * 0.20f) {
            isDraggingLauncherMenu = false
            dismissOverlay(animate = true)
            return
        }

        val progress = if (rawY <= screenHeight * 0.35f) {
            val startFadeY = screenHeight * 0.35f
            val endFadeY = screenHeight * 0.20f
            ((rawY - endFadeY) / (startFadeY - endFadeY)).coerceIn(0f, 1f)
        } else {
            1f
        }

        if (isDraggingLauncherMenu) {
            binding.gestureLauncherOverlay.alpha = progress

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blurRadius = (15f * progress).coerceAtLeast(1f)
                val blurEffect = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
                binding.checklist.setRenderEffect(blurEffect)
                binding.dateTimeLayout.setRenderEffect(blurEffect)
            }

            updateSelection(rawY)
        }
    }

    private fun updateSelection(rawY: Float) {
        val screenHeight = resources.displayMetrics.heightPixels
        val currentSelection = if (rawY >= screenHeight * 0.75f) {
            2 // Launch (lower 25%)
        } else if (rawY <= screenHeight * 0.50f) {
            0 // Close (upper 50%)
        } else {
            1 // App Name/None (mid section)
        }

        if (currentSelection == lastGestureSelection) {
            return
        }
        lastGestureSelection = currentSelection
        gestureSelection = currentSelection

        binding.overlayLaunch.animate().cancel()
        binding.overlayAppContainer.animate().cancel()
        binding.overlayClose.animate().cancel()

        when (currentSelection) {
            2 -> { // Launch highlighted
                binding.overlayLaunch.animate().scaleX(1.25f).scaleY(1.25f).alpha(1.0f).setDuration(100).start()
                binding.overlayAppContainer.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.4f).setDuration(100).start()
                binding.overlayClose.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.4f).setDuration(100).start()
            }
            0 -> { // Close highlighted
                binding.overlayLaunch.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.4f).setDuration(100).start()
                binding.overlayAppContainer.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.4f).setDuration(100).start()
                binding.overlayClose.animate().scaleX(1.25f).scaleY(1.25f).alpha(1.0f).setDuration(100).start()
            }
            else -> { // App Name highlighted (neither Launch nor Close)
                binding.overlayLaunch.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.4f).setDuration(100).start()
                binding.overlayAppContainer.animate().scaleX(1.15f).scaleY(1.15f).alpha(1.0f).setDuration(100).start()
                binding.overlayClose.animate().scaleX(0.9f).scaleY(0.9f).alpha(0.4f).setDuration(100).start()
            }
        }
    }

    private fun getTargetApp(): LauncherApp? {
        if (swipeDownAppList.isNotEmpty() && swipeDownAppIndex in swipeDownAppList.indices) {
            return swipeDownAppList[swipeDownAppIndex]
        }
        val fallbackPkg = prefs.swipeDownAppPackage
        if (fallbackPkg.isNotEmpty()) {
            return LauncherApp(
                packageName = fallbackPkg,
                label = prefs.swipeDownAppLabel,
                userHandle = prefs.swipeDownAppUser
            )
        }
        return null
    }

    private fun updateCarouselTexts() {
        val centerApp = getTargetApp()
        binding.overlayAppName.text = centerApp?.displayName ?: "No App Selected"

        if (swipeDownAppList.isNotEmpty() && swipeDownAppIndex - 1 >= 0) {
            binding.overlayAppLeft.text = swipeDownAppList[swipeDownAppIndex - 1].displayName
            binding.overlayAppLeft.visibility = View.VISIBLE
        } else {
            binding.overlayAppLeft.text = ""
            binding.overlayAppLeft.visibility = View.INVISIBLE
        }

        if (swipeDownAppList.isNotEmpty() && swipeDownAppIndex + 1 < swipeDownAppList.size) {
            binding.overlayAppRight.text = swipeDownAppList[swipeDownAppIndex + 1].displayName
            binding.overlayAppRight.visibility = View.VISIBLE
        } else {
            binding.overlayAppRight.text = ""
            binding.overlayAppRight.visibility = View.INVISIBLE
        }
    }

    private fun handleGestureEnd(yVelocity: Float) {
        if (yVelocity < -600f) {
            dismissOverlay(animate = true)
        } else if (yVelocity > 600f) {
            val app = getTargetApp()
            if (app != null) {
                launchAppGroup(app)
            } else {
                requireContext().showToast("Please configure launch app in launcher settings")
            }
            dismissOverlay(animate = false)
        } else {
            if (gestureSelection == 1 || gestureSelection == 2) {
                val app = getTargetApp()
                if (app != null) {
                    launchAppGroup(app)
                } else {
                    requireContext().showToast("Please configure launch app in launcher settings")
                }
                dismissOverlay(animate = false)
            } else {
                dismissOverlay(animate = true)
            }
        }
    }

    private fun launchAppGroup(appGroup: LauncherApp) {
        val context = requireContext()
        if (appGroup.packageName.isEmpty()) {
            context.showToast("Please configure launch app in launcher settings")
            return
        }
        for (bgApp in appGroup.backgroundApps) {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(bgApp.packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    context.startActivity(intent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        launchAppByPackage(context, appGroup.packageName, appGroup.userHandle)
    }

    private fun dismissOverlay(animate: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            _binding?.checklist?.setRenderEffect(null)
            _binding?.dateTimeLayout?.setRenderEffect(null)
        }
        if (animate) {
            _binding?.gestureLauncherOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(200)
                ?.withEndAction {
                    _binding?.gestureLauncherOverlay?.visibility = View.GONE
                }
                ?.start()
        } else {
            _binding?.gestureLauncherOverlay?.alpha = 0f
            _binding?.gestureLauncherOverlay?.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeTouchListener() {
        val swipeTouchListener = object : OnSwipeTouchListener(requireContext()) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                findNavController().navigate(R.id.action_mainFragment_to_rightPageFragment)
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                findNavController().navigate(R.id.action_mainFragment_to_chatFragment)
            }

            override fun onSwipeUp() {
                super.onSwipeUp()
                if (!binding.checklist.canScrollVertically(1)) {
                    attemptShowAppList(source = "gesture")
                }
            }

            override fun onSwipeDown() {
                super.onSwipeDown()
                if (!binding.checklist.canScrollVertically(-1)) {
                    if (prefs.swipeDownAction != Constants.SwipeDownAction.APP_LAUNCHER) {
                        swipeDownAction()
                    }
                }
            }
        }

        val customTouchListener = object : View.OnTouchListener {
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                if (prefs.swipeDownAction != Constants.SwipeDownAction.APP_LAUNCHER) {
                    return swipeTouchListener.onTouch(v, event)
                }

                if (!isDraggingLauncherMenu && binding.checklist.canScrollVertically(-1)) {
                    return swipeTouchListener.onTouch(v, event)
                }

                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain()
                }
                velocityTracker?.addMovement(event)

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        startX = event.rawX
                        recentTouchPoints.clear()
                        recentTouchPoints.add(Pair(event.eventTime, event.rawX))
                        swipeTouchListener.onTouch(v, event)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = event.rawY - startY
                        val dx = event.rawX - startX
                        
                        val currentTime = event.eventTime
                        recentTouchPoints.add(Pair(currentTime, event.rawX))
                        recentTouchPoints.removeAll { currentTime - it.first > 150 }
                        
                        val speed = if (recentTouchPoints.size >= 2) {
                            val oldest = recentTouchPoints.first()
                            val latest = recentTouchPoints.last()
                            val dt = latest.first - oldest.first
                            val adx = latest.second - oldest.second
                            if (dt > 0) (abs(adx) / dt) * 1000f else 0f
                        } else {
                            0f
                        }
                        
                        if (isDraggingLauncherMenu) {
                            updateOverlayDrag(event.rawY)
                            val deltaX = event.rawX - lastCycleX
                            
                            // Dynamically scale threshold distance based on drag speed
                            val threshold = when {
                                speed > 4000 -> 30f
                                speed > 2500 -> 60f
                                speed > 1200 -> 90f
                                else -> 120f
                            }
                            
                            if (abs(deltaX) >= threshold) {
                                if (swipeDownAppList.size > 1) {
                                    val previousIndex = swipeDownAppIndex
                                    
                                    // Scale steps skipped based on velocity
                                    val steps = when {
                                        speed > 5000 -> 3
                                        speed > 2500 -> 2
                                        else -> 1
                                    }
                                    
                                    if (deltaX > 0) {
                                        swipeDownAppIndex = (swipeDownAppIndex - steps).coerceAtLeast(0)
                                    } else {
                                        swipeDownAppIndex = (swipeDownAppIndex + steps).coerceAtMost(swipeDownAppList.size - 1)
                                    }
                                    
                                    if (swipeDownAppIndex != previousIndex) {
                                        lastCycleX = event.rawX
                                        updateCarouselTexts()
                                        
                                        binding.overlayAppName.animate().scaleX(1.3f).scaleY(1.3f).setDuration(80).withEndAction {
                                            binding.overlayAppName.animate().scaleX(1.15f).scaleY(1.15f).setDuration(80).start()
                                        }.start()
                                    }
                                }
                            }
                            return true
                        } else {
                            if (dy > 40 && abs(dy) > abs(dx)) {
                                isDraggingLauncherMenu = true
                                showOverlayMenu()
                                updateOverlayDrag(event.rawY)
                                return true
                            }
                        }
                        return swipeTouchListener.onTouch(v, event)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (isDraggingLauncherMenu) {
                            isDraggingLauncherMenu = false
                            
                            velocityTracker?.computeCurrentVelocity(1000)
                            val yVelocity = velocityTracker?.yVelocity ?: 0f
                            
                            velocityTracker?.recycle()
                            velocityTracker = null
                            
                            handleGestureEnd(yVelocity)
                            return true
                        }
                        
                        velocityTracker?.recycle()
                        velocityTracker = null
                        return swipeTouchListener.onTouch(v, event)
                    }
                }
                return swipeTouchListener.onTouch(v, event)
            }
        }

        binding.mainLayout.setOnTouchListener(customTouchListener)
        binding.checklist.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                customTouchListener.onTouch(rv, e)
                return isDraggingLauncherMenu
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                customTouchListener.onTouch(rv, e)
            }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun attemptShowAppList(source: String) {
        if (prefs.hardcoreMode) {
            val tasks = viewModel.todayTodoItems.value
            if (tasks != null && tasks.any { !it.isCompleted }) {
                requireContext().showToast(R.string.finish_tasks_to_open_apps)
                return
            }
        }
        showAppList(Constants.FLAG_LAUNCH_APP, source)
    }

    private fun showAppList(flag: Int, source: String) {
        findNavController().navigate(
            R.id.action_mainFragment_to_appListFragment,
            bundleOf(
                Constants.Key.FLAG to flag,
                "source" to source
            )
        )
    }

    private fun swipeDownAction() {
        when (prefs.swipeDownAction) {
            Constants.SwipeDownAction.NOTIFICATIONS -> expandNotificationDrawer(requireContext())
            Constants.SwipeDownAction.SEARCH -> {
                EventLogger.log(requireContext(), LogEvent.DrawerOpened("swipe_down", 0, 0))
                openSearch(requireContext())
            }
            Constants.SwipeDownAction.APP_LAUNCHER -> {
                val pkg = prefs.swipeDownAppPackage
                if (pkg.isNotEmpty()) {
                    launchAppByPackage(requireContext(), pkg, prefs.swipeDownAppUser)
                } else {
                    requireContext().showToast("Please configure launch app in launcher settings")
                }
            }
        }
    }

    private fun launchAppByPackage(context: Context, packageName: String, userHandleStr: String) {
        try {
            val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
            val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager
            val user = userManager.userProfiles.find { it.toString() == userHandleStr } 
                ?: android.os.Process.myUserHandle()
            
            val activities = launcherApps.getActivityList(packageName, user)
            if (activities.isNotEmpty()) {
                val componentName = ComponentName(packageName, activities[0].componentName.className)
                
                EventLogger.log(context, LogEvent.AppLaunched(
                    packageName = packageName,
                    activity = activities[0].componentName.className,
                    userHandle = user.toString(),
                    renamedLabelUsed = prefs.getAppRenameLabel(packageName).isNotEmpty(),
                    isHidden = false
                ))

                launcherApps.startMainActivity(componentName, user, null, null)
            } else {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    context.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    private fun openScreenTimeDigitalWellbeing() {
        val intent = Intent()
        try {
            intent.setClassName(
                Constants.DIGITAL_WELLBEING_PACKAGE_NAME,
                Constants.DIGITAL_WELLBEING_ACTIVITY
            )
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                intent.setClassName(
                    Constants.DIGITAL_WELLBEING_SAMSUNG_PACKAGE_NAME,
                    Constants.DIGITAL_WELLBEING_SAMSUNG_ACTIVITY
                )
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
