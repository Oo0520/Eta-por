package io.github.mangi.eta.agent.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock

/** 按需读取系统已有的最近位置，不持续监听，也不主动唤醒 GPS。 */
internal object DeviceLocationProvider {
    enum class AccessState {
        DENIED,
        FOREGROUND_ONLY,
        DISABLED,
        AVAILABLE,
    }

    sealed interface Result {
        data class Available(
            val latitude: Double,
            val longitude: Double,
            val accuracyMeters: Float?,
            val ageMillis: Long,
        ) : Result

        data class Unavailable(val status: String) : Result
    }

    fun accessState(context: Context): AccessState {
        val foregroundGranted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!foregroundGranted) return AccessState.DENIED
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return AccessState.DISABLED
        if (!manager.isLocationEnabled) return AccessState.DISABLED
        // 后台定位只有在持续订阅或后台触发读取时才需要；读取系统已有的最近位置
        // 属于前台读一次缓存，拿到「使用时允许」就够用，不能再当成拒绝。
        val backgroundGranted = context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (backgroundGranted) AccessState.AVAILABLE else AccessState.FOREGROUND_ONLY
    }

    @SuppressLint("MissingPermission")
    fun latest(context: Context): Result {
        return when (accessState(context)) {
            AccessState.DENIED -> Result.Unavailable("permission_required")
            AccessState.DISABLED -> Result.Unavailable("location_disabled")
            // FOREGROUND_ONLY 也能读最近位置，只是不能后台持续读取。
            AccessState.FOREGROUND_ONLY, AccessState.AVAILABLE -> {
                val manager = context.getSystemService(LocationManager::class.java)
                    ?: return Result.Unavailable("unavailable")
                val providers = runCatching { manager.getProviders(true) }
                    .getOrElse { return Result.Unavailable("unavailable") }
                val location = providers
                    .asSequence()
                    .mapNotNull { provider ->
                        runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
                    }
                    .maxByOrNull { it.elapsedRealtimeNanos }
                    ?: return Result.Unavailable("unavailable")
                val ageMillis = if (location.elapsedRealtimeNanos > 0L) {
                    ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
                        .coerceAtLeast(0L)
                } else {
                    (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
                }
                Result.Available(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() },
                    ageMillis = ageMillis,
                )
            }
        }
    }
}
