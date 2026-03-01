package app.olauncher.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.data.TodoTemplate
import app.olauncher.databinding.ItemTemplateBinding

class TemplateAdapter(
    private var items: List<TodoTemplate>,
    private val onItemClick: (TodoTemplate) -> Unit,
    private val onItemLongClick: (TodoTemplate) -> Unit,
    private val onDeleteClick: (TodoTemplate) -> Unit
) : RecyclerView.Adapter<TemplateAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemTemplateBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTemplateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvTemplateName.text = item.name
        holder.binding.root.setOnClickListener { onItemClick(item) }
        holder.binding.root.setOnLongClickListener {
            onItemLongClick(item)
            true
        }
        holder.binding.ivDelete.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<TodoTemplate>) {
        items = newItems
        notifyDataSetChanged()
    }
}
