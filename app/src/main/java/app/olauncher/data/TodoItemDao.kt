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

    @Query("DELETE FROM todo_items")
    suspend fun deleteAll()

    @Query("DELETE FROM todo_items WHERE type = :type")
    suspend fun deleteByType(type: TodoType)

    @Query("SELECT * FROM todo_items")
    fun getAllTodoItems(): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items")
    suspend fun getAllTodoItemsSync(): List<TodoItem>

    @Query("SELECT * FROM todo_items WHERE type = 'DAILY'")
    fun getAllDailyTasks(): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE type = 'DAILY' OR (type = 'TIMED' AND date(dueDate / 1000, 'unixepoch', 'localtime') = date('now', 'localtime'))")
    fun getTodayTodoItems(): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE isCompleted = 1 AND type != 'DAILY'")
    fun getCompletedTodoItems(): LiveData<List<TodoItem>>

    @Query("SELECT * FROM todo_items WHERE isCompleted = 0 AND (type = 'TIMED' OR type = 'TIMELESS')")
    fun getUpcomingTodoItems(): LiveData<List<TodoItem>>

    @Query("UPDATE todo_items SET isCompleted = 0 WHERE type = 'DAILY'")
    suspend fun resetDailyTasks()
}
