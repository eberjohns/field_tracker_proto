package com.eberjohns.fieldtracker

import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    // Create a simple, predictable square worksite for testing
    // Imagine a box around the center of a map (0,0)
    private val testWorksite = listOf(
        LatLng(10.0, -10.0), // Top Left
        LatLng(10.0, 10.0),  // Top Right
        LatLng(-10.0, 10.0), // Bottom Right
        LatLng(-10.0, -10.0) // Bottom Left
    )

    @Test
    fun `worker exactly in the middle is INSIDE`() {
        val workerLocation = LatLng(0.0, 0.0)

        val isInside = GeoUtils.isPointInPolygon(workerLocation, testWorksite)

        assertTrue("Worker at 0,0 should be inside the worksite", isInside)
    }

    @Test
    fun `worker way down the street is OUTSIDE`() {
        val workerLocation = LatLng(50.0, 50.0)

        val isInside = GeoUtils.isPointInPolygon(workerLocation, testWorksite)

        assertFalse("Worker at 50,50 should be outside", isInside)
    }

    @Test
    fun `worker exactly on the boundary wall is INSIDE`() {
        // This tests an edge case: What if they are standing exactly on the line?
        val workerLocation = LatLng(10.0, 0.0)

        val isInside = GeoUtils.isPointInPolygon(workerLocation, testWorksite)

        assertTrue("Worker on the wall should still be inside", isInside)
    }

    @Test
    fun `bounding circle enforces 150m minimum`() {
        // Create a tiny 5-meter polygon
        val tinyWorksite = listOf(
            LatLng(0.0001, 0.0001),
            LatLng(0.0001, -0.0001),
            LatLng(-0.0001, -0.0001),
            LatLng(-0.0001, 0.0001)
        )

        // Calculate the geofence circle
        val (_, radius) = GeoUtils.getBoundingCircle(tinyWorksite)

        // Assert that your code successfully forced it to be at least 150f
        assertEquals("Radius should default to 150m for tiny polygons", 150f, radius)
    }
}