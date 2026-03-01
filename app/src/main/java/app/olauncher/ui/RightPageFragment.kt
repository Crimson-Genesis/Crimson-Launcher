package app.olauncher.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.MainViewModel
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.data.TodoItem
import app.olauncher.databinding.FragmentRightPageBinding
import app.olauncher.listener.OnSwipeTouchListener

class RightPageFragment : Fragment() {

    private var _binding: FragmentRightPageBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private lateinit var prefs: Prefs

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRightPageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]
        prefs = Prefs(requireContext())

        val completedAdapter = ChecklistAdapter(
            items = emptyList(),
            prefs = prefs,
            onLongClickListener = { item -> showTaskOptions(item, showEdit = false) }
        ) { todoItem, isChecked ->
            todoItem.isCompleted = isChecked
            viewModel.update(todoItem)
        }
        binding.rvCompletedTasks.adapter = completedAdapter
        binding.rvCompletedTasks.layoutManager = LinearLayoutManager(requireContext())

        val upcomingAdapter = ChecklistAdapter(
            items = emptyList(),
            prefs = prefs,
            onLongClickListener = { item -> showTaskOptions(item, showEdit = true) }
        ) { todoItem, isChecked ->
            todoItem.isCompleted = isChecked
            viewModel.update(todoItem)
        }
        binding.rvUpcomingTasks.adapter = upcomingAdapter
        binding.rvUpcomingTasks.layoutManager = LinearLayoutManager(requireContext())

        viewModel.completedTodoItems.observe(viewLifecycleOwner, Observer {
            completedAdapter.setItems(it)
            updateSectionVisibility(it, binding.rvCompletedTasks, binding.tvNoCompleted, binding.llCompleted, 3f)
        })

        viewModel.upcomingTodoItems.observe(viewLifecycleOwner, Observer {
            upcomingAdapter.setItems(it)
            updateSectionVisibility(it, binding.rvUpcomingTasks, binding.tvNoUpcoming, binding.llUpcoming, 7f)
        })

        initSwipeListener()
    }

    private fun showTaskOptions(item: TodoItem, showEdit: Boolean) {
        TaskOptionsDialogFragment.newInstance(item, showEdit).show(childFragmentManager, "task_options")
    }

    private fun updateSectionVisibility(
        items: List<TodoItem>,
        recyclerView: RecyclerView,
        emptyTextView: View,
        layout: LinearLayout,
        defaultWeight: Float
    ) {
        if (items.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyTextView.visibility = View.VISIBLE
            // Shrink the section by removing its weight and setting height to wrap_content
            val params = layout.layoutParams as LinearLayout.LayoutParams
            params.weight = 0f
            params.height = LinearLayout.LayoutParams.WRAP_CONTENT
            layout.layoutParams = params
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyTextView.visibility = View.GONE
            // Restore the weight and set height back to 0dp
            val params = layout.layoutParams as LinearLayout.LayoutParams
            params.weight = defaultWeight
            params.height = 0
            layout.layoutParams = params
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initSwipeListener() {
        val swipeTouchListener = object : OnSwipeTouchListener(requireContext()) {
            override fun onSwipeLeft() {
                super.onSwipeLeft()
                findNavController().navigate(R.id.action_rightPageFragment_to_settingsFragment)
            }

            override fun onSwipeRight() {
                super.onSwipeRight()
                findNavController().navigate(R.id.action_rightPageFragment_to_mainFragment)
            }
        }
        binding.rootLayout.setOnTouchListener(swipeTouchListener)
        binding.rvCompletedTasks.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                swipeTouchListener.onTouch(rv, e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
        binding.rvUpcomingTasks.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                swipeTouchListener.onTouch(rv, e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
