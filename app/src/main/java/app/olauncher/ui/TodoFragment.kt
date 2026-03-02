package app.olauncher.ui

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.TodoDateTimeHelper
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoType
import app.olauncher.databinding.FragmentTodoBinding
import app.olauncher.helper.showToast
import app.olauncher.listener.OnSwipeTouchListener
import java.text.DateFormat
import java.util.Calendar

class TodoFragment : Fragment() {

    private var _binding: FragmentTodoBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private val fromCalendar: Calendar = Calendar.getInstance()
    private val toCalendar: Calendar = Calendar.getInstance()
    private lateinit var manageDailyAdapter: ManageDailyAdapter

    private var editingItem: TodoItem? = null

    // Mapping for 3-character storage while using 1-character UI
    private val dayMapping = mapOf(
        R.id.btnMon to "Mon",
        R.id.btnTue to "Tue",
        R.id.btnWed to "Wed",
        R.id.btnThu to "Thu",
        R.id.btnFri to "Fri",
        R.id.btnSat to "Sat",
        R.id.btnSun to "Sun"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTodoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupDayOfWeekButtons()
        setupDateTimePickers()
        setupManageDailyRecyclerView()

        binding.btnSave.setOnClickListener { saveTodo() }
        binding.btnToggleAll.setOnClickListener { toggleAllDays() }
        binding.btnTemplate.setOnClickListener {
            TemplateDialogFragment().show(childFragmentManager, "templates")
        }

        initSwipeListener()

        viewModel.allDailyTasks.observe(viewLifecycleOwner, Observer {
            manageDailyAdapter.setItems(it)
        })

        viewModel.activeBoilerName.observe(viewLifecycleOwner) { name ->
            binding.tvDailyTasksHeader.text = getString(R.string.all_daily_tasks_header, name)
        }

        viewModel.copyTaskEvent.observe(viewLifecycleOwner) { item ->
            copyToForm(item)
        }

        updateFieldsEnablement()
    }

