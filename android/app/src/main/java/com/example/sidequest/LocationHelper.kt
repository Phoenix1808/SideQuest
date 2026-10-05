package com.example.sidequest

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

// Plain Android LocationManager - no Google Play Services needed.
object LocationHelper {

    fun isLocationOn(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    // For building quests: "roughly where am I?" A fix from the last few minutes is
    // plenty, and the network provider (Wi-Fi / cell towers) answers fast, even indoors.
    suspend fun current(context: Context): Location? = locate(
        context,
        maxAgeMs = 10 * 60 * 1000L,
        providers = listOf(LocationManager.NETWORK_PROVIDER to 15_000L, LocationManager.GPS_PROVIDER to 15_000L),
        staleIsOk = true
    )

    // For proving you're at a place: "where am I right now, precisely?" Old fixes could be
    // from home, so they must be very recent. GPS first (5-20 m outdoors), network as backup.
    suspend fun fresh(context: Context): Location? = locate(
        context,
        maxAgeMs = 30 * 1000L,
        providers = listOf(LocationManager.GPS_PROVIDER to 15_000L, LocationManager.NETWORK_PROVIDER to 8_000L),
        staleIsOk = false
    )

    // Callers must already hold a location permission.
    @SuppressLint("MissingPermission")
    private suspend fun locate(
        context: Context,
        maxAgeMs: Long,
        providers: List<Pair<String, Long>>,
        staleIsOk: Boolean
    ): Location? {
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val enabled = providers.filter { (provider, _) -> manager.isProviderEnabled(provider) }

        // A cached fix that's recent enough saves waiting for satellites
        val lastKnown = enabled.mapNotNull { (provider, _) -> manager.getLastKnownLocation(provider) }
            .maxByOrNull { it.time }
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < maxAgeMs) {
            return lastKnown
        }

        // Ask each provider in order for a brand-new fix, giving each a time limit
        for ((provider, timeoutMs) in enabled) {
            val fix = withTimeoutOrNull(timeoutMs) { requestOnce(context, manager, provider) }
            if (fix != null) return fix
        }
        // Quests can be built from an older fix; proof never can
        return if (staleIsOk) lastKnown else null
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(context: Context, manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            LocationManagerCompat.getCurrentLocation(
                manager, provider, signal, ContextCompat.getMainExecutor(context)
            ) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }
}
