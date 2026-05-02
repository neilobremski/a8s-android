package com.a8s.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `/location` — one-shot best-effort location fix.
 *
 * Tries Google Play Services' FusedLocationProviderClient via reflection
 * (so we don't pull in the dep when it isn't installed); falls back to
 * `LocationManager` GPS + NETWORK providers. Plain-text reply formatted
 * by `CmdHelpers.renderLocation`.
 */
object CmdLocation {

    private const val ONE_SHOT_TIMEOUT_S = 30L

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        if (!hasLocationPerm(service)) {
            service.replyToSender(
                config, cmd.sender,
                "Location failed: ACCESS_FINE_LOCATION not granted (open the app and tap Grant All)",
            )
            return
        }
        val context: Context = service
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            service.replyToSender(config, cmd.sender, "Location failed: LocationManager unavailable")
            return
        }

        val fused = tryFused(context)
        val location = fused ?: requestFromManager(lm)
        if (location == null) {
            service.replyToSender(config, cmd.sender, "Location failed: no fix within ${ONE_SHOT_TIMEOUT_S}s")
            return
        }
        val snap = CmdHelpers.LocationSnapshot(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            ageMs = (System.currentTimeMillis() - location.time).coerceAtLeast(0),
            provider = location.provider ?: "fused",
        )
        service.replyToSender(config, cmd.sender, CmdHelpers.renderLocation(snap))
    }

    private fun hasLocationPerm(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Attempt FusedLocationProviderClient via reflection so we avoid a
     * hard dep on `play-services-location`. If the class isn't present
     * in the runtime, return null and the caller falls back to
     * LocationManager.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun tryFused(context: Context): Location? {
        return try {
            val factoryCls = Class.forName("com.google.android.gms.location.LocationServices")
            val factoryMethod = factoryCls.getMethod("getFusedLocationProviderClient", Context::class.java)
            val client = factoryMethod.invoke(null, context) ?: return null
            val getLast = client.javaClass.getMethod("getLastLocation")
            val task = getLast.invoke(client) ?: return null
            val tasksCls = Class.forName("com.google.android.gms.tasks.Tasks")
            val await = tasksCls.getMethod("await", Class.forName("com.google.android.gms.tasks.Task"))
            await.invoke(null, task) as? Location
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("ReturnCount")
    private fun requestFromManager(lm: LocationManager): Location? {
        val providers = mutableListOf<String>()
        if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            providers.add(LocationManager.NETWORK_PROVIDER)
        }
        if (providers.isEmpty()) return null

        // Seed with last-known so the timeout path has something to return.
        var best: Location? = null
        for (p in providers) {
            try {
                val last = lm.getLastKnownLocation(p)
                val current = best
                if (last != null && (current == null || last.accuracy < current.accuracy)) best = last
            } catch (_: SecurityException) { /* perm gated above; race-permission still possible */ }
        }

        val latch = CountDownLatch(1)
        val mainHandler = Handler(Looper.getMainLooper())
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val current = best
                if (current == null || loc.accuracy < current.accuracy) best = loc
                if (loc.provider == LocationManager.GPS_PROVIDER) latch.countDown()
            }
            @Deprecated("unused override required by interface")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            for (p in providers) {
                try {
                    lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                } catch (_: SecurityException) { /* skip; race */ }
            }
            latch.await(ONE_SHOT_TIMEOUT_S, TimeUnit.SECONDS)
        } finally {
            mainHandler.post { try { lm.removeUpdates(listener) } catch (_: Exception) { } }
        }
        return best
    }
}
