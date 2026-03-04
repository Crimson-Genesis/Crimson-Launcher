package app.olauncher.ui

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
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
            onLongClickListener = { item -> showTaskOptions(item) }
        ) { todoItem, isChecked ->
            todoItem.isCompleted = isChecked
            viewModel.update(todoItem)
        }
        binding.checklist.adapter = adapter
        binding.checklist.layoutManager = LinearLayoutManager(requireContext())
    }

    private fun showTaskOptions(item: TodoItem) {
        TaskOptionsDialogFragment.newInstance(item).show(childFragmentManager, "task_options")
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
                findNavController().navigate(R.id.action_mainFragment_to_todoFragment)
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
                    swipeDownAction()
                }
            }
        }
        binding.mainLayout.setOnTouchListener(swipeTouchListener)
        binding.checklist.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                swipeTouchListener.onTouch(rv, e)
                return false
            }

            @Suppress("UNUSED_PARAMETER")
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
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
