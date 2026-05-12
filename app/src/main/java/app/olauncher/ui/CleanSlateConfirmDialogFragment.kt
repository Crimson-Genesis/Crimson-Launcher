package app.olauncher.ui

import android.content.Intent
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
import app.olauncher.databinding.DialogClearDataConfirmBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CleanSlateConfirmDialogFragment : DialogFragment() {

    private var _binding: DialogClearDataConfirmBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogClearDataConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        binding.tvQuestion.text = "This will PERMANENTLY delete all tasks, chat messages, logs, and reset all settings to default. The app will then restart. Continue?"
        binding.btnReallyDelete.text = "RESET EVERYTHING"

        binding.btnReallyDelete.setOnClickListener {
            binding.btnReallyDelete.isEnabled = false
            binding.tvQuestion.text = "Cleaning up... please wait."
            
            val context = requireContext()
            val packageName = context.packageName
            val packageManager = context.packageManager
            val activity = requireActivity()

            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    viewModel.cleanSlateSync()
                }
                
                context.showToast("App reset complete")
                
                // Restart the app
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                intent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                    activity.finish()
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
