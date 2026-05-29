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

class TimesheetService : Service() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // Tracks which phase the service is currently in
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
        if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.d("TimesheetService", "OS triggered 150m ENTER. Checking Polygon...")
            checkInitialEntry()
        } else if (transitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d("TimesheetService", "OS triggered 150m EXIT. Forcing logout.")
            // If they completely left the 150m circle, force an EXIT log.
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
                        // PHASE 1: They hit the circle AND the polygon at the same time.
                        Log.d("TimesheetService", "Instantly inside polygon. Logging ENTER.")
                        logEnter(currentPoint)
                        startHeartbeatMode()
                    } else {
                        // PHASE 2: Inside the circle, but still walking to the polygon. Start Hunting.
                        startHuntingMode()
                    }
                } else {
                    // Location was null, start hunting anyway to be safe
                    startHuntingMode()
                }
            }
    }

    // ==========================================
    // PHASE 2: THE HUNTER (High Frequency)
    // ==========================================
    private fun startHuntingMode() {
        if (isHunting || isTracking) return
        isHunting = true

        updateNotification("Approaching site. Waiting for boundary cross...")
        Log.d("TimesheetService", "Hunting mode started.")

        serviceScope.launch {
            var attempts = 0
            // Hunt for up to 15 minutes (90 attempts * 10 seconds)
            while (attempts < 90 && isHunting) {
                delay(10 * 1000L) // Poll every 10 seconds while walking up to the building

                if (!hasLocationPermission()) break

                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                    if (loc != null) {
                        val currentPoint = LatLng(loc.latitude, loc.longitude)
                        val activePolygon = getActivePolygonFromMemory()

                        if (GeoUtils.isPointInPolygon(currentPoint, activePolygon)) {
                            Log.d("TimesheetService", "Hunter detected ENTER! Logging and switching to Heartbeat.")
                            isHunting = false
                            logEnter(currentPoint)
                            startHeartbeatMode()
                        }
                    }
                }
                attempts++
            }

            // If they never entered the polygon after 15 mins of hunting, shut down to save battery
            if (isHunting) {
                Log.d("TimesheetService", "Hunting timed out. User never entered polygon.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // ==========================================
    // PHASE 3: THE HEARTBEAT (Low Frequency)
    // ==========================================
    private fun startHeartbeatMode() {
        if (isTracking) return
        isTracking = true
        isHunting = false

        updateNotification("On Site: Tracking Hours")
        Log.d("TimesheetService", "Heartbeat mode started.")

        serviceScope.launch {
            while (isTracking) {
                // Poll every 1 minute for testing (Change to 15 * 60 * 1000L for production)
                delay(1 * 60 * 1000L)

                if (!hasLocationPermission()) break

                fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).addOnSuccessListener { loc ->
                    if (loc != null) {
                        val currentPoint = LatLng(loc.latitude, loc.longitude)
                        val activePolygon = getActivePolygonFromMemory()

                        if (!GeoUtils.isPointInPolygon(currentPoint, activePolygon)) {
                            Log.d("TimesheetService", "Heartbeat detected EXIT. Logging out.")
                            logExitAndStop(currentPoint)
                        } else {
                            Log.d("TimesheetService", "Heartbeat passed. Still inside.")
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // DATABASE HELPERS
    // ==========================================
    private fun logEnter(point: LatLng) {
        serviceScope.launch {
            database.trackerDao().insertLog(
                TimeLog(eventType = "ENTER", timestamp = System.currentTimeMillis(), latitude = point.latitude, longitude = point.longitude)
            )
        }
    }

    private fun logExitAndStop(point: LatLng?) {
        isTracking = false
        isHunting = false

        serviceScope.launch {
            database.trackerDao().insertLog(
                TimeLog(
                    eventType = "EXIT",
                    timestamp = System.currentTimeMillis(),
                    latitude = point?.latitude ?: 0.0,
                    longitude = point?.longitude ?: 0.0
                )
            )
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