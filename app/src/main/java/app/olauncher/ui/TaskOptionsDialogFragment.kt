package app.olauncher.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import app.olauncher.MainViewModel
import app.olauncher.data.TodoItem
import app.olauncher.databinding.DialogTaskOptionsBinding
import app.olauncher.helper.dpToPx
import kotlinx.coroutines.launch

class TaskOptionsDialogFragment : DialogFragment() {

    private var _binding: DialogTaskOptionsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var todoItemId: Long = -1
    private var showEditOption: Boolean = true

    companion object {
        private const val ARG_TODO_ID = "todo_id"
        private const val ARG_SHOW_EDIT = "show_edit"

        fun newInstance(todoItem: TodoItem, showEdit: Boolean = true): TaskOptionsDialogFragment {
            val fragment = TaskOptionsDialogFragment()
            val args = Bundle().apply {
                putLong(ARG_TODO_ID, todoItem.id)
                putBoolean(ARG_SHOW_EDIT, showEdit)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            todoItemId = it.getLong(ARG_TODO_ID)
            showEditOption = it.getBoolean(ARG_SHOW_EDIT)
        }
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
            // Hide the first separator
            binding.root.getChildAt(1).visibility = View.GONE
        }

        // Hide "Copy" if not triggered from TodoFragment (left page)
        if (parentFragment !is TodoFragment) {
            binding.btnCopyOption.visibility = View.GONE
            // Hide the separator above Copy
            if (showEditOption) binding.root.getChildAt(3).visibility = View.GONE
        }

        binding.btnEditOption.setOnClickListener {
            lifecycleScope.launch {
                val item = viewModel.getTodoItemById(todoItemId)
                item?.let {
                    EditTaskDialogFragment.newInstance(it).show(parentFragmentManager, "edit_task")
                }
                dismiss()
            }
        }

        binding.btnCopyOption.setOnClickListener {
            lifecycleScope.launch {
                val item = viewModel.getTodoItemById(todoItemId)
                item?.let {
                    viewModel.copyTaskEvent.postValue(it)
                }
                dismiss()
            }
        }

        binding.btnDeleteOption.setOnClickListener {
            lifecycleScope.launch {
                val item = viewModel.getTodoItemById(todoItemId)
                item?.let {
                    viewModel.delete(it)
                }
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
