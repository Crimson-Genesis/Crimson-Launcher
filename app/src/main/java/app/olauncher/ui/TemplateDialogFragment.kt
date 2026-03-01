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
import androidx.recyclerview.widget.LinearLayoutManager
import app.olauncher.MainViewModel
import app.olauncher.data.TodoTemplate
import app.olauncher.databinding.DialogTemplatesBinding

class TemplateDialogFragment : DialogFragment() {

    private var _binding: DialogTemplatesBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private lateinit var adapter: TemplateAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogTemplatesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setupRecyclerView()

        binding.btnAddTemplate.setOnClickListener {
            AddTemplateDialogFragment().show(childFragmentManager, "add_template")
        }

        viewModel.allTemplates.observe(viewLifecycleOwner) { templates ->
            adapter.updateItems(templates)
            binding.tvNoTemplates.visibility = if (templates.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun setupRecyclerView() {
        adapter = TemplateAdapter(
            items = emptyList(),
            onItemClick = { template ->
                viewModel.applyTemplate(template.id)
                dismiss()
            },
            onItemLongClick = { template ->
                showRenameDialog(template)
            },
            onDeleteClick = { template ->
                viewModel.deleteTemplate(template)
            }
        )
        binding.rvTemplates.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@TemplateDialogFragment.adapter
        }
    }

    private fun showRenameDialog(template: TodoTemplate) {
        val dialog = RenameTemplateDialogFragment.newInstance(template)
        dialog.show(childFragmentManager, "rename_template")
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            } else {
                @Suppress("DEPRECATION")
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            
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
