package app.olauncher.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import app.olauncher.MainViewModel
import app.olauncher.data.TodoItem
import app.olauncher.databinding.DialogTaskOptionsBinding
import app.olauncher.helper.dpToPx

class TaskOptionsDialogFragment : DialogFragment() {

    private var _binding: DialogTaskOptionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var todoItem: TodoItem? = null
    private var showEditOption: Boolean = true

    companion object {
        private var currentTodoItem: TodoItem? = null
        private var currentShowEdit: Boolean = true

        fun newInstance(todoItem: TodoItem, showEdit: Boolean = true): TaskOptionsDialogFragment {
            val fragment = TaskOptionsDialogFragment()
            currentTodoItem = todoItem
            currentShowEdit = showEdit
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        todoItem = currentTodoItem
        showEditOption = currentShowEdit
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTaskOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        if (!showEditOption) {
            binding.btnEditOption.visibility = View.GONE
            if (binding.root.childCount > 1) {
                binding.root.getChildAt(1).visibility = View.GONE
            }
        }

        todoItem?.let { item ->
            binding.btnEditOption.setOnClickListener {
                EditTaskDialogFragment.newInstance(item).show(parentFragmentManager, "edit_task")
                dismiss()
            }

            binding.btnDeleteOption.setOnClickListener {
                viewModel.delete(item)
                dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            
            val params = attributes
            params.y = 100.dpToPx()
            params.dimAmount = 0.7f
            attributes = params
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
