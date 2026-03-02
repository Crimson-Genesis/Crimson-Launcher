package app.olauncher.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.children
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.TodoDateTimeHelper
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoType
import app.olauncher.databinding.DialogEditTaskBinding
import app.olauncher.helper.showToast
import java.text.DateFormat
import java.util.Calendar

class EditTaskDialogFragment : DialogFragment() {

    private var _binding: DialogEditTaskBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private val fromCalendar: Calendar = Calendar.getInstance()
    private val toCalendar: Calendar = Calendar.getInstance()
    private var todoItem: TodoItem? = null

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

    companion object {
        private var currentTodoItem: TodoItem? = null

        fun newInstance(todoItem: TodoItem): EditTaskDialogFragment {
            val fragment = EditTaskDialogFragment()
            currentTodoItem = todoItem
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        todoItem = currentTodoItem
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogEditTaskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupDayOfWeekButtons()
        setupDateTimePickers()
        populateFields()

        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnEdit.setOnClickListener { saveTodo() }
    }

    private fun populateFields() {
        todoItem?.let { item ->
            binding.etTodo.setText(item.task)

            // Populate days
            val days = item.daysOfWeek?.split(" ") ?: emptyList()
            for (button in binding.llDaily.children) {
                if (button is Button) {
                    val dayName = dayMapping[button.id]
                    button.isSelected = days.contains(dayName)
                }
            }

            // Populate date/time
            if (item.type == TodoType.TIMED) {
                item.dueDate?.let {
                    fromCalendar.timeInMillis = it
                    updateDateText(true)
                    updateTimeText(true)
                } ?: run {
                    binding.tvDate.text = "From Date"
                    binding.tvTime.text = item.time ?: "From Time"
                }

                item.toDate?.let {
                    toCalendar.timeInMillis = it
                    updateDateText(false)
                    updateTimeText(false)
                } ?: run {
                    binding.tvToDate.text = "To Date"
                    binding.tvToTime.text = item.toTime ?: "To Time"
                }
            } else if (item.type == TodoType.DAILY) {
                if (item.time != null) {
                    binding.tvTime.text = item.time
                } else {
                    binding.tvTime.text = "From Time"
                }
                if (item.toTime != null) {
                    binding.tvToTime.text = item.toTime
                } else {
                    binding.tvToTime.text = "To Time"
                }
            }
            updateDatePickerState()
        }
    }

    private fun setupDayOfWeekButtons() {
        for (button in binding.llDaily.children) {
            button.setOnClickListener { onDayOfWeekClicked(it) }
        }
    }

    private fun onDayOfWeekClicked(view: View) {
        view.isSelected = !view.isSelected
        updateDatePickerState()
    }

    private fun updateDatePickerState() {
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
        } else {
            binding.tvDate.isEnabled = true
            binding.tvDate.alpha = 1.0f
            
            binding.tvToDate.isEnabled = isFromDateSet
            binding.tvToDate.alpha = if (isFromDateSet) 1.0f else 0.5f

            val dailyEnabled = !isFromDateSet
            binding.llDaily.alpha = if (dailyEnabled) 1.0f else 0.5f
            for (button in binding.llDaily.children) button.isEnabled = dailyEnabled
        }

        binding.tvToTime.isEnabled = isFromTimeSet
        binding.tvToTime.alpha = if (isFromTimeSet) 1.0f else 0.5f
    }

    private fun setupDateTimePickers() {
        binding.tvDate.setOnClickListener { showDatePicker(true) }
        binding.tvToDate.setOnClickListener { showDatePicker(false) }
        binding.tvTime.setOnClickListener { showTimePicker(true) }
        binding.tvToTime.setOnClickListener { showTimePicker(false) }

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
        updateDatePickerState()
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
                updateDatePickerState()
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
                updateDatePickerState()
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

    private fun saveTodo() {
        val task = binding.etTodo.text.toString()
        if (task.isBlank() || todoItem == null) return

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

        val updatedItem = todoItem!!.copy(
            task = task,
            type = type,
            daysOfWeek = daysOfWeek,
            dueDate = dueDate,
            time = time,
            toDate = toDate,
            toTime = toTime
        )

        // Validate absolute range using central helper
        if (TodoDateTimeHelper.hasExplicitEnd(updatedItem)) {
            val startAt = TodoDateTimeHelper.getStartAtMillis(updatedItem)
            val endAt = TodoDateTimeHelper.getEndAtMillis(updatedItem)
            
            if (startAt != null && endAt != null && endAt <= startAt) {
                context?.showToast("End time must be after start time")
                return
            }
        }

        viewModel.update(updatedItem)
        dismiss()
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            
            val params = attributes
            params.dimAmount = 0.7f
            attributes = params
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
