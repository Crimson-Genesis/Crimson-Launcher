package app.olauncher.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.TodoItem

class ManageDailyAdapter(
    private var items: List<TodoItem>,
    private val onEditClick: (TodoItem) -> Unit,
    private val onCopyClick: (TodoItem) -> Unit,
    private val onDeleteClick: (TodoItem) -> Unit
) : RecyclerView.Adapter<ManageDailyAdapter.ViewHolder>() {

    private var selectedItemId: Long = -1
    private var trayVisibleItemId: Long = -1

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

    fun setItems(newItems: List<TodoItem>) {
        val diffCallback = object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = items[oldPos].id == newItems[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) = items[oldPos] == newItems[newPos]
        }
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems.toList()
        diffResult.dispatchUpdatesTo(this)
    }

    fun setSelectedItem(id: Long) {
        selectedItemId = id
        notifyDataSetChanged()
    }

    fun clearTray() {
        if (trayVisibleItemId != -1L) {
            trayVisibleItemId = -1L
            notifyDataSetChanged()
        }
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTaskName: TextView = itemView.findViewById(R.id.tvTaskName)
        private val tvTaskInfo: TextView = itemView.findViewById(R.id.tvTaskInfo)
        private val llTray: View = itemView.findViewById(R.id.llTray)
        private val btnEdit: View = itemView.findViewById(R.id.btnEdit)
        private val btnCopy: View = itemView.findViewById(R.id.btnCopy)
        private val btnDelete: View = itemView.findViewById(R.id.btnDelete)

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

            llTray.visibility = if (trayVisibleItemId == item.id) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                if (trayVisibleItemId == item.id) {
                    trayVisibleItemId = -1L
                    notifyItemChanged(bindingAdapterPosition)
                } else {
                    onEditClick(item)
                }
            }

            itemView.setOnLongClickListener {
                val oldId = trayVisibleItemId
                trayVisibleItemId = if (trayVisibleItemId == item.id) -1L else item.id
                if (oldId != -1L) {
                    val oldPos = items.indexOfFirst { it.id == oldId }
                    if (oldPos != -1) notifyItemChanged(oldPos)
                }
                notifyItemChanged(bindingAdapterPosition)
                true
            }

            btnEdit.setOnClickListener {
                onEditClick(item)
                trayVisibleItemId = -1L
                notifyItemChanged(bindingAdapterPosition)
            }

            btnCopy.setOnClickListener {
                onCopyClick(item)
                trayVisibleItemId = -1L
                notifyItemChanged(bindingAdapterPosition)
            }

            btnDelete.setOnClickListener {
                onDeleteClick(item)
                trayVisibleItemId = -1L
                notifyDataSetChanged()
            }
        }
    }
}
