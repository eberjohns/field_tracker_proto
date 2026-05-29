package com.eberjohns.fieldtracker

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var geofencingClient: GeofencingClient

    private val polygonPoints = mutableListOf<LatLng>()
    private var currentPolygon: Polygon? = null
    private var currentCircle: Circle? = null

    // Lazy load the PendingIntent to trigger our Receiver
    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    }

    // Permission Launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            enableMyLocation()
            // Android 11+ requires requesting background location separately after foreground is granted
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestBackgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    private val requestBackgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) showMessage("Background location required for tracking")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        geofencingClient = LocationServices.getGeofencingClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        findViewById<Button>(R.id.btnSave).setOnClickListener { saveGeofence() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { clearMap() }
        findViewById<Button>(R.id.btnTimesheet).setOnClickListener {
            showTimesheetDialog() // <--- Triggers the Room Database read
        }

        requestPermissions()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        enableMyLocation()

        // Tap to draw polygon
        mMap.setOnMapClickListener { latLng ->
            polygonPoints.add(latLng)
            drawShapes()
        }
    }

    private fun drawShapes() {
        currentPolygon?.remove()
        currentCircle?.remove()

        if (polygonPoints.isNotEmpty()) {
            val polygonOptions = PolygonOptions().addAll(polygonPoints)
                .strokeColor(Color.BLACK).fillColor(Color.argb(50, 0, 0, 0))
            currentPolygon = mMap.addPolygon(polygonOptions)

            // Draw the invisible bounding circle for visual confirmation during testing
            if (polygonPoints.size >= 3) {
                val (center, radius) = GeoUtils.getBoundingCircle(polygonPoints)
                currentCircle = mMap.addCircle(
                    CircleOptions().center(center).radius(radius.toDouble())
                        .strokeColor(Color.BLUE).fillColor(Color.argb(30, 0, 0, 255))
                )
            }
        }
    }

    private fun saveGeofence() {
        if (polygonPoints.size < 3) {
            showMessage("Need at least 3 points!")
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            showMessage("Grant 'Allow all the time' location permission first.")
            return
        }

        val (center, radius) = GeoUtils.getBoundingCircle(polygonPoints)

        // 1. Save polygon to phone memory so the Service can use it for verification
        savePolygonToMemory(polygonPoints)

        // 2. Build the OS Geofence
        val geofence = Geofence.Builder()
            .setRequestId("ACTIVE_WORKSITE")
            .setCircularRegion(center.latitude, center.longitude, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        // 3. Register with OS
        geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent).run {
            addOnSuccessListener {
                showMessage("Tracking armed! Net Radius: ${radius.toInt()}m")
            }
            addOnFailureListener { e ->
                showMessage("Failed: ${e.message}")
            }
        }
    }

    // ==========================================
    // ROOM DATABASE TIMESHEET UI LOGIC
    // ==========================================
    private fun showTimesheetDialog() {
        // 1. Run database query on a background thread (Dispatchers.IO)
        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(this@MainActivity)
            val logs = db.trackerDao().getAllLogs() // Fetch newest first

            // 2. Switch back to the main UI thread to show the popup
            withContext(Dispatchers.Main) {
                if (logs.isEmpty()) {
                    showMessage("No logs yet. Go for a walk!")
                    return@withContext
                }

                // Format the timestamps nicely (e.g., "02:30:15 PM")
                val sdf = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())

                // Convert the database logs into a list of readable strings with emojis
                val logStrings = logs.map { log ->
                    val icon = if (log.eventType == "ENTER") "🟢" else "🔴"
                    "$icon ${log.eventType} - ${sdf.format(Date(log.timestamp))}"
                }.toTypedArray()

                // Show the native popup list
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Local Timesheet")
                    .setItems(logStrings, null)
                    .setPositiveButton("Close", null)
                    .setNegativeButton("Wipe Data") { _, _ ->

                        // Delete all logs on the background thread
                        CoroutineScope(Dispatchers.IO).launch {
                            db.trackerDao().clearAllLogs()

                            // Confirm deletion on the UI thread
                            withContext(Dispatchers.Main) {
                                showMessage("Database Wiped")
                            }
                        }
                    }
                    .show()
            }
        }
    }

    private fun clearMap() {
        polygonPoints.clear()
        currentPolygon?.remove()
        currentCircle?.remove()
        geofencingClient.removeGeofences(geofencePendingIntent)
        showMessage("Map & Tracking Cleared")
    }

    // Simplistic string serialization for the prototype
    private fun savePolygonToMemory(points: List<LatLng>) {
        val prefs = getSharedPreferences("FieldTrackerPrefs", Context.MODE_PRIVATE)
        val serialized = points.joinToString("|") { "${it.latitude},${it.longitude}" }
        prefs.edit().putString("ACTIVE_POLYGON", serialized).apply()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (::mMap.isInitialized) mMap.isMyLocationEnabled = true
        }
    }

    private fun showMessage(text: String) {
        com.google.android.material.snackbar.Snackbar.make(
            findViewById(android.R.id.content),
            text,
            com.google.android.material.snackbar.Snackbar.LENGTH_LONG
        ).show()
    }
}