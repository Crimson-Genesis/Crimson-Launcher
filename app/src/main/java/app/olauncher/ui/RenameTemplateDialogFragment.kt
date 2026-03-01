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
import app.olauncher.R
import app.olauncher.data.TodoTemplate
import app.olauncher.databinding.DialogAddTemplateBinding
import app.olauncher.helper.hideKeyboard
import app.olauncher.helper.showKeyboard

class RenameTemplateDialogFragment : DialogFragment() {

    private var _binding: DialogAddTemplateBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var templateId: Long = -1
    private var templateName: String = ""

    companion object {
        private const val ARG_ID = "arg_id"
        private const val ARG_NAME = "arg_name"

        fun newInstance(template: TodoTemplate): RenameTemplateDialogFragment {
            return RenameTemplateDialogFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, template.id)
                    putString(ARG_NAME, template.name)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        templateId = arguments?.getLong(ARG_ID) ?: -1
        templateName = arguments?.getString(ARG_NAME) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAddTemplateBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        binding.btnSave.text = getString(R.string.rename)
        
        val titleView = binding.root.findViewById<android.widget.TextView>(R.id.tvTitle)
        if (titleView != null) {
            titleView.text = getString(R.string.rename_template)
        }

        binding.etTemplateName.setText(templateName)
        binding.etTemplateName.requestFocus()
        binding.etTemplateName.showKeyboard()

        binding.btnSave.setOnClickListener {
            val newName = binding.etTemplateName.text.toString()
            if (newName.isNotBlank()) {
                viewModel.renameTemplate(TodoTemplate(id = templateId, name = templateName), newName)
                binding.etTemplateName.hideKeyboard()
                dismiss()
            }
        }
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
