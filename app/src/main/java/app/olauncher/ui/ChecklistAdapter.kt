package app.olauncher.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.data.TodoItem
import app.olauncher.data.TodoType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChecklistAdapter(
    private var items: List<TodoItem>,
    private val prefs: Prefs,
    private val onLongClickListener: ((TodoItem) -> Unit)? = null,
    private val onCheckedChangeListener: (TodoItem, Boolean) -> Unit
) : RecyclerView.Adapter<ChecklistAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_checklist, parent, false)
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

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTime: TextView = itemView.findViewById(R.id.tvTime)
        private val tvTask: TextView = itemView.findViewById(R.id.tvTask)
        private val strikeThroughLine: View = itemView.findViewById(R.id.strikeThroughLine)

        fun bind(item: TodoItem) {
            tvTask.text = item.task
            val textSizeInPx = itemView.context.resources.getDimension(R.dimen.text_large)
            val threeSp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 3f, itemView.context.resources.displayMetrics)
            tvTask.setTextSize(TypedValue.COMPLEX_UNIT_PX, (textSizeInPx * prefs.textSizeScale) + threeSp)
            tvTime.setTextSize(TypedValue.COMPLEX_UNIT_PX, (textSizeInPx * prefs.textSizeScale) + threeSp)

            when (item.type) {
                TodoType.DAILY -> {
                    if (item.time != null) {
                        tvTime.visibility = View.VISIBLE
                        tvTime.text = item.time
                    } else {
                        tvTime.visibility = View.GONE
                    }
                }
                TodoType.TIMED -> {
                    if (item.dueDate != null) {
                        tvTime.visibility = View.VISIBLE
                        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                        tvTime.text = sdf.format(Date(item.dueDate))
                    } else {
                        tvTime.visibility = View.GONE
                    }
                }
                else -> {
                    tvTime.visibility = View.GONE
                }
            }

            updateVisuals(item, animate = false)

            itemView.setOnClickListener {
                val newCompletedState = !item.isCompleted
                item.isCompleted = newCompletedState
                updateVisuals(item, animate = false)
                onCheckedChangeListener(item, newCompletedState)
            }

            itemView.setOnLongClickListener {
                onLongClickListener?.invoke(item)
                true
            }
        }

        private fun updateVisuals(item: TodoItem, animate: Boolean) {
            val isCompleted = item.isCompleted
            val isOverdue = item.isOverdue(prefs)
            
            val targetColor = when {
                isCompleted -> Color.parseColor("#666666")
                isOverdue -> ContextCompat.getColor(itemView.context, R.color.overdue_crimson)
                else -> Color.WHITE
            }

            val backgroundRes = when {
                isCompleted -> R.drawable.item_checklist_border_completed
                isOverdue -> R.drawable.item_checklist_border_overdue
                else -> R.drawable.item_checklist_border
            }
            
            itemView.setBackgroundResource(backgroundRes)
            strikeThroughLine.visibility = if (isCompleted) View.VISIBLE else View.GONE
            
            // Remove the TextView-level strike-thru as we now use a single line view
            tvTask.paintFlags = tvTask.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            
            if (animate) {
                val colorAnim = ValueAnimator.ofObject(ArgbEvaluator(), tvTask.currentTextColor, targetColor)
                colorAnim.duration = 300
                colorAnim.addUpdateListener { animator ->
                    val color = animator.animatedValue as Int
                    tvTask.setTextColor(color)
                    tvTime.setTextColor(color)
                }
                colorAnim.start()
            } else {
                tvTask.setTextColor(targetColor)
                tvTime.setTextColor(targetColor)
            }
        }
    }
}
