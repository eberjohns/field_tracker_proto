package com.eberjohns.fieldtracker

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "time_logs")
data class TimeLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val eventType: String, // We will save "ENTER" or "EXIT" here
    val timestamp: Long,   // The exact time you crossed the line
    val latitude: Double,
    val longitude: Double
)