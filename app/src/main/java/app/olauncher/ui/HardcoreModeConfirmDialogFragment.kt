package app.olauncher.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogHardcoreModeConfirmBinding
import app.olauncher.helper.dpToPx

class HardcoreModeConfirmDialogFragment : DialogFragment() {

    private var _binding: DialogHardcoreModeConfirmBinding? = null
    private val binding get() = _binding!!

    private lateinit var prefs: Prefs
    private var onConfirm: (() -> Unit)? = null

    companion object {
        fun newInstance(onConfirm: () -> Unit): HardcoreModeConfirmDialogFragment {
            val fragment = HardcoreModeConfirmDialogFragment()
            fragment.onConfirm = onConfirm
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogHardcoreModeConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())

        binding.btnEnable.setOnClickListener {
            prefs.hardcoreMode = true
            onConfirm?.invoke()
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            
            val params = attributes
            params.dimAmount = 0.8f
            attributes = params
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
