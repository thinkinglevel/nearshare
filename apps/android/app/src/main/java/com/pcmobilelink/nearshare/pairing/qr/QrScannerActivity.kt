package com.pcmobilelink.nearshare.pairing.qr

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import com.pcmobilelink.nearshare.ui.AppTypeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class QrScannerActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var topPanel: LinearLayout
    private lateinit var bottomPanel: LinearLayout
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainExecutor = MainThreadExecutor()
    private val hasReturnedResult = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            statusText.text = "Camera permission is needed to scan the Windows pairing QR code."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        buildUi()

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun buildUi() {
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(Color.BLACK)
        }

        topPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(18))
            background = roundedDrawable(COLOR_PANEL, bottomLeft = dp(28), bottomRight = dp(28))
        }

        topPanel.addView(
            TextView(this).apply {
                text = "Scan QR code"
                textSize = 24f
                typeface = AppTypeface.bold
                includeFontPadding = false
                setTextColor(Color.WHITE)
            },
        )
        topPanel.addView(
            TextView(this).apply {
                text = "Place the Windows NearShare QR code inside the frame."
                textSize = 14f
                setLineSpacing(dp(2).toFloat(), 1.0f)
                typeface = AppTypeface.regular
                setTextColor(COLOR_PANEL_TEXT)
                setPadding(0, dp(10), 0, 0)
            },
        )

        val scanFrame = View(this).apply {
            background = scanFrameDrawable()
        }

        bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(18), dp(24), dp(24))
            background = roundedDrawable(COLOR_PANEL, topLeft = dp(28), topRight = dp(28))
        }

        statusText = TextView(this).apply {
            text = "Starting camera..."
            textSize = 14f
            gravity = Gravity.CENTER
            setLineSpacing(dp(2).toFloat(), 1.0f)
            typeface = AppTypeface.regular
            setTextColor(COLOR_PANEL_TEXT)
            setPadding(0, 0, 0, dp(16))
        }

        val cancelButton = Button(this).apply {
            text = "Cancel"
            setAllCaps(false)
            textSize = 15f
            typeface = AppTypeface.bold
            setTextColor(Color.WHITE)
            minHeight = dp(52)
            background = roundedStrokeDrawable(
                fillColor = COLOR_CANCEL_FILL,
                strokeColor = COLOR_CANCEL_STROKE,
                radius = dp(16),
                strokeWidth = dp(1),
            )
            elevation = 0f
            stateListAnimator = null
            setOnClickListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }

        bottomPanel.addView(
            statusText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        bottomPanel.addView(
            cancelButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            ),
        )

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                previewView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                View(this@QrScannerActivity).apply { setBackgroundColor(COLOR_SCRIM) },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                scanFrame,
                FrameLayout.LayoutParams(dp(282), dp(282), Gravity.CENTER),
            )
            addView(
                topPanel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP,
                ),
            )
            addView(
                bottomPanel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM,
                ),
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            topPanel.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(18))
            bottomPanel.setPadding(dp(24) + bars.left, dp(18), dp(24) + bars.right, dp(24) + bars.bottom)
            insets
        }

        setContentView(root)
    }

    private fun startCamera() {
        statusText.text = "Starting camera..."
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().also { preview ->
                        preview.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { imageAnalysis ->
                            imageAnalysis.setAnalyzer(
                                cameraExecutor,
                                QrImageAnalyzer { decodedText -> finishWithQrCode(decodedText) },
                            )
                        }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    statusText.text = "Scanning for a NearShare pairing QR code..."
                } catch (exception: Exception) {
                    statusText.text = "Could not start camera: ${exception.message}"
                }
            },
            mainExecutor,
        )
    }

    private fun finishWithQrCode(decodedText: String) {
        if (!hasReturnedResult.compareAndSet(false, true)) {
            return
        }

        runOnUiThread {
            val resultIntent = Intent().putExtra(EXTRA_SCANNED_TEXT, decodedText)
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        }
    }

    private fun scanFrameDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(3), Color.WHITE)
        }
    }

    private fun roundedDrawable(
        fillColor: Int,
        topLeft: Int = 0,
        topRight: Int = 0,
        bottomRight: Int = 0,
        bottomLeft: Int = 0,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
            cornerRadii = floatArrayOf(
                topLeft.toFloat(), topLeft.toFloat(),
                topRight.toFloat(), topRight.toFloat(),
                bottomRight.toFloat(), bottomRight.toFloat(),
                bottomLeft.toFloat(), bottomLeft.toFloat(),
            )
        }
    }

    private fun roundedStrokeDrawable(
        fillColor: Int,
        strokeColor: Int,
        radius: Int,
        strokeWidth: Int,
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
            setStroke(strokeWidth, strokeColor)
        }
    }





    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_SCANNED_TEXT = "com.pcmobilelink.nearshare.extra.SCANNED_TEXT"

        private const val COLOR_PANEL = 0xCC0F172A.toInt()
        private const val COLOR_PANEL_TEXT = 0xFFE2E8F0.toInt()
        private const val COLOR_SCRIM = 0x26000000
        private const val COLOR_CANCEL_FILL = 0x26000000
        private const val COLOR_CANCEL_STROKE = 0x66FFFFFF
    }
}
