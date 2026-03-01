package app.olauncher.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "todo_templates")
data class TodoTemplate(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "todo_template_items",
    foreignKeys = [
        ForeignKey(
            entity = TodoTemplate::class,
            parentColumns = ["id"],
            childColumns = ["templateId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("templateId")]
)
data class TodoTemplateItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val templateId: Long,
    val task: String,
    val type: TodoType,
    val dueDate: Long? = null,
    val time: String? = null,
    val daysOfWeek: String? = null
)
