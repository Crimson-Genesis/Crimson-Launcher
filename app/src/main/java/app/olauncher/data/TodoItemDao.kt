package app.olauncher.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TodoItemDao {
    @Insert
    suspend fun insert(todoItem: TodoItem): Long

    @Update
    suspend fun update(todoItem: TodoItem)

    @Delete
    suspend fun delete(todoItem: TodoItem)

    @Query("SELECT * FROM todo_items WHERE id = :id")
    suspend fun getById(id: Long): TodoItem?

    @Query("SELECT * FROM todo_items WHERE originTemplateItemId = :templateItemId LIMIT 1")
    suspend fun getByTemplateItemId(templateItemId: Long): TodoItem?

    @Query("DELETE FROM todo_items")
    suspend fun deleteAll()

    @Query("DELETE FROM todo_items WHERE type = :type")
    suspend fun deleteByType(type: TodoType)

    @Query("DELETE FROM todo_items WHERE type = 'DAILY' AND originTemplateId = :templateId AND isCompleted = 0")
    suspend fun deleteUncompletedDailyByTemplateId(templateId: Long)

    @Query("SELECT * FROM todo_items")
    fun getAllTodoItems(): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items")
    suspend fun getAllTodoItemsSync(): List<TodoItem>

    @Query("SELECT * FROM todo_items WHERE type = 'DAILY'")
    fun getAllDailyTasks(): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE type = 'DAILY' AND (originTemplateId IS NULL OR originTemplateId = :templateId)")
    fun getDailyTasksForTemplate(templateId: Long): LiveData<List<TodoItem>>

    @Query("""
        SELECT * FROM todo_items 
        WHERE ((type = 'DAILY' AND (originTemplateId IS NULL OR originTemplateId = :templateId)) AND (daysOfWeek IS NULL OR daysOfWeek = '' OR daysOfWeek LIKE '%' || :dayOfWeek || '%'))
        OR (type = 'TIMED' AND (originTemplateId IS NULL OR originTemplateId = :templateId) AND (
            :logicalDate BETWEEN date(dueDate / 1000, 'unixepoch', 'localtime') AND date(COALESCE(toDate, dueDate) / 1000, 'unixepoch', 'localtime')
            OR (:logicalDate = date((dueDate / 1000) + 86400, 'unixepoch', 'localtime') 
                AND toDate IS NULL 
                AND time IS NOT NULL 
                AND toTime IS NOT NULL 
                AND (
                    (CAST(strftime('%H', time) AS INTEGER) * 60 + CAST(strftime('%M', time) AS INTEGER)) > 
                    (CAST(strftime('%H', toTime) AS INTEGER) * 60 + CAST(strftime('%M', toTime) AS INTEGER))
                )
            )
        ))
    """)
    fun getTodayTodoItems(logicalDate: String, dayOfWeek: String, templateId: Long): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE isCompleted = 1 AND type != 'DAILY' AND (originTemplateId IS NULL OR originTemplateId = :templateId)")
    fun getCompletedTodoItems(templateId: Long): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE isCompleted = 0 AND (type = 'TIMED' OR type = 'TIMELESS') AND (originTemplateId IS NULL OR originTemplateId = :templateId)")
    fun getUpcomingTodoItems(templateId: Long): LiveData<List<TodoItem>>

    @Query("UPDATE todo_items SET isCompleted = 0, completedAt = NULL WHERE type = 'DAILY'")
    suspend fun resetDailyTasks()

    @Query("DELETE FROM todo_items WHERE type = 'DAILY' AND originTemplateId = :templateId")
    suspend fun deleteAllDailyByTemplateId(templateId: Long)
}
