package com.eberjohns.fieldtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent

class GeofenceBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)

        if (geofencingEvent == null || geofencingEvent.hasError()) {
            Log.e("GeoReceiver", "Error receiving geofence event")
            return
        }

        val transitionType = geofencingEvent.geofenceTransition
        if (transitionType == Geofence.GEOFENCE_TRANSITION_ENTER ||
            transitionType == Geofence.GEOFENCE_TRANSITION_EXIT) {

            Log.d("GeoReceiver", "OS Geofence Triggered! Waking Engine...")

            // Start the Foreground Engine
            val serviceIntent = Intent(context, TimesheetService::class.java).apply {
                putExtra("TRANSITION_TYPE", transitionType)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}