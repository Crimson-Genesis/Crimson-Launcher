package app.olauncher.ui

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentLogsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: Prefs

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = Prefs(requireContext())
        binding.logsTitle.gravity = prefs.homeAlignment
        binding.logsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        loadLogs()
    }

    private fun loadLogs() {
        val logFiles = mutableListOf<LogFileItem>()

        // Load internal logs
        val internalDir = File(requireContext().filesDir, "progress_logs")
        if (internalDir.exists()) {
            internalDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("crimson_log_")) {
                    logFiles.add(LogFileItem(file.name, file.toUri(), file.length(), file.lastModified(), true))
                }
            }
        }

        // Load SAF logs
        val folderUriStr = prefs.storageFolderUri
        if (!folderUriStr.isNullOrEmpty()) {
            try {
                val tree = DocumentFile.fromTreeUri(requireContext(), Uri.parse(folderUriStr))
                val logsDir = tree?.findFile("logs")
                logsDir?.listFiles()?.forEach { file ->
                    if (file.name?.startsWith("crimson_log_") == true) {
                        logFiles.add(LogFileItem(file.name ?: "Unknown", file.uri, file.length(), file.lastModified(), false))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Sort chronologically (newest first)
        logFiles.sortByDescending { it.lastModified }

        binding.logsRecyclerView.adapter = LogsAdapter(logFiles, prefs.homeAlignment) { item ->
            val bundle = Bundle().apply {
                putString("logUri", item.uri.toString())
                putString("logFileName", item.name)
            }
            findNavController().navigate(R.id.action_logsFragment_to_logDetailFragment, bundle)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class LogFileItem(
        val name: String,
        val uri: Uri,
        val size: Long,
        val lastModified: Long,
        val isInternal: Boolean
    )

    private inner class LogsAdapter(
        private val items: List<LogFileItem>,
        private val alignment: Int,
        private val onClick: (LogFileItem) -> Unit
    ) : RecyclerView.Adapter<LogsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.logFileName)
            val info: TextView = view.findViewById(R.id.logFileInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.name.text = item.name
            holder.name.gravity = alignment
            val date = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(item.lastModified))
            val sizeKb = item.size / 1024
            val type = if (item.isInternal) "Internal" else "External"
            holder.info.text = "$date • ${sizeKb}KB • $type"
            holder.info.gravity = alignment
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
