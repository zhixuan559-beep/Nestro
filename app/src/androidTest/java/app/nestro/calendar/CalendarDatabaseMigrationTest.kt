package app.nestro.calendar

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarDatabaseMigrationTest {
    @Test
    fun editingAutomationDefinitionAdvancesGenerationAndClearsOldResult() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = CalendarRepository(context)
        val eventId = "calendar-generation-regression"
        repository.delete(eventId)
        val start = System.currentTimeMillis() + 3_600_000L
        try {
            val created = repository.save(
                CalendarEvent(
                    id = eventId, title = "版本化日历任务", description = "", location = "",
                    startAt = start, endAt = start + 60_000L, allDay = false,
                    recurrence = CalendarRules.NONE, reminderMinutes = -1, completed = false,
                    createdAt = start - 1L, updatedAt = start - 1L,
                    automationPrompt = "整理项目周报", automationProjectId = "project-a"
                )
            )
            assertEquals(0L, created.automationGeneration)
            repository.recordAutomationResult(eventId, "FAILED", error = "旧定义失败")

            val edited = repository.save(created.copy(automationDelayMinutes = 30))
            assertEquals(1L, edited.automationGeneration)
            assertEquals("IDLE", edited.automationStatus)
            assertEquals("", edited.automationError)

            val titleOnly = repository.save(edited.copy(title = "只修改标题"))
            assertEquals("标题编辑不应重置任务身份", 1L, titleOnly.automationGeneration)

            val completed = repository.save(titleOnly.copy(completed = true))
            assertEquals(2L, completed.automationGeneration)
            val reactivated = repository.save(completed.copy(completed = false))
            assertEquals(3L, reactivated.automationGeneration)
        } finally {
            repository.delete(eventId)
        }
    }


    @Test
    fun repositoryCanReadPersistedAutomationDispatchState() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = CalendarRepository(context)
        val eventId = "calendar-dispatch-state-regression"
        repository.delete(eventId)
        val start = System.currentTimeMillis() + 3_600_000L
        try {
            repository.save(
                CalendarEvent(
                    id = eventId, title = "派发状态读取", description = "", location = "",
                    startAt = start, endAt = start + 60_000L, allDay = false,
                    recurrence = CalendarRules.NONE, reminderMinutes = -1, completed = false,
                    createdAt = start - 1L, updatedAt = start - 1L
                )
            )
            val taskId = "calendar-task-$eventId"
            repository.recordAutomationDispatch(
                eventId = eventId,
                nextRunAt = start,
                lastRunAt = System.currentTimeMillis(),
                taskId = taskId,
                idempotencyKey = "calendar-key-$eventId"
            )
            val persisted = repository.event(eventId)
            assertNotNull(persisted)
            assertEquals(taskId, persisted?.lastTaskId)
        } finally {
            repository.delete(eventId)
        }
    }

    @Test
    fun versionFiveDatabaseAddsAutomationGenerationWithoutLosingEvents() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "nestro_calendar_migration_v5.db"
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()

        val legacy = SQLiteDatabase.openOrCreateDatabase(file.absolutePath, null)
        legacy.execSQL(
            """
            CREATE TABLE calendar_events (
                id TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT NOT NULL,
                location TEXT NOT NULL,
                startAt INTEGER NOT NULL,
                endAt INTEGER NOT NULL,
                allDay INTEGER NOT NULL,
                recurrence TEXT NOT NULL,
                reminderMinutes INTEGER NOT NULL,
                completed INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                sourceSystemEventId INTEGER,
                sourceSystemCalendarId INTEGER,
                sourceSystemImported INTEGER NOT NULL,
                automationPrompt TEXT NOT NULL DEFAULT '',
                automationProjectId TEXT NOT NULL DEFAULT '',
                automationDelayMinutes INTEGER NOT NULL DEFAULT 0,
                nextRunAt INTEGER NOT NULL DEFAULT 0,
                lastRunAt INTEGER NOT NULL DEFAULT 0,
                lastTaskId TEXT NOT NULL DEFAULT '',
                lastIdempotencyKey TEXT NOT NULL DEFAULT '',
                automationStatus TEXT NOT NULL DEFAULT 'IDLE',
                automationResult TEXT NOT NULL DEFAULT '',
                automationError TEXT NOT NULL DEFAULT '',
                automationUpdatedAt INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(id)
            )
            """.trimIndent()
        )
        legacy.execSQL("CREATE INDEX index_calendar_events_startAt ON calendar_events(startAt)")
        legacy.execSQL("CREATE INDEX index_calendar_events_updatedAt ON calendar_events(updatedAt)")
        legacy.execSQL(
            """
            INSERT INTO calendar_events (
                id, title, description, location, startAt, endAt, allDay, recurrence,
                reminderMinutes, completed, createdAt, updatedAt, sourceSystemImported
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf("legacy-event", "旧日程", "", "", 1_800_000_000_000L, 1_800_000_060_000L, 0, "NONE", -1, 0, 1L, 1L, 0)
        )
        legacy.version = 5
        legacy.close()

        val database = Room.databaseBuilder(context, CalendarDatabase::class.java, databaseName)
            .addMigrations(*CalendarDatabase.ALL_MIGRATIONS)
            .build()
        try {
            val dao = database.calendarDao()
            val migrated = dao.event("legacy-event")
            assertNotNull(migrated)
            assertEquals(0L, migrated?.automationGeneration)

            val start = System.currentTimeMillis() + 60_000L
            dao.put(
                CalendarEvent(
                    id = "generation-event",
                    title = "新日程",
                    description = "",
                    location = "",
                    startAt = start,
                    endAt = start + 60_000L,
                    allDay = false,
                    recurrence = CalendarRules.NONE,
                    reminderMinutes = -1,
                    completed = false,
                    createdAt = start - 1L,
                    updatedAt = start - 1L,
                    automationGeneration = 7L
                )
            )
            assertEquals(7L, dao.event("generation-event")?.automationGeneration)
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }
}
