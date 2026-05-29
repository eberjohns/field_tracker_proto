package com.eberjohns.fieldtracker

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface TrackerDao {
    // Saves a new entry to the database
    @Insert
    suspend fun insertLog(log: TimeLog)

    // Fetches all entries, newest first, for your Logs popup
    @Query("SELECT * FROM time_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<TimeLog>

    // Optional: A quick way to wipe the timesheet for a new day
    @Query("DELETE FROM time_logs")
    suspend fun clearAllLogs()
}