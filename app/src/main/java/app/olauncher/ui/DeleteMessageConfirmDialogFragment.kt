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
import app.olauncher.MainViewModel
import app.olauncher.data.ChatMessage
import app.olauncher.databinding.DialogDeleteMessageConfirmBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.showToast

class DeleteMessageConfirmDialogFragment : DialogFragment() {

    private var _binding: DialogDeleteMessageConfirmBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: MainViewModel
    private var messagesToDelete: List<ChatMessage> = emptyList()

    companion object {
        fun newInstance(message: ChatMessage): DeleteMessageConfirmDialogFragment {
            val fragment = DeleteMessageConfirmDialogFragment()
            fragment.messagesToDelete = listOf(message)
            return fragment
        }

        fun newInstance(messages: List<ChatMessage>): DeleteMessageConfirmDialogFragment {
            val fragment = DeleteMessageConfirmDialogFragment()
            fragment.messagesToDelete = messages
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogDeleteMessageConfirmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[MainViewModel::class.java]

        if (messagesToDelete.isEmpty()) {
            dismiss()
            return
        }

        if (messagesToDelete.size > 1) {
            binding.tvQuestion.text = "DELETE SELECTED MESSAGES AND THEIR MEDIA?"
        } else {
            binding.tvQuestion.text = "DELETE THIS MESSAGE AND ITS MEDIA?"
        }

        binding.btnConfirmDelete.setOnClickListener {
            if (messagesToDelete.size > 1) {
                viewModel.deleteChatMessages(messagesToDelete)
                requireContext().showToast("${messagesToDelete.size} messages deleted")
            } else if (messagesToDelete.isNotEmpty()) {
                viewModel.deleteChatMessage(messagesToDelete[0])
                requireContext().showToast("Message deleted")
            }
            // Always notify that a deletion operation finished so selection mode can be cleared if needed
            parentFragmentManager.setFragmentResult("delete_confirmed", Bundle())
            dismiss()
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
