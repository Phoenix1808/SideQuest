package com.example.sidequest

import android.location.Location

// Decides whether the player is actually at a quest's place before the camera opens.
object Geofence {

    sealed interface Result {
        data object Arrived : Result
        data class TooFar(val meters: Int) : Result
        data object NoFix : Result
        data object Imprecise : Result
    }

    // OpenStreetMap gives one centre point per place, but a park can be 600 m across,
    // so big areas get a wider circle than a fountain or a viewpoint.
    private val BIG_PLACES = setOf(
        "park", "garden", "nature reserve", "forest", "wood", "water", "meadow",
        "recreation ground", "village green", "grassland", "beach", "playground", "dog park"
    )

    private const val BIG_RADIUS_M = 400f
    private const val SMALL_RADIUS_M = 150f
    private const val MAX_ACCURACY_BONUS_M = 100f
    private const val IMPRECISE_ACCURACY_M = 500f

    fun check(here: Location?, place: Place): Result {
        if (here == null) return Result.NoFix
        if (here.hasAccuracy() && here.accuracy > IMPRECISE_ACCURACY_M) return Result.Imprecise

        val meters = FloatArray(1)
        Location.distanceBetween(here.latitude, here.longitude, place.lat, place.lon, meters)

        val radius = if (place.kind in BIG_PLACES) BIG_RADIUS_M else SMALL_RADIUS_M
        val allowed = radius + here.accuracy.coerceAtMost(MAX_ACCURACY_BONUS_M)

        return if (meters[0] <= allowed) Result.Arrived else Result.TooFar(meters[0].toInt())
    }
}
