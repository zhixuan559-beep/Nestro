package app.nestro.calendar

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

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
    val sourceSystemCalendarId: Long? = null
)

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_events ORDER BY startAt ASC, id ASC")
    suspend fun allEvents(): List<CalendarEvent>

    @Query("SELECT * FROM calendar_events WHERE id = :id LIMIT 1")
    suspend fun event(id: String): CalendarEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(event: CalendarEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(events: List<CalendarEvent>)

    @Query("DELETE FROM calendar_events")
    suspend fun deleteAll()

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(entities = [CalendarEvent::class], version = 1, exportSchema = false)
abstract class CalendarDatabase : RoomDatabase() {
    abstract fun calendarDao(): CalendarDao

    companion object {
        @Volatile private var instance: CalendarDatabase? = null

        fun get(context: Context): CalendarDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CalendarDatabase::class.java,
                    "nestro_calendar.db"
                ).build().also { instance = it }
            }
    }
}
