package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DiagnosticsSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 24 * 60,
    val keepCount: Int = 30,
    val notifyOnlyAbnormal: Boolean = true,
    val lastAt: Long = 0L,
    val lastFile: String = "",
    val lastLevel: String = "",
    val lastSummary: String = "",
)