    private fun setupManageDailyRecyclerView() {
        manageDailyAdapter = ManageDailyAdapter(
            items = emptyList(),
            onItemClick = { todoItem ->
                if (editingItem?.id == todoItem.id) {
                    exitEditMode()
                } else {
                    enterEditMode(todoItem)
                }
            },
            onItemLongClick = { todoItem ->
                showTaskOptions(todoItem)
            }
        )
        binding.rvDailyTasks.apply {
            adapter = manageDailyAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun showTaskOptions(item: TodoItem) {
        TaskOptionsDialogFragment.newInstance(item, showEdit = false).show(childFragmentManager, "task_options")
    }

    private fun enterEditMode(item: TodoItem) {
        editingItem = item
        manageDailyAdapter.setSelectedItem(item.id)

        binding.etTodo.setText(item.task)
        binding.btnSave.text = "Edit"

        // Days of week
        val days = item.daysOfWeek?.split(" ") ?: emptyList()
        for (button in binding.llDaily.children) {
            if (button is Button) {
                val dayName = dayMapping[button.id]
                button.isSelected = days.contains(dayName)
            }
        }

        // From Date/Time
        item.dueDate?.let {
            fromCalendar.timeInMillis = it
            updateDateText(isFrom = true)
            updateTimeText(isFrom = true)
        } ?: run {
            binding.tvDate.text = "From Date"
            binding.tvTime.text = item.time ?: "From Time"
        }

        // To Date/Time
        item.toDate?.let {
            toCalendar.timeInMillis = it
            updateDateText(isFrom = false)
            updateTimeText(isFrom = false)
        } ?: run {
            binding.tvToDate.text = "To Date"
            binding.tvToTime.text = item.toTime ?: "To Time"
        }

        updateFieldsEnablement()
    }

    fun copyToForm(item: TodoItem) {
        exitEditMode()
        binding.etTodo.setText(item.task)
        
        // Days of week
        val days = item.daysOfWeek?.split(" ") ?: emptyList()
        for (button in binding.llDaily.children) {
            if (button is Button) {
                val dayName = dayMapping[button.id]
                button.isSelected = days.contains(dayName)
            }
        }

        // From Date/Time
        item.dueDate?.let {
            fromCalendar.timeInMillis = it
            updateDateText(isFrom = true)
            updateTimeText(isFrom = true)
        } ?: run {
            binding.tvDate.text = "From Date"
            binding.tvTime.text = item.time ?: "From Time"
        }

        // To Date/Time
        item.toDate?.let {
            toCalendar.timeInMillis = it
            updateDateText(isFrom = false)
            updateTimeText(isFrom = false)
        } ?: run {
            binding.tvToDate.text = "To Date"
            binding.tvToTime.text = item.toTime ?: "To Time"
        }

        updateFieldsEnablement()
        context?.showToast("Task copied to form")
    }

    private fun exitEditMode() {
        editingItem = null
        manageDailyAdapter.setSelectedItem(-1)
        binding.btnSave.text = "Save"
        resetFields()
    }

    private fun setupDayOfWeekButtons() {
        for (button in binding.llDaily.children) {
            button.setOnClickListener { onDayOfWeekClicked(it) }
        }
    }

    private fun onDayOfWeekClicked(view: View) {
        view.isSelected = !view.isSelected
        updateFieldsEnablement()
    }

    private fun toggleAllDays() {
        val allSelected = binding.llDaily.children.all { it.isSelected }
        for (button in binding.llDaily.children) {
            button.isSelected = !allSelected
        }
        updateFieldsEnablement()
    }

    private fun updateFieldsEnablement() {
        val isAnyDaySelected = binding.llDaily.children.any { it.isSelected }
        val isFromDateSet = binding.tvDate.text != "From Date"
        val isFromTimeSet = binding.tvTime.text != "From Time"

        if (isAnyDaySelected) {
            binding.tvDate.isEnabled = false
            binding.tvDate.alpha = 0.5f
            binding.tvToDate.isEnabled = false
            binding.tvToDate.alpha = 0.5f
            
            binding.llDaily.alpha = 1.0f
            for (button in binding.llDaily.children) button.isEnabled = true
            binding.btnToggleAll.isEnabled = true
            binding.btnToggleAll.alpha = 1.0f
        } else {
            binding.tvDate.isEnabled = true
            binding.tvDate.alpha = 1.0f
            
            binding.tvToDate.isEnabled = isFromDateSet
            binding.tvToDate.alpha = if (isFromDateSet) 1.0f else 0.5f

            val dailyEnabled = !isFromDateSet
            binding.llDaily.alpha = if (dailyEnabled) 1.0f else 0.5f
            for (button in binding.llDaily.children) button.isEnabled = dailyEnabled
            binding.btnToggleAll.isEnabled = dailyEnabled
            binding.btnToggleAll.alpha = if (dailyEnabled) 1.0f else 0.5f
        }

        binding.tvToTime.isEnabled = isFromTimeSet
        binding.tvToTime.alpha = if (isFromTimeSet) 1.0f else 0.5f
    }

    private fun setupDateTimePickers() {
        binding.tvDate.setOnClickListener { showDatePicker(isFrom = true) }
        binding.tvToDate.setOnClickListener { showDatePicker(isFrom = false) }
        binding.tvTime.setOnClickListener { showTimePicker(isFrom = true) }
        binding.tvToTime.setOnClickListener { showTimePicker(isFrom = false) }

        binding.tvDate.setOnLongClickListener { clearField(it.id); true }
        binding.tvToDate.setOnLongClickListener { clearField(it.id); true }
        binding.tvTime.setOnLongClickListener { clearField(it.id); true }
        binding.tvToTime.setOnLongClickListener { clearField(it.id); true }
    }

    private fun clearField(id: Int) {
        when (id) {
            R.id.tvDate -> {
                binding.tvDate.text = "From Date"
                binding.tvToDate.text = "To Date"
            }
            R.id.tvToDate -> binding.tvToDate.text = "To Date"
            R.id.tvTime -> {
                binding.tvTime.text = "From Time"
                binding.tvToTime.text = "To Time"
            }
            R.id.tvToTime -> binding.tvToTime.text = "To Time"
        }
        updateFieldsEnablement()
    }

    private fun showDatePicker(isFrom: Boolean) {
        val calendar = if (isFrom) fromCalendar else toCalendar
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                if (isFrom) {
                    calendar.set(Calendar.HOUR_OF_DAY, 0)
                    calendar.set(Calendar.MINUTE, 0)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                } else {
                    calendar.set(Calendar.HOUR_OF_DAY, 23)
                    calendar.set(Calendar.MINUTE, 59)
                    calendar.set(Calendar.SECOND, 59)
                    calendar.set(Calendar.MILLISECOND, 999)
                }
                updateDateText(isFrom)
                updateFieldsEnablement()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }

    private fun showTimePicker(isFrom: Boolean) {
        val calendar = if (isFrom) fromCalendar else toCalendar
        val timePickerDialog = TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                updateTimeText(isFrom)
                updateFieldsEnablement()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            false
        )
        timePickerDialog.show()
    }

    private fun updateDateText(isFrom: Boolean) {
        val calendar = if (isFrom) fromCalendar else toCalendar
        val textView = if (isFrom) binding.tvDate else binding.tvToDate
        textView.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(calendar.time)
    }

    private fun updateTimeText(isFrom: Boolean) {
        val calendar = if (isFrom) fromCalendar else toCalendar
        val textView = if (isFrom) binding.tvTime else binding.tvToTime
        textView.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(calendar.time)
    }


    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeListener() {
        val swipeTouchListener = object : OnSwipeTouchListener(requireContext()) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                findNavController().navigate(R.id.action_todoFragment_to_mainFragment)
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                findNavController().navigate(R.id.action_todoFragment_to_settingsFragment)
            }
        }

