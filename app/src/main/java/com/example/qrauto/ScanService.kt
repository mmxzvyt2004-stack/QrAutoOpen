package com.example.qrauto

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

class ScanService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"
        private const val CHANNEL_ID = "qr_scan_channel"
        private const val NOTIF_ID = 1001

        private const val TARGET_LONG_SIDE = 1280
        private const val MIN_FRAME_INTERVAL_MS = 80L
        private const val REPEAT_COOLDOWN_MS = 8000L
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var stopButton: Button? = null
    private var windowManager: WindowManager? = null

    private lateinit var scanner: BarcodeScanner
    private var lastFrameTime = 0L
    private var lastScannedText: String? = null
    private var lastScannedTime = 0L
    @Volatile private var isAnalyzing = false

    override fun onCreate() {
        super.onCreate()
        scanner = BarcodeScanning.getClient()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != 0 && resultData != null) {
                    startForegroundServiceWithNotification()
                    setupMediaProjection(resultCode, resultData)
                    showFloatingStopButton()
                } else {
                    stopSelf()
                }
            }
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val stopIntent = Intent(this, ScanService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QR Auto Scanner Running")
            .setContentText("Scanning screen for QR codes...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun setupMediaProjection(resultCode: Int, resultData: Intent) {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        var width = metrics.widthPixels
        var height = metrics.heightPixels
        val maxDim = maxOf(width, height)
        if (maxDim > TARGET_LONG_SIDE) {
            val scale = TARGET_LONG_SIDE.toFloat() / maxDim
            width = (width * scale).toInt()
            height = (height * scale).toInt()
        }

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "QRScanDisplay",
            width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameTime < MIN_FRAME_INTERVAL_MS || isAnalyzing) {
                reader.acquireNextImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrameTime = now
            val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
            processImage(image)
        }, Handler(Looper.getMainLooper()))
    }

    private fun processImage(image: Image) {
        isAnalyzing = true
        val inputImage = try {
            InputImage.fromMediaImage(image, 0)
        } catch (e: Exception) {
            image.close()
            isAnalyzing = false
            return
        }

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                image.close()
                isAnalyzing = false
                handleBarcodes(barcodes)
            }
            .addOnFailureListener {
                image.close()
                isAnalyzing = false
            }
    }

    private fun handleBarcodes(barcodes: List<Barcode>) {
        val now = SystemClock.elapsedRealtime()
        for (barcode in barcodes) {
            val url = barcode.rawValue ?: continue
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue

            if (url == lastScannedText && (now - lastScannedTime) < REPEAT_COOLDOWN_MS) {
                continue
            }

            lastScannedText = url
            lastScannedTime = now

            openUrl(url)
            break
        }
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun showFloatingStopButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        stopButton = Button(this).apply {
            text = "■"
            setOnClickListener { stopSelf() }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 20
            y = 100
        }

        try {
            windowManager?.addView(stopButton, params)
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "QR Scan Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopButton?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
