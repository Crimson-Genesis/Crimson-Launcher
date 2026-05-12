package app.olauncher.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.FragmentLogsBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.OutputStream

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
        binding.logsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        binding.logsSwipeRefresh.setOnRefreshListener {
            loadLogsAsync(forceRefresh = true)
        }
        
        loadLogsAsync(forceRefresh = false)
    }

    private fun loadLogsAsync(forceRefresh: Boolean) {
        if (!binding.logsSwipeRefresh.isRefreshing) {
            binding.logsProgressBar.visibility = View.VISIBLE
            binding.logsRecyclerView.visibility = View.GONE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val logFiles = withContext(Dispatchers.IO) {
                fetchLogFiles(forceRefresh)
            }
            
            binding.logsProgressBar.visibility = View.GONE
            binding.logsRecyclerView.visibility = View.VISIBLE
            binding.logsSwipeRefresh.isRefreshing = false
            
            binding.logsRecyclerView.adapter = LogsAdapter(logFiles, prefs.homeAlignment) { item ->
                val bundle = Bundle().apply {
                    putString("logUri", item.uri.toString())
                    putString("logFileName", item.name)
                }
                findNavController().navigate(R.id.action_logsFragment_to_logDetailFragment, bundle)
            }
        }
    }

    private fun fetchLogFiles(forceRefresh: Boolean): List<LogFileItem> {
        val logFiles = mutableListOf<LogFileItem>()
        val context = requireContext()

        // 1. Load internal logs (usually very fast)
        val internalDir = File(context.filesDir, "progress_logs")
        if (internalDir.exists()) {
            internalDir.listFiles()?.forEach { file ->
                if (file.name.startsWith("crimson_log_")) {
                    logFiles.add(LogFileItem(file.name, file.toUri(), file.length(), file.lastModified(), true))
                }
            }
        }

        // 2. Load SAF logs with manifest caching
        val folderUriStr = prefs.storageFolderUri
        if (!folderUriStr.isNullOrEmpty()) {
            try {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(folderUriStr))
                val logsDir = tree?.findFile("logs")
                if (logsDir != null) {
                    val manifest = if (forceRefresh) null else tryLoadManifest(context, logsDir)
                    if (manifest != null) {
                        logFiles.addAll(manifest)
                    } else {
                        // Slow path: scan and save manifest
                        val scanned = mutableListOf<LogFileItem>()
                        logsDir.listFiles().forEach { file ->
                            val name = file.name
                            if (name != null && name.startsWith("crimson_log_")) {
                                scanned.add(LogFileItem(name, file.uri, file.length(), file.lastModified(), false))
                            }
                        }
                        logFiles.addAll(scanned)
                        saveManifest(context, logsDir, scanned)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Sort chronologically (newest first)
        logFiles.sortByDescending { it.lastModified }
        return logFiles
    }

    private fun tryLoadManifest(context: Context, logsDir: DocumentFile): List<LogFileItem>? {
        val manifestFile = logsDir.findFile("logs_manifest.json") ?: return null
        return try {
            context.contentResolver.openInputStream(manifestFile.uri)?.use { input ->
                val json = InputStreamReader(input).readText()
                val array = JSONArray(json)
                val items = mutableListOf<LogFileItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    items.add(LogFileItem(
                        obj.getString("n"),
                        Uri.parse(obj.getString("u")),
                        obj.getLong("s"),
                        obj.getLong("t"),
                        false
                    ))
                }
                items
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveManifest(context: Context, logsDir: DocumentFile, items: List<LogFileItem>) {
        try {
            val manifestFile = logsDir.findFile("logs_manifest.json") ?: logsDir.createFile("application/json", "logs_manifest.json")
            if (manifestFile != null) {
                val array = JSONArray()
                items.forEach { item ->
                    val obj = JSONObject()
                    obj.put("n", item.name)
                    obj.put("u", item.uri.toString())
                    obj.put("s", item.size)
                    obj.put("t", item.lastModified)
                    array.put(obj)
                }
                context.contentResolver.openOutputStream(manifestFile.uri)?.use { output: OutputStream ->
                    OutputStreamWriter(output).use { it.write(array.toString()) }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadLogs() {
        // Redundant now, replaced by loadLogsAsync
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
