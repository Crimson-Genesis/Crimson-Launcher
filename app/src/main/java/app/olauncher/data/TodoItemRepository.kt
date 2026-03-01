package app.olauncher.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.map

class TodoItemRepository(private val todoItemDao: TodoItemDao) {

    val todayTodoItems: LiveData<List<TodoItem>> = todoItemDao.getTodayTodoItems().map { 
        TodoSorter.sortToday(it) 
    }
    
    val completedTodoItems: LiveData<List<TodoItem>> = todoItemDao.getCompletedTodoItems().map { 
        TodoSorter.sortCompleted(it) 
    }
    
    val upcomingTodoItems: LiveData<List<TodoItem>> = todoItemDao.getUpcomingTodoItems().map { 
        TodoSorter.sortUpcoming(it) 
    }
    
    val allDailyTasks: LiveData<List<TodoItem>> = todoItemDao.getAllDailyTasks().map { 
        TodoSorter.sortDaily(it) 
    }

    suspend fun insert(todoItem: TodoItem): Long {
        return todoItemDao.insert(todoItem)
    }

    suspend fun update(todoItem: TodoItem) {
        todoItemDao.update(todoItem)
    }

    suspend fun delete(todoItem: TodoItem) {
        todoItemDao.delete(todoItem)
    }

    suspend fun deleteAll() {
        todoItemDao.deleteAll()
    }

    suspend fun deleteByType(type: TodoType) {
        todoItemDao.deleteByType(type)
    }

    suspend fun getById(id: Long): TodoItem? {
        return todoItemDao.getById(id)
    }

    suspend fun getAllTodoItemsSync(): List<TodoItem> {
        return todoItemDao.getAllTodoItemsSync()
    }
}
