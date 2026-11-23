package app.nestro.calendar

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "calendar_events",
    indices = [Index(value = ["startAt"]), Index(value = ["updatedAt"])]
)
data class CalendarEvent(
    @PrimaryKey val id: String,
    val title: String,
    val description: String,
    val location: String,
    val startAt: Long,
    val endAt: Long,
    val allDay: Boolean,
    val recurrence: String,
    val reminderMinutes: Int,
    val completed: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val sourceSystemEventId: Long? = null,
    val sourceSystemCalendarId: Long? = null,
    val sourceSystemImported: Boolean = false,
    @ColumnInfo(defaultValue = "''") val automationPrompt: String = "",
    @ColumnInfo(defaultValue = "''") val automationProjectId: String = "",
    @ColumnInfo(defaultValue = "0") val automationDelayMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val automationGeneration: Long = 0L,
    @ColumnInfo(defaultValue = "0") val nextRunAt: Long = 0L,
    @ColumnInfo(defaultValue = "0") val lastRunAt: Long = 0L,
    @ColumnInfo(defaultValue = "''") val lastTaskId: String = "",
    @ColumnInfo(defaultValue = "''") val lastIdempotencyKey: String = "",
    @ColumnInfo(defaultValue = "'IDLE'") val automationStatus: String = "IDLE",
    @ColumnInfo(defaultValue = "''") val automationResult: String = "",
    @ColumnInfo(defaultValue = "''") val automationError: String = "",
    @ColumnInfo(defaultValue = "0") val automationUpdatedAt: Long = 0L
)

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_events ORDER BY startAt ASC, id ASC")
    suspend fun allEvents(): List<CalendarEvent>

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun event(id: String): CalendarEvent?

    @Query("SELECT * FROM calendar_events WHERE sourceSystemCalendarId = :calendarId AND sourceSystemEventId = :eventId AND sourceSystemImported = 0 LIMIT 1")
    suspend fun exportedSystemEvent(calendarId: Long, eventId: Long): CalendarEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(event: CalendarEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(events: List<CalendarEvent>)

    @Query("DELETE FROM calendar_events")
    suspend fun deleteAll()

    @Query("UPDATE calendar_events SET nextRunAt = :nextRunAt, lastRunAt = :lastRunAt, lastTaskId = :taskId, lastIdempotencyKey = :idempotencyKey, automationStatus = 'QUEUED', automationResult = '', automationError = '', automationUpdatedAt = :updatedAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun recordAutomationDispatch(
        id: String,
        nextRunAt: Long,
        lastRunAt: Long,
        taskId: String,
        idempotencyKey: String,
        updatedAt: Long
    )

    @Query("UPDATE calendar_events SET nextRunAt = :nextRunAt, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateAutomationNextRunAt(id: String, nextRunAt: Long, updatedAt: Long)

    @Query("UPDATE calendar_events SET automationStatus = :status, automationResult = :result, automationError = :error, automationUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun recordAutomationResult(
        id: String,
        status: String,
        result: String,
        error: String,
        updatedAt: Long
    )

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(entities = [CalendarEvent::class], version = 6, exportSchema = false)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationPrompt TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationProjectId TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN nextRunAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN lastRunAt INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN lastTaskId TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN lastIdempotencyKey TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationStatus TEXT NOT NULL DEFAULT 'IDLE'")
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationResult TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationError TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationUpdatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationDelayMinutes INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE calendar_events ADD COLUMN automationGeneration INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6
        )

        @Volatile private var instance: CalendarDatabase? = null

        fun get(context: Context): CalendarDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CalendarDatabase::class.java,
                    "nestro_calendar.db"
                ).addMigrations(*ALL_MIGRATIONS).build().also { instance = it }
            }
    }
}
