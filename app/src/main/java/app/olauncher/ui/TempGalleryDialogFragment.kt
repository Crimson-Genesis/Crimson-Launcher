package app.olauncher.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.DialogTempGalleryBinding
import app.olauncher.databinding.ItemChatMediaBinding
import app.olauncher.helper.dpToPx

class TempGalleryDialogFragment : DialogFragment() {

    private var _binding: DialogTempGalleryBinding? = null
    private val binding get() = _binding!!

    private lateinit var uris: List<String>
    private lateinit var previews: List<String>
    private lateinit var types: List<String>
    private var onSelected: ((List<String>) -> Unit)? = null
    private val selectedUris = mutableSetOf<String>()

    companion object {
        fun newInstance(uris: List<String>, previews: List<String>, types: List<String>, onSelected: (List<String>) -> Unit): TempGalleryDialogFragment {
            val fragment = TempGalleryDialogFragment()
            fragment.uris = uris
            fragment.previews = previews
            fragment.types = types
            fragment.onSelected = onSelected
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogTempGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rootLayout.setOnClickListener { dismiss() }
        binding.btnClose.setOnClickListener { dismiss() }

        setupConfirmButton()

        binding.rvTempMedia.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = TempMediaAdapter()
            
            // Detect clicks on empty space in RecyclerView
            setOnTouchListener { _, event ->
                if (event.action == android.view.MotionEvent.ACTION_UP) {
                    val child = findChildViewUnder(event.x, event.y)
                    if (child == null) {
                        dismiss()
                        return@setOnTouchListener true
                    }
                }
                false
            }
        }

        binding.btnConfirmSelection.setOnClickListener {
            if (selectedUris.isNotEmpty()) {
                onSelected?.invoke(selectedUris.toList())
                dismiss()
            }
        }
    }

    private fun setupConfirmButton() {
        val prefs = Prefs(requireContext())
        val params = binding.btnConfirmSelection.layoutParams as RelativeLayout.LayoutParams
        params.removeRule(RelativeLayout.ALIGN_PARENT_START)
        params.removeRule(RelativeLayout.ALIGN_PARENT_END)
        
        if (prefs.homeAlignment == Gravity.END) {
            params.addRule(RelativeLayout.ALIGN_PARENT_END)
        } else {
            params.addRule(RelativeLayout.ALIGN_PARENT_START)
        }
        binding.btnConfirmSelection.layoutParams = params
    }

    private fun toggleSelection(uri: String, position: Int) {
        if (selectedUris.contains(uri)) {
            selectedUris.remove(uri)
        } else {
            selectedUris.add(uri)
        }
        binding.rvTempMedia.adapter?.notifyItemChanged(position)
        binding.rlBottomBar.isVisible = selectedUris.isNotEmpty()
    }

    private inner class TempMediaAdapter : RecyclerView.Adapter<TempMediaAdapter.ViewHolder>() {
        inner class ViewHolder(val binding: ItemChatMediaBinding) : RecyclerView.ViewHolder(binding.root) {
            var loadThread: Thread? = null
            val handler = Handler(Looper.getMainLooper())

            fun cancelLoad() {
                loadThread?.interrupt()
                loadThread = null
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemChatMediaBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val uriStr = uris[position]
            val previewUriStr = previews.getOrNull(position)
            val type = types[position]
            val isSelected = selectedUris.contains(uriStr)
            
            val displayUriStr = if (!previewUriStr.isNullOrEmpty()) previewUriStr else uriStr
            
            holder.cancelLoad()
            holder.binding.ivMedia.setImageDrawable(null)
            holder.binding.ivPlayVideo.visibility = if (type == "VIDEO") View.VISIBLE else View.GONE
            
            val ivAudio = holder.binding.root.findViewById<android.widget.ImageView>(app.olauncher.R.id.ivAudio)
            if (ivAudio != null) {
                ivAudio.visibility = if (type == "AUDIO") View.VISIBLE else View.GONE
            }
            
            if (type == "AUDIO") {
                holder.binding.ivMedia.setBackgroundColor(0x33FFFFFF)
            } else {
                holder.binding.ivMedia.setBackgroundResource(R.drawable.rounded_rect_shade_color)
            }
            
            holder.binding.ivSelected.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.binding.ivMedia.alpha = if (isSelected) 0.6f else 1.0f

            // Load thumbnail asynchronously
            holder.loadThread = Thread {
                try {
                    val uri = Uri.parse(displayUriStr)
                    val context = holder.itemView.context
                    
                    val options = BitmapFactory.Options()
                    options.inJustDecodeBounds = true
                    try {
                        context.contentResolver.openInputStream(uri)
                    } catch (e: Exception) {
                        if (uri.scheme == "file") uri.path?.let { java.io.FileInputStream(it) } else null
                    }?.use { BitmapFactory.decodeStream(it, null, options) }

                    options.inSampleSize = 4 // Downscale for grid thumbnails
                    if (options.outHeight > 2048 || options.outWidth > 2048) {
                        options.inSampleSize = 8
                    }
                    options.inJustDecodeBounds = false

                    val inputStream = try {
                        context.contentResolver.openInputStream(uri)
                    } catch (e: Exception) {
                        if (uri.scheme == "file") uri.path?.let { java.io.FileInputStream(it) } else null
                    }
                    var bitmap = inputStream?.use { BitmapFactory.decodeStream(it, null, options) }

                    if (!Thread.interrupted() && bitmap != null) {
                        bitmap = app.olauncher.data.ChatStorage.rotateBitmapIfRequired(context, bitmap, uri)
                        holder.handler.post {
                            holder.binding.ivMedia.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Throwable) {
                    // Fail silently, show placeholder if needed
                }
            }.apply { start() }

            holder.binding.root.setOnClickListener {
                if (selectedUris.isNotEmpty()) {
                    toggleSelection(uriStr, position)
                } else {
                    if (type == "AUDIO") {
                        toggleSelection(uriStr, position)
                        return@setOnClickListener
                    }
                    val filteredIndices = uris.indices.filter { types[it] != "AUDIO" }
                    val newUris = filteredIndices.map { uris[it] }
                    val newPreviews = filteredIndices.map { previews.getOrNull(it) ?: "" }
                    val newTypes = filteredIndices.map { types[it] }
                    val newPosition = filteredIndices.indexOf(position).coerceAtLeast(0)
                    
                    if (newUris.isNotEmpty()) {
                        MediaPreviewDialogFragment.newInstance(newUris, newPreviews, newTypes, newPosition)
                            .show(childFragmentManager, "temp_preview")
                    }
                }
            }

            holder.binding.root.setOnLongClickListener {
                toggleSelection(uriStr, position)
                true
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            super.onViewRecycled(holder)
            holder.cancelLoad()
        }

        override fun getItemCount() = uris.size
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
