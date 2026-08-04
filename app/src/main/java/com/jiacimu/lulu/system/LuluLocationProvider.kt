package com.jiacimu.lulu.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

object LuluLocationProvider {
    suspend fun freshLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val fused = runCatching {
            withTimeoutOrNull(15_000L) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val cancellation = CancellationTokenSource()
                    continuation.invokeOnCancellation { cancellation.cancel() }
                    LocationServices.getFusedLocationProviderClient(context)
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellation.token)
                        .addOnSuccessListener { location ->
                            if (continuation.isActive) continuation.resume(location)
                        }
                        .addOnFailureListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                        .addOnCanceledListener {
                            if (continuation.isActive) continuation.resume(null)
                        }
                }
            }
        }.getOrNull()
        return fused ?: bestSystemLocation(context)
    }

    fun bestSystemLocation(context: Context): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val manager = context.getSystemService(LocationManager::class.java)
        return manager.getProviders(true)
            .mapNotNull { provider -> runCatching { manager.getLastKnownLocation(provider) }.getOrNull() }
            .maxWithOrNull(compareBy<Location> { it.time }.thenByDescending { -it.accuracy })
    }

    fun label(location: Location): String =
        "位置：%.6f, %.6f · 精度约 %.0f 米 · %s".format(
            location.latitude,
            location.longitude,
            location.accuracy,
            location.provider,
        )
}
