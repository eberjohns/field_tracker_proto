package com.eberjohns.fieldtracker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel

class TimesheetService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = AppDatabase.getDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1. Immediately start the foreground notification to satisfy Android OS
        val notification = NotificationCompat.Builder(this, "TIMESHEET_CHANNEL")
            .setContentTitle("Field Tracker")
            .setContentText("Verifying location...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        startForeground(1, notification)

        // 2. Process the trigger
        val transitionType = intent?.getIntExtra("TRANSITION_TYPE", -1)
        if (transitionType != -1) {
            verifyLocationAndLog(transitionType!!)
        }

        return START_STICKY
    }

    private fun verifyLocationAndLog(transitionType: Int) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return
        }

        // Request exactly ONE high-accuracy GPS ping
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val currentPoint = LatLng(location.latitude, location.longitude)

                    // Fetch the polygon from SharedPreferences
                    val activePolygon = getActivePolygonFromMemory()

                    val isInsidePolygon = GeoUtils.isPointInPolygon(currentPoint, activePolygon)

                    serviceScope.launch {
                        if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER && isInsidePolygon) {
                            Log.d("TimesheetService", "Verified ENTER. Saving to DB.")

                            // FIXED: Changed 'type' to 'eventType' and removed 'worksiteId'
                            database.trackerDao().insertLog(
                                TimeLog(eventType = "ENTER", timestamp = System.currentTimeMillis(), latitude = currentPoint.latitude, longitude = currentPoint.longitude)
                            )
                            updateNotification("On Site: Tracking Hours")
                            start30MinuteHeartbeat()

                        } else if (transitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
                            Log.d("TimesheetService", "Verified EXIT. Saving to DB.")

                            // FIXED: Changed 'type' to 'eventType' and removed 'worksiteId'
                            database.trackerDao().insertLog(
                                TimeLog(eventType = "EXIT", timestamp = System.currentTimeMillis(), latitude = currentPoint.latitude, longitude = currentPoint.longitude)
                            )
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else {
                            Log.d("TimesheetService", "False trigger. Ignored.")
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                }
            }
    }

    private fun start30MinuteHeartbeat() {
        Log.d("TimesheetService", "Heartbeat engine started.")

        serviceScope.launch {
            while (true) {
                // For testing purposes right now, change this to 60000L (1 minute)
                delay(1 * 60 * 1000L)

                Log.d("TimesheetService", "Heartbeat tick: Verifying employee location...")

                if (ContextCompat.checkSelfPermission(this@TimesheetService, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                    fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .addOnSuccessListener { location ->
                            if (location != null) {
                                val currentPoint = LatLng(location.latitude, location.longitude)
                                val activePolygon = getActivePolygonFromMemory()

                                val isInside = GeoUtils.isPointInPolygon(currentPoint, activePolygon)

                                if (!isInside) {
                                    Log.d("TimesheetService", "Heartbeat detected EXIT. Employee is no longer in the polygon.")

                                    // Launch a new coroutine to handle the database write safely
                                    serviceScope.launch {
                                        // FIXED: Changed 'type' to 'eventType' and removed 'worksiteId'
                                        database.trackerDao().insertLog(
                                            TimeLog(
                                                eventType = "EXIT",
                                                timestamp = System.currentTimeMillis(),
                                                latitude = currentPoint.latitude,
                                                longitude = currentPoint.longitude
                                            )
                                        )
                                        // Kill the background loop and stop the service
                                        stopForeground(STOP_FOREGROUND_REMOVE)
                                        stopSelf()
                                    }
                                } else {
                                    Log.d("TimesheetService", "Heartbeat check passed. Employee still on site.")
                                }
                            }
                        }
                }
            }
        }
    }

    private fun getActivePolygonFromMemory(): List<LatLng> {
        val prefs = getSharedPreferences("FieldTrackerPrefs", Context.MODE_PRIVATE)
        val serialized = prefs.getString("ACTIVE_POLYGON", "") ?: ""

        if (serialized.isEmpty()) return emptyList()

        return serialized.split("|").mapNotNull {
            val parts = it.split(",")
            if (parts.size == 2) {
                try {
                    LatLng(parts[0].toDouble(), parts[1].toDouble())
                } catch (e: Exception) { null }
            } else null
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("TIMESHEET_CHANNEL", "Timesheet Tracking", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, "TIMESHEET_CHANNEL")
            .setContentTitle("Field Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("TimesheetService", "Service destroyed. Cancelling heartbeat.")
        // This instantly kills the 30-minute while(true) loop
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}