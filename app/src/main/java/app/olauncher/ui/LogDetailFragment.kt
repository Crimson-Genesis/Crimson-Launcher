package app.olauncher.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.databinding.FragmentLogDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class LogDetailFragment : Fragment() {

    private var _binding: FragmentLogDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: app.olauncher.data.Prefs

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLogDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = app.olauncher.data.Prefs(requireContext())
        val logUri = arguments?.getString("logUri") ?: return
        val logFileName = arguments?.getString("logFileName") ?: "Log Detail"

        binding.logTitle.text = logFileName
        binding.logEntriesRecyclerView.layoutManager = LinearLayoutManager(requireContext())

        loadLogEntries(logUri)
    }

    private fun loadLogEntries(uriStr: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val entries = mutableListOf<LogEntry>()
            try {
                val uri = uriStr.toUri()
                val inputStream = if (uri.scheme == "file") {
                    java.io.File(uri.path!!).inputStream()
                } else {
                    requireContext().contentResolver.openInputStream(uri)
                }

                inputStream?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line ->
                        try {
                            val json = JSONObject(line)
                            val timestamp = json.optLong("ts", 0)
                            val event = json.optString("event", "Unknown")
                            // Remove already displayed or redundant fields from data
                            val data = JSONObject(line)
                            data.remove("ts")
                            data.remove("ts_human")
                            data.remove("ts_local_human")
                            data.remove("event")
                            entries.add(LogEntry(timestamp, event, data.toString(2)))
                        } catch (e: Exception) {
                            Log.e("LogDetail", "Failed to parse line: $line", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LogDetail", "Failed to read log file", e)
            }

            // Sort entries chronologically (oldest first for a single file usually makes sense, or newest first?)
            // Usually logs are read top to bottom, so let's keep them as they appear or sort by timestamp.
            entries.sortByDescending { it.timestamp }

            withContext(Dispatchers.Main) {
                binding.logEntriesRecyclerView.adapter = LogEntriesAdapter(entries)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class LogEntry(
        val timestamp: Long,
        val event: String,
        val data: String
    )

    private inner class LogEntriesAdapter(
        private val items: List<LogEntry>
    ) : RecyclerView.Adapter<LogEntriesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val time: TextView = view.findViewById(R.id.entryTime)
            val event: TextView = view.findViewById(R.id.entryEvent)
            val data: TextView = view.findViewById(R.id.entryData)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val date = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(item.timestamp))
            holder.time.text = date
            holder.event.text = item.event
            holder.data.text = if (item.data == "{}") "" else item.data
            holder.data.visibility = if (item.data == "{}") View.GONE else View.VISIBLE
        }

        override fun getItemCount() = items.size
    }
}
