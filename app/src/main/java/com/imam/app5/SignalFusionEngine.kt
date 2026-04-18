package com.imam.app5

import android.os.SystemClock

data class FusionReading(
    val fusedRisk: Double,
    val levelLabel: String,
    val reason: String,
    val shouldVibrate: Boolean
)

class SignalFusionEngine {
    private var lastVibrationAt = 0L

    fun update(
        radio: RadioSnapshot,
        camera: CameraMetrics
    ): FusionReading {
        val radioScore = (
            (0.45 * radio.wifiCloseness) +
            (0.45 * radio.bleCloseness) +
            (0.10 * radio.cellInstability)
        ).coerceIn(0.0, 1.0)

        val fusedRisk = if (camera.score >= 0.55) {
            ((radioScore * 0.70) + (camera.score * 0.30) + 0.15).coerceIn(0.0, 1.0)
        } else {
            ((radioScore * 0.85) + (camera.score * 0.15)).coerceIn(0.0, 1.0)
        }

        val levelLabel = when {
            fusedRisk >= 0.80 -> "Bahaya tinggi"
            fusedRisk >= 0.60 -> "Waspada"
            fusedRisk >= 0.40 -> "Sedang"
            else -> "Aman relatif"
        }

        val strongest = when {
            radio.bleCloseness >= radio.wifiCloseness && radio.bleCloseness >= camera.score ->
                "BLE sangat kuat"
            radio.wifiCloseness >= camera.score ->
                "Wi‑Fi sekitar menguat"
            else ->
                "kamera melihat hambatan di depan"
        }

        val reason = buildString {
            append(strongest)
            if (camera.looming >= 0.20) {
                append(", objek depan tampak membesar")
            }
            if (radio.cellInstability >= 0.35) {
                append(", lingkungan radio berubah")
            }
        }

        val now = SystemClock.elapsedRealtime()
        val shouldVibrate = (
            fusedRisk >= 0.72 ||
                (camera.score >= 0.72 && maxOf(radio.wifiCloseness, radio.bleCloseness) >= 0.55)
            ) && now - lastVibrationAt >= 1200L

        if (shouldVibrate) {
            lastVibrationAt = now
        }

        return FusionReading(
            fusedRisk = fusedRisk,
            levelLabel = levelLabel,
            reason = reason,
            shouldVibrate = shouldVibrate
        )
    }
}
