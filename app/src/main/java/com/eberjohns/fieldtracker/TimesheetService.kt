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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.annotation.SuppressLint

@SuppressLint("MissingPermission")
class TimesheetService : Service() {

    // ========================
    // CONFIGURATION VARIABLES
    // ========================
    companion object {
        // Phase 2: How often to poll while hunting for the polygon entry
        const val HUNTING_POLL_INTERVAL_MS = 90 * 1000L // 1.5 Minute

        // Phase 3: How often to poll while sitting inside the polygon
        const val HEARTBEAT_POLL_INTERVAL_MS = 30 * 60 * 1000L // 30 Minutes

        // Maximum number of hunting attempts before giving up (e.g., 15 attempts * 1 min = 15 mins)
        const val MAX_HUNTING_ATTEMPTS = 30
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    private var isHunting = false
    private var isTracking = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        database = AppDatabase.getDatabase(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "TIMESHEET_CHANNEL")
            .setContentTitle("Field Tracker")
            .setContentText("Initializing tracking...")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()

        startForeground(1, notification)

        val transitionType = intent?.getIntExtra("TRANSITION_TYPE", -1)
        if (transitionType != -1) {
            handleGeofenceTrigger(transitionType!!)
        }

        return START_STICKY
    }

    private fun handleGeofenceTrigger(transitionType: Int) {
        // Catch BOTH Enter and Dwell triggers here
        if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER ||
            transitionType == Geofence.GEOFENCE_TRANSITION_DWELL) {

            Log.d("TimesheetService", "OS triggered ENTER or DWELL. Checking Polygon...")

            // This will restart the GPS check and the Hunting phase if they aren't inside yet
            checkInitialEntry()

        } else if (transitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d("TimesheetService", "OS triggered EXIT. Forcing logout.")
            logExitAndStop(null)
        }
    }

    private fun checkInitialEntry() {
        if (!hasLocationPermission()) return

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    val currentPoint = LatLng(location.latitude, location.longitude)
                    val activePolygon = getActivePolygonFromMemory()

                    if (GeoUtils.isPointInPolygon(currentPoint, activePolygon)) {
                        Log.d("TimesheetService", "Instantly inside polygon. Logging ENTER.")
                        logEnter(currentPoint)
                        startHeartbeatMode()
                    } else {
                        startHuntingMode()
                    }
                } else {
                    startHuntingMode()
                }
            }
    }

    private fun startHuntingMode() {
        if (isHunting || isTracking) return
        isHunting = true

        updateNotification("Approaching site. Waiting for boundary cross...")

        serviceScope.launch {
            var attempts = 0
            while (attempts < MAX_HUNTING_ATTEMPTS && isHunting) {
                delay(HUNTING_POLL_INTERVAL_MS)

                if (!hasLocationPermission()) break

                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                    if (loc != null) {
                        val currentPoint = LatLng(loc.latitude, loc.longitude)
                        val activePolygon = getActivePolygonFromMemory()

                        if (GeoUtils.isPointInPolygon(currentPoint, activePolygon)) {
                            Log.d("TimesheetService", "Hunter detected ENTER! Switching to Heartbeat.")
                            isHunting = false
                            logEnter(currentPoint)
                            startHeartbeatMode()
                        }
                    }
                }
                attempts++
            }

            if (isHunting) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun startHeartbeatMode() {
        if (isTracking) return
        isTracking = true
        isHunting = false

        updateNotification("On Site: Tracking Hours")

        serviceScope.launch {
            while (isTracking) {
                delay(HEARTBEAT_POLL_INTERVAL_MS)

                if (!hasLocationPermission()) break

                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                    if (loc != null) {
                        val currentPoint = LatLng(loc.latitude, loc.longitude)
                        val activePolygon = getActivePolygonFromMemory()

                        if (!GeoUtils.isPointInPolygon(currentPoint, activePolygon)) {
                            Log.d("TimesheetService", "Heartbeat detected EXIT. Logging out.")
                            logExitAndStop(currentPoint)
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // DATABASE HELPERS (With Double-Entry Protection)
    // ==========================================
    private fun logEnter(point: LatLng) {
        serviceScope.launch {
            val lastLog = database.trackerDao().getLastLog()

            // Only insert if the last action wasn't already an ENTER
            if (lastLog?.eventType != "ENTER") {
                database.trackerDao().insertLog(
                    TimeLog(eventType = "ENTER", timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude)
                )
            } else {
                Log.d("TimesheetService", "ENTER log skipped to prevent duplicate.")
            }
        }
    }

    private fun logExitAndStop(point: LatLng?) {
        isTracking = false
        isHunting = false

        serviceScope.launch {
            val lastLog = database.trackerDao().getLastLog()

            // Only insert if the last action wasn't already an EXIT (or if DB is empty)
            if (lastLog != null && lastLog.eventType != "EXIT") {
                database.trackerDao().insertLog(
                    TimeLog(
                        eventType = "EXIT",
                        timestamp = System.currentTimeMillis(),
                        latitude = point?.latitude ?: 0.0,
                        longitude = point?.longitude ?: 0.0
                    )
                )
            } else {
                Log.d("TimesheetService", "EXIT log skipped to prevent duplicate.")
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun getActivePolygonFromMemory(): List<LatLng> {
        val prefs = getSharedPreferences("FieldTrackerPrefs", Context.MODE_PRIVATE)
        val serialized = prefs.getString("ACTIVE_POLYGON", "") ?: ""
        if (serialized.isEmpty()) return emptyList()
        return serialized.split("|").mapNotNull {
            val parts = it.split(",")
            if (parts.size == 2) {
                try { LatLng(parts[0].toDouble(), parts[1].toDouble()) } catch (e: Exception) { null }
            } else null
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
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
        getSystemService(NotificationManager::class.java).notify(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}