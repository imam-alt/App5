package com.imam.app5

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var signalView: TextView
    private lateinit var permissionButton: Button
    private lateinit var startButton: Button

    private val fusionEngine = SignalFusionEngine()
    private lateinit var signalScanner: SignalScanner
    private lateinit var cameraExecutor: ExecutorService

    @Volatile
    private var latestRadio = RadioSnapshot()

    @Volatile
    private var latestCamera = CameraMetrics()

    private var cameraStarted = false
    private val uiHandler = Handler(Looper.getMainLooper())

    private val renderLoop = object : Runnable {
        override fun run() {
            render()
            uiHandler.postDelayed(this, 700L)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (hasAllPermissions()) {
            statusView.text = getString(R.string.permissions_ready)
        } else {
            statusView.text = getString(R.string.permissions_missing)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        statusView = findViewById(R.id.statusView)
        detailView = findViewById(R.id.detailView)
        signalView = findViewById(R.id.signalView)
        permissionButton = findViewById(R.id.permissionButton)
        startButton = findViewById(R.id.startButton)

        cameraExecutor = Executors.newSingleThreadExecutor()
        signalScanner = SignalScanner(this) { snapshot ->
            latestRadio = snapshot
        }

        permissionButton.setOnClickListener {
            permissionLauncher.launch(requiredPermissions())
        }

        startButton.setOnClickListener {
            if (!hasAllPermissions()) {
                permissionLauncher.launch(requiredPermissions())
                return@setOnClickListener
            }
            startDetection()
        }

        if (hasAllPermissions()) {
            statusView.text = getString(R.string.permissions_ready)
        } else {
            statusView.text = getString(R.string.permissions_missing)
        }
    }

    private fun startDetection() {
        if (!cameraStarted) {
            startCamera()
        }
        signalScanner.start()
        uiHandler.removeCallbacks(renderLoop)
        uiHandler.post(renderLoop)
        statusView.text = getString(R.string.status_running)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        cameraExecutor,
                        CameraObstacleAnalyzer { metrics ->
                            latestCamera = metrics
                        }
                    )
                }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            cameraStarted = true
        }, ContextCompat.getMainExecutor(this))
    }

    private fun render() {
        val fusion = fusionEngine.update(latestRadio, latestCamera)

        statusView.text = getString(
            R.string.status_template,
            fusion.levelLabel
        )
        detailView.text = getString(
            R.string.detail_template,
            (fusion.fusedRisk * 100.0).toInt(),
            latestCamera.score.format(),
            latestCamera.looming.format(),
            fusion.reason
        )
        signalView.text = getString(
            R.string.signal_template,
            latestRadio.strongestWifiRssi,
            latestRadio.wifiCount,
            latestRadio.strongestBleRssi,
            latestRadio.bleCount,
            latestRadio.strongestCellDbm,
            latestRadio.cellInstability.format()
        )

        if (fusion.shouldVibrate) {
            vibrateWarning()
        }
    }

    private fun vibrateWarning() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        if (!vibrator.hasVibrator()) {
            return
        }

        val effect = VibrationEffect.createWaveform(longArrayOf(0, 100, 70, 140), -1)
        vibrator.vibrate(effect)
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        }

        return permissions.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions().all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        signalScanner.stop()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    private fun Double.format(): String = String.format(Locale.US, "%.2f", this)
}
