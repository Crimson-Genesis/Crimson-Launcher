package app.olauncher.data

import androidx.lifecycle.LiveData
import androidx.lifecycle.map

class TodoItemRepository(private val todoItemDao: TodoItemDao) {

    fun getTodayTodoItems(logicalDate: String, dayOfWeek: String, templateId: Long): LiveData<List<TodoItem>> {
        return todoItemDao.getTodayTodoItems(logicalDate, dayOfWeek, templateId).map { 
            TodoSorter.sortToday(it) 
        }
    }
    
    fun getCompletedTodoItems(templateId: Long): LiveData<List<TodoItem>> = todoItemDao.getCompletedTodoItems(templateId).map { 
        TodoSorter.sortCompleted(it) 
    }
    
    fun getUpcomingTodoItems(templateId: Long): LiveData<List<TodoItem>> = todoItemDao.getUpcomingTodoItems(templateId).map { 
        TodoSorter.sortUpcoming(it) 
    }

    fun getDailyTasksForTemplate(templateId: Long): LiveData<List<TodoItem>> {
        return todoItemDao.getDailyTasksForTemplate(templateId).map {
            TodoSorter.sortDaily(it)
        }
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

    suspend fun getByTemplateItemId(templateItemId: Long): TodoItem? {
        return todoItemDao.getByTemplateItemId(templateItemId)
    }

    suspend fun getAllTodoItemsSync(): List<TodoItem> {
        return todoItemDao.getAllTodoItemsSync()
    }

    suspend fun deleteUncompletedDailyByTemplateId(templateId: Long) {
        todoItemDao.deleteUncompletedDailyByTemplateId(templateId)
    }

    suspend fun deleteAllDailyByTemplateId(templateId: Long) {
        todoItemDao.deleteAllDailyByTemplateId(templateId)
    }
}
