package com.eberjohns.fieldtracker // Update this to match your exact package name

import android.location.Location
import com.google.android.gms.maps.model.LatLng
import kotlin.math.max

object GeoUtils {

    /**
     * Ray-Casting algorithm to check if a high-accuracy GPS point is inside the drawn polygon.
     */
    fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        var intersectCount = 0
        for (i in 0 until polygon.size - 1) {
            if (rayCastIntersect(point, polygon[i], polygon[i + 1])) intersectCount++
        }
        // Connect the last point back to the first
        if (polygon.isNotEmpty() && rayCastIntersect(point, polygon.last(), polygon.first())) {
            intersectCount++
        }
        return (intersectCount % 2) == 1
    }

    private fun rayCastIntersect(point: LatLng, vertA: LatLng, vertB: LatLng): Boolean {
        val aY = vertA.latitude
        val bY = vertB.latitude
        val aX = vertA.longitude
        val bX = vertB.longitude
        val pY = point.latitude
        val pX = point.longitude

        if ((aY > pY && bY > pY) || (aY < pY && bY < pY) || (aX < pX && bX < pX)) {
            return false // Cannot intersect
        }
        val m = (aY - bY) / (aX - bX)
        val bee = (-aX) * m + aY
        val x = (pY - bee) / m
        return x > pX
    }

    /**
     * Calculates the invisible OS geofence circle.
     * Enforces a 150m minimum to prevent Android from ignoring small geofences.
     */
    fun getBoundingCircle(polygon: List<LatLng>): Pair<LatLng, Float> {
        if (polygon.isEmpty()) return Pair(LatLng(0.0, 0.0), 150f)

        var sumLat = 0.0
        var sumLon = 0.0
        for (p in polygon) {
            sumLat += p.latitude
            sumLon += p.longitude
        }
        val center = LatLng(sumLat / polygon.size, sumLon / polygon.size)

        var maxRadius = 0f
        val results = FloatArray(1)
        for (p in polygon) {
            Location.distanceBetween(center.latitude, center.longitude, p.latitude, p.longitude, results)
            if (results[0] > maxRadius) maxRadius = results[0]
        }

        val finalRadius = max(maxRadius, 150f)
        return Pair(center, finalRadius)
    }
}