        // Root view
        binding.root.setOnTouchListener(swipeTouchListener)

        // Interactive components
        binding.etTodo.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }
        binding.llDaily.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }
        binding.llTimed.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }
        binding.btnSave.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }
        binding.btnToggleAll.setOnTouchListener { v, event ->
            swipeTouchListener.onTouch(v, event)
            false
        }

        for (button in binding.llDaily.children) {
            button.setOnTouchListener { v, event ->
                swipeTouchListener.onTouch(v, event)
                false
            }
        }

        binding.rvDailyTasks.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                swipeTouchListener.onTouch(rv, e)
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    private fun saveTodo() {
        val task = binding.etTodo.text.toString()
        if (task.isBlank()) {
            return
        }

        val isAnyDaySelected = binding.llDaily.children.any { it.isSelected }
        val isDateSet = binding.tvDate.text != "From Date"
        val isToDateSet = binding.tvToDate.text != "To Date"
        val isTimeSet = binding.tvTime.text != "From Time"
        val isToTimeSet = binding.tvToTime.text != "To Time"

        // Validation
        if (isToDateSet && !isDateSet) {
            context?.showToast("Cannot set 'To Date' without 'From Date'")
            return
        }
        if (isToTimeSet && !isTimeSet) {
            context?.showToast("Cannot set 'To Time' without 'From Time'")
            return
        }

        val type: TodoType
        var daysOfWeek: String? = null
        var dueDate: Long? = null
        var toDate: Long? = null
        var time: String? = null
        var toTime: String? = null

        if (isAnyDaySelected) {
            type = TodoType.DAILY
            daysOfWeek = binding.llDaily.children
                .filter { it.isSelected }
                .map { dayMapping[it.id] ?: "" }
                .joinToString(" ")
            if (isTimeSet) {
                time = binding.tvTime.text.toString()
            }
            if (isToTimeSet) {
                toTime = binding.tvToTime.text.toString()
            }
        } else if (isDateSet) {
            type = TodoType.TIMED
            dueDate = fromCalendar.timeInMillis
            if(isToDateSet) toDate = toCalendar.timeInMillis
            if (isTimeSet) time = binding.tvTime.text.toString()
            if (isToTimeSet) toTime = binding.tvToTime.text.toString()
        } else {
            type = TodoType.TIMELESS
        }

        val tempItem = TodoItem(
            task = task,
            type = type,
            daysOfWeek = daysOfWeek,
            dueDate = dueDate,
            toDate = toDate,
            time = time,
            toTime = toTime
        )

        // Validate absolute range ONLY if an explicit end exists
        if (TodoDateTimeHelper.hasExplicitEnd(tempItem)) {
            val startAt = TodoDateTimeHelper.getStartAtMillis(tempItem)
            val endAt = TodoDateTimeHelper.getEndAtMillis(tempItem)
            
            if (startAt != null && endAt != null && endAt <= startAt) {
                context?.showToast("End time must be after start time")
                return
            }
        }

        if (editingItem != null) {
            val updatedItem = editingItem!!.copy(
                task = task,
                type = type,
                daysOfWeek = daysOfWeek,
                dueDate = dueDate,
                time = time,
                toDate = toDate,
                toTime = toTime
            )
            viewModel.update(updatedItem)
            exitEditMode()
        } else {
            viewModel.insert(tempItem)
            resetFields()
        }
    }

    private fun resetFields() {
        binding.etTodo.text.clear()
        for (button in binding.llDaily.children) button.isSelected = false
        binding.tvDate.text = "From Date"
        binding.tvToDate.text = "To Date"
        binding.tvTime.text = "From Time"
        binding.tvToTime.text = "To Time"
        updateFieldsEnablement()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
