package com.imam.app5

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import kotlin.math.abs
import kotlin.math.max

data class CameraMetrics(
    val score: Double = 0.0,
    val texture: Double = 0.0,
    val edgeDensity: Double = 0.0,
    val looming: Double = 0.0,
    val meanLuma: Double = 0.0
)

class CameraObstacleAnalyzer(
    private val onMetricsReady: (CameraMetrics) -> Unit
) : ImageAnalysis.Analyzer {

    private var previousTexture = 0.0
    private var previousEdgeDensity = 0.0
    private var previousMeanLuma = 0.0

    override fun analyze(image: ImageProxy) {
        try {
            val yPlane = image.planes.firstOrNull() ?: return
            val buffer = yPlane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width = image.width
            val height = image.height
            val rowStride = yPlane.rowStride

            val xStart = (width * 0.28).toInt()
            val xEnd = (width * 0.72).toInt().coerceAtMost(width - 2)
            val yStart = (height * 0.25).toInt()
            val yEnd = (height * 0.75).toInt().coerceAtMost(height - 2)

            val step = 8
            var samples = 0
            var edgeCount = 0
            var gradientTotal = 0.0
            var lumaTotal = 0.0

            fun lumaAt(x: Int, y: Int): Int {
                val index = y * rowStride + x
                return bytes[index].toInt() and 0xFF
            }

            var y = yStart
            while (y < yEnd) {
                var x = xStart
                while (x < xEnd) {
                    val current = lumaAt(x, y)
                    val right = lumaAt((x + step).coerceAtMost(xEnd), y)
                    val down = lumaAt(x, (y + step).coerceAtMost(yEnd))
                    val gradient = abs(current - right) + abs(current - down)

                    lumaTotal += current.toDouble()
                    gradientTotal += gradient.toDouble()
                    if (gradient > 42) {
                        edgeCount += 1
                    }
                    samples += 1
                    x += step
                }
                y += step
            }

            if (samples == 0) {
                return
            }

            val edgeDensity = edgeCount.toDouble() / samples.toDouble()
            val texture = (gradientTotal / samples.toDouble() / 180.0).coerceIn(0.0, 1.0)
            val meanLuma = lumaTotal / samples.toDouble()

            val positiveTextureRise = max(0.0, texture - previousTexture)
            val positiveEdgeRise = max(0.0, edgeDensity - previousEdgeDensity) * 2.5
            val lumaShift = (abs(meanLuma - previousMeanLuma) / 85.0).coerceIn(0.0, 1.0)

            val looming = (
                (0.55 * positiveTextureRise) +
                (0.35 * positiveEdgeRise.coerceIn(0.0, 1.0)) +
                (0.10 * lumaShift)
            ).coerceIn(0.0, 1.0)

            val darkOcclusion = ((65.0 - meanLuma) / 65.0).coerceIn(0.0, 1.0)
            val score = (
                (0.55 * texture) +
                (0.25 * (edgeDensity / 0.30).coerceIn(0.0, 1.0)) +
                (0.15 * looming) +
                (0.05 * darkOcclusion)
            ).coerceIn(0.0, 1.0)

            previousTexture = texture
            previousEdgeDensity = edgeDensity
            previousMeanLuma = meanLuma

            onMetricsReady(
                CameraMetrics(
                    score = score,
                    texture = texture,
                    edgeDensity = edgeDensity,
                    looming = looming,
                    meanLuma = meanLuma
                )
            )
        } finally {
            image.close()
        }
    }
}
