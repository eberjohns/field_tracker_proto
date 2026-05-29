package com.eberjohns.fieldtracker

import com.google.android.gms.maps.model.LatLng
import kotlin.math.*

object GeoUtils {

    /**
     * Industry-standard Ray-Casting algorithm.
     * Includes an explicit boundary-check for workers standing exactly on the wall.
     */
    fun isPointInPolygon(point: LatLng, polygon: List<LatLng>): Boolean {
        var intersect = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]

            // 1. Boundary Check: Are they standing exactly on this wall?
            if (isOnSegment(point, pi, pj)) {
                return true
            }

            // 2. Standard Ray-Cast: Are they inside the polygon?
            if (((pi.latitude > point.latitude) != (pj.latitude > point.latitude)) &&
                (point.longitude < (pj.longitude - pi.longitude) * (point.latitude - pi.latitude) / (pj.latitude - pi.latitude) + pi.longitude)) {
                intersect = !intersect
            }
            j = i
        }
        return intersect
    }

    /**
     * Checks if point 'p' lies exactly on the line segment between 'a' and 'b'.
     */
    private fun isOnSegment(p: LatLng, a: LatLng, b: LatLng): Boolean {
        // First check if the point is within the bounding box of the line segment
        if (p.latitude < min(a.latitude, b.latitude) || p.latitude > max(a.latitude, b.latitude) ||
            p.longitude < min(a.longitude, b.longitude) || p.longitude > max(a.longitude, b.longitude)) {
            return false
        }
        // Then check if it is collinear using the cross-product
        val crossProduct = (p.latitude - a.latitude) * (b.longitude - a.longitude) - (p.longitude - a.longitude) * (b.latitude - a.latitude)

        // Use a tiny tolerance (1e-9) to account for floating-point math inaccuracies
        return abs(crossProduct) < 1e-9
    }

    /**
     * Calculates the bounding circle without relying on the Android OS.
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
        for (p in polygon) {
            val dist = haversineDistance(center, p)
            if (dist > maxRadius) maxRadius = dist
        }

        val finalRadius = max(maxRadius, 150f)
        return Pair(center, finalRadius)
    }

    /**
     * Pure math replacement for Android's Location.distanceBetween.
     * Calculates distance between two points on the Earth in meters.
     */
    private fun haversineDistance(p1: LatLng, p2: LatLng): Float {
        val r = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(p1.latitude)) * cos(Math.toRadians(p2.latitude)) *
                sin(dLon / 2) * sin(dLon / 2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).toFloat()
    }
}