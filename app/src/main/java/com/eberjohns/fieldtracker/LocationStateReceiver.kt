package com.eberjohns.fieldtracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.util.Log
import android.widget.Toast

class LocationStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.w("AntiTamper", "WARNING: GPS was turned OFF by the user!")

                // For now, we fire a massive toast.
                // Later, we will query the Room DB here to see if they are currently "Clocked In"
                // and if so, trigger the full-screen Alarm Activity.
                Toast.makeText(context, "⚠️ GPS DISABLED! Turn location back on to track hours.", Toast.LENGTH_LONG).show()
            } else {
                Log.d("AntiTamper", "GPS is ON. System secure.")
            }
        }
    }
}