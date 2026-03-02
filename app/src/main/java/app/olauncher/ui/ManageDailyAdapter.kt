package app.olauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.TodoItem

class ManageDailyAdapter(
    private var items: List<TodoItem>,
    private val onItemClick: (TodoItem) -> Unit,
    private val onItemLongClick: (TodoItem) -> Unit
) : RecyclerView.Adapter<ManageDailyAdapter.ViewHolder>() {

    private var selectedItemId: Long = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manage_daily, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int {
        return items.size
    }

    fun setItems(items: List<TodoItem>) {
        this.items = items
        notifyDataSetChanged()
    }

    fun setSelectedItem(id: Long) {
        selectedItemId = id
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTaskName: TextView = itemView.findViewById(R.id.tvTaskName)
        private val tvTaskInfo: TextView = itemView.findViewById(R.id.tvTaskInfo)

        fun bind(item: TodoItem) {
            tvTaskName.text = item.task
            val info = StringBuilder()
            if (item.daysOfWeek != null) {
                // Restore showing the full string stored in the database (e.g. "Mon Tue")
                info.append(item.daysOfWeek)
            }
            
            val timeStr = if (item.time != null && item.toTime != null) {
                "${item.time} - ${item.toTime}"
            } else if (item.time != null) {
                item.time
            } else {
                null
            }

            if (timeStr != null) {
                if (info.isNotEmpty()) info.append(" | ")
                info.append(timeStr)
            }
            tvTaskInfo.text = info.toString()

            val isSelected = item.id == selectedItemId
            itemView.isSelected = isSelected

            itemView.setOnClickListener {
                onItemClick(item)
            }

            itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
        }
    }
}
