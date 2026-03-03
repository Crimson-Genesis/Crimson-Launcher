package app.olauncher.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TodoItem::class, TodoTemplate::class, TodoTemplateItem::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun todoItemDao(): TodoItemDao
    abstract fun todoTemplateDao(): TodoTemplateDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todo_items ADD COLUMN time TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `todo_templates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `todo_template_items` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `templateId` INTEGER NOT NULL, `task` TEXT NOT NULL, `type` TEXT NOT NULL, `dueDate` INTEGER, `time` TEXT, `daysOfWeek` TEXT, FOREIGN KEY(`templateId`) REFERENCES `todo_templates`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_todo_template_items_templateId` ON `todo_template_items` (`templateId`)")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todo_items ADD COLUMN toDate INTEGER")
                db.execSQL("ALTER TABLE todo_items ADD COLUMN toTime TEXT")
                db.execSQL("ALTER TABLE todo_template_items ADD COLUMN toDate INTEGER")
                db.execSQL("ALTER TABLE todo_template_items ADD COLUMN toTime TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA table_info(todo_items)")
                var hasOriginTemplateId = false
                var hasOriginTemplateItemId = false
                var hasAccidentalColumn = false
                
                val nameIndex = cursor.getColumnIndex("name")
                if (nameIndex != -1) {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameIndex)
                        if (name == "originTemplateId") hasOriginTemplateId = true
                        if (name == "originTemplateItemId") hasOriginTemplateItemId = true
                        if (name == "templateItemId") hasAccidentalColumn = true
                    }
                }
                cursor.close()

                if (!hasOriginTemplateId) {
                    db.execSQL("ALTER TABLE todo_items ADD COLUMN originTemplateId INTEGER")
                }
                if (!hasOriginTemplateItemId) {
                    db.execSQL("ALTER TABLE todo_items ADD COLUMN originTemplateItemId INTEGER")
                }
                
                if (hasAccidentalColumn) {
                    db.execSQL("CREATE TABLE todo_items_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, task TEXT NOT NULL, isCompleted INTEGER NOT NULL, type TEXT NOT NULL, dueDate INTEGER, time TEXT, daysOfWeek TEXT, completedAt INTEGER, toDate INTEGER, toTime TEXT, originTemplateId INTEGER, originTemplateItemId INTEGER)")
                    db.execSQL("INSERT INTO todo_items_new (id, task, isCompleted, type, dueDate, time, daysOfWeek, completedAt, toDate, toTime, originTemplateId, originTemplateItemId) SELECT id, task, isCompleted, type, dueDate, time, daysOfWeek, completedAt, toDate, toTime, originTemplateId, originTemplateItemId FROM todo_items")
                    db.execSQL("DROP TABLE todo_items")
                    db.execSQL("ALTER TABLE todo_items_new RENAME TO todo_items")
                }
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
