package app.olauncher.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface TodoTemplateDao {
    @Insert
    suspend fun insertTemplate(template: TodoTemplate): Long

    @Insert
    suspend fun insertTemplateItems(items: List<TodoTemplateItem>)

    @Transaction
    suspend fun insertTemplateWithItems(template: TodoTemplate, items: List<TodoTemplateItem>): Long {
        val templateId = insertTemplate(template)
        val itemsWithId = items.map { it.copy(templateId = templateId) }
        insertTemplateItems(itemsWithId)
        return templateId
    }

    @Query("SELECT * FROM todo_templates ORDER BY createdAt DESC")
    fun getAllTemplates(): LiveData<List<TodoTemplate>>

    @Query("SELECT * FROM todo_templates ORDER BY createdAt DESC")
    suspend fun getAllTemplatesSync(): List<TodoTemplate>

    @Query("SELECT * FROM todo_templates WHERE name = 'Default' LIMIT 1")
    suspend fun getDefaultTemplate(): TodoTemplate?

    @Query("SELECT * FROM todo_template_items WHERE templateId = :templateId")
    suspend fun getItemsForTemplate(templateId: Long): List<TodoTemplateItem>

    @Delete
    suspend fun deleteTemplate(template: TodoTemplate)

    @Update
    suspend fun updateTemplate(template: TodoTemplate)

    @Transaction
    @Query("SELECT * FROM todo_templates WHERE id = :templateId")
    suspend fun getTemplateWithItems(templateId: Long): TodoTemplateWithItems?

    @Transaction
    @Query("SELECT * FROM todo_templates")
    suspend fun getAllTemplatesWithItemsSync(): List<TodoTemplateWithItems>

    @Query("DELETE FROM todo_templates")
    suspend fun deleteAllTemplates()

    @Query("UPDATE todo_template_items SET task = :newTask, time = :newTime, daysOfWeek = :newDaysOfWeek, toDate = :newToDate, toTime = :newToTime WHERE templateId = :templateId AND task = :oldTask AND type = 'DAILY'")
    suspend fun updateDailyTaskInTemplate(templateId: Long, oldTask: String, newTask: String, newTime: String?, newDaysOfWeek: String?, newToDate: Long?, newToTime: String?)

    @Query("DELETE FROM todo_template_items WHERE templateId = :templateId AND task = :task AND type = 'DAILY'")
    suspend fun deleteDailyTaskFromTemplate(templateId: Long, task: String)

    @Insert
    suspend fun insertTemplateItem(item: TodoTemplateItem)
}

data class TodoTemplateWithItems(
    @androidx.room.Embedded val template: TodoTemplate,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "templateId"
    )
    val items: List<TodoTemplateItem>
)
