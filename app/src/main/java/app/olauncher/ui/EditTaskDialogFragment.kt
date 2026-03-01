package app.olauncher.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import androidx.core.view.children
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoType
import app.olauncher.databinding.DialogEditTaskBinding
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class EditTaskDialogFragment : DialogFragment() {

    private var _binding: DialogEditTaskBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private val calendar: Calendar = Calendar.getInstance()
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
            updateDatePickerState()

            // Populate date/time
            if (item.type == TodoType.TIMED && item.dueDate != null) {
                calendar.timeInMillis = item.dueDate
                updateDateText()
                updateTimeText()
                // Disable daily section if it's a timed task
                binding.llDaily.alpha = 0.5f
                for (button in binding.llDaily.children) {
                    button.isEnabled = false
                }
            } else if (item.type == TodoType.DAILY) {
                if (item.time != null) {
                    binding.tvTime.text = item.time
                }
                binding.tvDate.isEnabled = false
                binding.tvDate.alpha = 0.5f
            }
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
        binding.tvDate.isEnabled = !isAnyDaySelected
        binding.tvDate.alpha = if (isAnyDaySelected) 0.5f else 1.0f
    }

    private fun setupDateTimePickers() {
        binding.tvDate.setOnClickListener { showDatePicker() }
        binding.tvTime.setOnClickListener { showTimePicker() }
    }

    private fun showDatePicker() {
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                updateDateText()
                binding.llDaily.alpha = 0.5f
                for (button in binding.llDaily.children) {
                    button.isEnabled = false
                }
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        datePickerDialog.show()
    }

    private fun showTimePicker() {
        val timePickerDialog = TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                calendar.set(Calendar.MINUTE, minute)
                updateTimeText()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            false
        )
        timePickerDialog.show()
    }

    private fun updateDateText() {
        binding.tvDate.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(calendar.time)
    }

    private fun updateTimeText() {
        binding.tvTime.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(calendar.time)
    }

    private fun saveTodo() {
        val task = binding.etTodo.text.toString()
        if (task.isBlank() || todoItem == null) return

        val isAnyDaySelected = binding.llDaily.children.any { it.isSelected }
        val isDateSet = binding.tvDate.text != "Select Date"
        val isTimeSet = binding.tvTime.text != "Select Time"

        val type: TodoType
        var daysOfWeek: String? = null
        var dueDate: Long? = null
        var time: String? = null

        if (isAnyDaySelected) {
            type = TodoType.DAILY
            daysOfWeek = binding.llDaily.children
                .filter { it.isSelected }
                .map { dayMapping[it.id] ?: "" }
                .joinToString(" ")
            if (isTimeSet) {
                time = binding.tvTime.text.toString()
            }
        } else if (isDateSet) {
            type = TodoType.TIMED
            dueDate = calendar.timeInMillis
        } else {
            type = TodoType.TIMELESS
        }

        val updatedItem = todoItem!!.copy(
            task = task,
            type = type,
            daysOfWeek = daysOfWeek,
            dueDate = dueDate,
            time = time
        )

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
