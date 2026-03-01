package app.olauncher.data

import androidx.lifecycle.LiveData

class TodoTemplateRepository(private val todoTemplateDao: TodoTemplateDao) {

    val allTemplates: LiveData<List<TodoTemplate>> = todoTemplateDao.getAllTemplates()

    suspend fun getAllTemplatesSync(): List<TodoTemplate> {
        return todoTemplateDao.getAllTemplatesSync()
    }

    suspend fun insertTemplateWithItems(template: TodoTemplate, items: List<TodoTemplateItem>): Long {
        return todoTemplateDao.insertTemplateWithItems(template, items)
    }

    suspend fun getTemplateWithItems(templateId: Long): TodoTemplateWithItems? {
        return todoTemplateDao.getTemplateWithItems(templateId)
    }

    suspend fun deleteTemplate(template: TodoTemplate) {
        todoTemplateDao.deleteTemplate(template)
    }

    suspend fun updateTemplate(template: TodoTemplate) {
        todoTemplateDao.updateTemplate(template)
    }
}
