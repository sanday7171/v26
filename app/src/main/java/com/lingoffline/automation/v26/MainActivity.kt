package com.lingoffline.automation.v26

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var autoButton: Button

    private val projectionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (
                result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {
                val serviceIntent =
                    Intent(
                        this,
                        ScreenCaptureService::class.java
                    ).apply {
                        action = ScreenCaptureService.ACTION_START
                        putExtra(
                            ScreenCaptureService.EXTRA_RESULT_CODE,
                            result.resultCode
                        )
                        putExtra(
                            ScreenCaptureService.EXTRA_RESULT_DATA,
                            result.data
                        )
                    }

                ContextCompat.startForegroundService(
                    this,
                    serviceIntent
                )

                status.text =
                    "Status: screen capture dimulai"

                Log.d(
                    "LingAutoCapture",
                    "USER_GRANTED_CAPTURE"
                )
            } else {
                status.text =
                    "Status: screen capture dibatalkan"

                Log.w(
                    "LingAutoCapture",
                    "USER_CANCELLED_CAPTURE"
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        autoButton = findViewById(R.id.btnEnableAuto)

        AutomationState.autoExecutionEnabled = false
        syncAutoButton()

        findViewById<Button>(
            R.id.btnAccessibility
        ).setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            )
        }

        findViewById<Button>(
            R.id.btnCapture
        ).setOnClickListener {
            val mgr =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            val captureIntent =
                if (android.os.Build.VERSION.SDK_INT >= 34) {
                    mgr.createScreenCaptureIntent(
                        MediaProjectionConfig.createConfigForDefaultDisplay()
                    )
                } else {
                    mgr.createScreenCaptureIntent()
                }

            projectionLauncher.launch(captureIntent)
        }

        autoButton.setOnClickListener {
            AutomationState.autoExecutionEnabled =
                !AutomationState.autoExecutionEnabled

            syncAutoButton()

            status.text =
                if (AutomationState.autoExecutionEnabled) {
                    "Status: auto execution aktif"
                } else {
                    "Status: auto execution mati"
                }

            Log.d(
                "LingAutoDetector",
                "AUTO_EXECUTION=${AutomationState.autoExecutionEnabled}"
            )
        }

        findViewById<Button>(
            R.id.btnTestGesture
        ).setOnClickListener {
            val service =
                GestureAccessibilityService.instance

            val ok =
                service?.tapNormalized(
                    0.5f,
                    0.5f
                ) ?: false

            status.text =
                if (ok) {
                    "Status: test tap diterima Android"
                } else {
                    "Status: accessibility belum aktif / gesture ditolak"
                }
        }

        findViewById<Button>(
            R.id.btnStop
        ).setOnClickListener {
            startService(
                Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }
            )

            AutomationState.autoExecutionEnabled = false
            syncAutoButton()

            status.text = "Status: stop"
        }
    }

    override fun onResume() {
        super.onResume()

        status.text =
            "Status: ${AutomationState.statusText}\n" +
            "Debug: ${AutomationState.lastDetectorSummary}"

        syncAutoButton()
    }

    private fun syncAutoButton() {
        autoButton.text =
            if (AutomationState.autoExecutionEnabled) {
                "3. AUTO EXECUTION: ON"
            } else {
                "3. AUTO EXECUTION: OFF"
            }
    }
}
