package com.lingoffline.automation.v26

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlin.math.*

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "capture.start"
        const val ACTION_STOP = "capture.stop"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        private const val CHANNEL_ID = "capture_v26"
        private const val NOTIFICATION_ID = 1026

        private const val TAG_CAPTURE = "LingAutoCapture"
        private const val TAG_DETECTOR = "LingAutoDetector"

        private const val PROCESS_INTERVAL_MS = 40L
        private const val POST_GESTURE_SETTLE_MS = 52L
        private const val START_STABLE_FRAMES = 2
        private const val TARGET_TIMEOUT_MS = 1100L
        private const val MAX_DASHES = 4
        private const val START_RING_THRESHOLD = 0.58f
        private const val MIN_RADIUS_FACTOR = 0.155f
        private const val MAX_RADIUS_FACTOR = 0.72f
    }

    private var projection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null

    private var lastProcessMs = 0L
    private var frameCounter = 0L
    private var callbacks = 0L

    private var surfaceW = 0
    private var surfaceH = 0
    private var densityDpi = 0

    private var sequenceActive = false
    private var dashCount = 0
    private var waitingGesture = false
    private var nextDashAt = 0L
    private var targetWaitStart = 0L

    private var stableFrames = 0
    private var lastRing: List<SwordPoint> = emptyList()

    private var lastLogMs = 0L
    private var lastHero = HeroEstimate(0f, 0f, 0f, false)

    override fun onCreate() {
        super.onCreate()

        createChannel()

        workerThread =
            HandlerThread("capture-worker-v26").also {
                it.start()
            }

        worker = Handler(workerThread!!.looper)

        Log.d(
            TAG_CAPTURE,
            "SERVICE_CREATED V2.6_DIRECT_FRAME"
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> startCapture(intent)
            ACTION_STOP -> stopSelf()
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startCapture(intent: Intent) {
        if (projection != null) return

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    android.R.drawable.ic_menu_camera
                )
                .setContentTitle(
                    "Ling Offline Automation V2.6"
                )
                .setContentText(
                    "Direct-frame detector aktif"
                )
                .setOngoing(true)
                .build()
        )

        val resultCode =
            intent.getIntExtra(
                EXTRA_RESULT_CODE,
                Activity.RESULT_CANCELED
            )

        val resultData =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA,
                    Intent::class.java
                )
            } else {
                intent.getParcelableExtra(
                    EXTRA_RESULT_DATA
                )
            }

        if (resultData == null) {
            stopSelf()
            return
        }

        val mgr =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        projection =
            mgr.getMediaProjection(
                resultCode,
                resultData
            )

        projection?.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.w(
                        TAG_CAPTURE,
                        "PROJECTION_STOPPED"
                    )
                    stopSelf()
                }
            },
            worker
        )

        val dm = resources.displayMetrics

        surfaceW = max(dm.widthPixels, dm.heightPixels)
        surfaceH = min(dm.widthPixels, dm.heightPixels)
        densityDpi = resources.configuration.densityDpi

        val reader =
            ImageReader.newInstance(
                surfaceW,
                surfaceH,
                PixelFormat.RGBA_8888,
                3
            )

        reader.setOnImageAvailableListener(
            { r ->
                callbacks++
                processImage(r)
            },
            worker
        )

        imageReader = reader

        virtualDisplay =
            projection?.createVirtualDisplay(
                "LingCaptureV26",
                surfaceW,
                surfaceH,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                worker
            )

        Log.d(
            TAG_CAPTURE,
            "CAPTURE_STARTED " +
                "${surfaceW}x${surfaceH} " +
                "process=${PROCESS_INTERVAL_MS}ms"
        )
    }

    private fun processImage(reader: ImageReader) {
        val now = SystemClock.elapsedRealtime()

        if (now - lastProcessMs < PROCESS_INTERVAL_MS) {
            reader.acquireLatestImage()?.close()
            return
        }

        lastProcessMs = now

        val image =
            reader.acquireLatestImage()
                ?: return

        try {
            val plane = image.planes[0]

            val detection =
                FastFrameDetector.analyze(
                    buffer = plane.buffer,
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride
                )

            frameCounter++

            handleDetection(
                detection,
                now
            )
        } catch (t: Throwable) {
            Log.e(
                TAG_CAPTURE,
                "DIRECT_FRAME_FAILED " +
                    "${t.javaClass.simpleName}: ${t.message}",
                t
            )
        } finally {
            image.close()
        }
    }

    private fun handleDetection(
        d: FastDetection,
        now: Long
    ) {
        lastHero = d.hero

        val world =
            filterWorld(
                d,
                sequenceActive
            )

        if (now - lastLogMs > 450L) {
            lastLogMs = now

            Log.d(
                TAG_DETECTOR,
                "FRAME#$frameCounter " +
                    "step=${d.sampleStep} " +
                    "purple=${d.purpleSamples} " +
                    "raw=${d.candidates.size} " +
                    "world=${world.size} " +
                    "hero=(${d.hero.x.roundToInt()},${d.hero.y.roundToInt()}) " +
                    "heroSource=${if (d.hero.fromHealthBar) "HP" else "fallback"} " +
                    "heroConf=${pct(d.hero.confidence)} " +
                    "sequence=$sequenceActive " +
                    "dash=$dashCount"
            )
        }

        if (!sequenceActive) {
            tryStartSequence(
                d,
                world
            )
            return
        }

        if (waitingGesture) return
        if (now < nextDashAt) return

        if (dashCount >= MAX_DASHES) {
            endSequence("FOUR_DASHES")
            return
        }

        val target =
            chooseTarget(
                d,
                world
            )

        if (target == null) {
            if (targetWaitStart == 0L) {
                targetWaitStart = now
            }

            if (
                now - targetWaitStart >
                TARGET_TIMEOUT_MS
            ) {
                endSequence(
                    "NO_TARGET_TIMEOUT"
                )
            }

            return
        }

        targetWaitStart = 0L
        sendDash(d, target)
    }

    private fun tryStartSequence(
        d: FastDetection,
        world: List<SwordPoint>
    ) {
        if (world.size < 4) {
            stableFrames = 0
            lastRing = emptyList()
            return
        }

        val ring = bestRing(d, world)
        val score = ringScore(d.hero, ring)

        if (
            ring.size != 4 ||
            score < START_RING_THRESHOLD
        ) {
            stableFrames = 0
            lastRing = emptyList()
            return
        }

        stableFrames =
            if (
                stableRing(
                    lastRing,
                    ring,
                    d.width,
                    d.height
                )
            ) {
                stableFrames + 1
            } else {
                1
            }

        lastRing = ring

        Log.d(
            TAG_DETECTOR,
            "RING " +
                "$stableFrames/$START_STABLE_FRAMES " +
                "score=${pct(score)}"
        )

        if (
            stableFrames <
            START_STABLE_FRAMES
        ) return

        if (
            !AutomationState
                .autoExecutionEnabled
        ) return

        sequenceActive = true
        dashCount = 0
        waitingGesture = false
        nextDashAt = 0L
        targetWaitStart = 0L

        stableFrames = 0
        lastRing = emptyList()

        Log.d(
            TAG_DETECTOR,
            "SEQUENCE_START V2.6"
        )

        val target =
            chooseTarget(
                d,
                ring
            )

        if (target != null) {
            sendDash(d, target)
        }
    }

    private fun filterWorld(
        d: FastDetection,
        sequence: Boolean
    ): List<SwordPoint> {
        val hero = d.hero

        val minDim =
            min(
                d.width,
                d.height
            ).toFloat()

        val minRadius =
            minDim *
            MIN_RADIUS_FACTOR

        val maxRadius =
            minDim *
            MAX_RADIUS_FACTOR

        val valid =
            d.candidates.filter { p ->
                val dist =
                    hypot(
                        p.x - hero.x,
                        p.y - hero.y
                    )

                if (
                    dist < minRadius ||
                    dist > maxRadius
                ) return@filter false

                if (
                    isUi(
                        p,
                        d.width,
                        d.height
                    )
                ) return@filter false

                true
            }

        if (valid.isEmpty()) {
            return emptyList()
        }

        val topScore =
            valid.maxOf { it.score }

        val scoreFloor =
            if (sequence) {
                max(
                    10f,
                    topScore * 0.08f
                )
            } else {
                max(
                    16f,
                    topScore * 0.13f
                )
            }

        return valid
            .filter {
                it.score >= scoreFloor
            }
            .sortedByDescending {
                it.score
            }
    }

    private fun isUi(
        p: SwordPoint,
        width: Int,
        height: Int
    ): Boolean {
        val nx =
            p.x /
            width.toFloat()

        val ny =
            p.y /
            height.toFloat()

        if (
            nx < 0.205f &&
            ny < 0.345f
        ) return true

        if (
            nx > 0.90f &&
            ny < 0.58f
        ) return true

        val controls =
            arrayOf(
                floatArrayOf(
                    0.197f,
                    0.785f,
                    0.105f
                ),
                floatArrayOf(
                    0.841f,
                    0.588f,
                    0.085f
                ),
                floatArrayOf(
                    0.7585f,
                    0.7019f,
                    0.090f
                ),
                floatArrayOf(
                    0.708f,
                    0.893f,
                    0.085f
                ),
                floatArrayOf(
                    0.507f,
                    0.900f,
                    0.060f
                ),
                floatArrayOf(
                    0.570f,
                    0.900f,
                    0.060f
                ),
                floatArrayOf(
                    0.635f,
                    0.900f,
                    0.065f
                )
            )

        val minDim =
            min(
                width,
                height
            ).toFloat()

        for (c in controls) {
            val cx =
                width * c[0]

            val cy =
                height * c[1]

            val radius =
                minDim * c[2]

            if (
                hypot(
                    p.x - cx,
                    p.y - cy
                ) < radius
            ) return true
        }

        return false
    }

    private fun chooseTarget(
        d: FastDetection,
        candidates: List<SwordPoint>
    ): SwordPoint? {
        if (candidates.isEmpty()) {
            return null
        }

        val hero = d.hero

        val maxScore =
            candidates
                .maxOf { it.score }
                .coerceAtLeast(1f)

        return candidates.minByOrNull { p ->
            val dist =
                hypot(
                    p.x - hero.x,
                    p.y - hero.y
                )

            val distanceNorm =
                dist /
                min(
                    d.width,
                    d.height
                ).toFloat()

            val confidence =
                (
                    p.score /
                    maxScore
                ).coerceIn(
                    0f,
                    1f
                )

            distanceNorm -
                confidence * 0.08f
        }
    }

    private fun sendDash(
        d: FastDetection,
        target: SwordPoint
    ) {
        val service =
            GestureAccessibilityService
                .instance
                ?: return

        waitingGesture = true

        val hero = d.hero

        val accepted =
            service.dragSkill2Toward(
                targetX = target.x,
                targetY = target.y,
                heroX = hero.x,
                heroY = hero.y,
                frameWidth = d.width,
                frameHeight = d.height
            ) { completed ->
                worker?.post {
                    waitingGesture = false

                    val doneAt =
                        SystemClock
                            .elapsedRealtime()

                    if (completed) {
                        dashCount++

                        nextDashAt =
                            doneAt +
                            POST_GESTURE_SETTLE_MS

                        Log.d(
                            TAG_DETECTOR,
                            "DASH#$dashCount COMPLETED " +
                                "hero=(${hero.x.roundToInt()},${hero.y.roundToInt()}) " +
                                "target=(${target.x.roundToInt()},${target.y.roundToInt()}) " +
                                "next=${POST_GESTURE_SETTLE_MS}ms"
                        )
                    } else {
                        nextDashAt =
                            doneAt + 60L
                    }
                }
            }

        if (!accepted) {
            waitingGesture = false
        }
    }

    private fun bestRing(
        d: FastDetection,
        candidates: List<SwordPoint>
    ): List<SwordPoint> {
        val pool =
            candidates.take(9)

        if (pool.size < 4) {
            return emptyList()
        }

        if (pool.size == 4) {
            return pool
        }

        var best =
            emptyList<SwordPoint>()

        var bestScore = -1f

        for (a in 0 until pool.size - 3) {
            for (b in a + 1 until pool.size - 2) {
                for (c in b + 1 until pool.size - 1) {
                    for (e in c + 1 until pool.size) {
                        val combo =
                            listOf(
                                pool[a],
                                pool[b],
                                pool[c],
                                pool[e]
                            )

                        val score =
                            ringScore(
                                d.hero,
                                combo
                            )

                        if (score > bestScore) {
                            bestScore = score
                            best = combo
                        }
                    }
                }
            }
        }

        return best
    }

    private fun ringScore(
        hero: HeroEstimate,
        points: List<SwordPoint>
    ): Float {
        if (points.size != 4) {
            return 0f
        }

        val radii =
            points.map {
                hypot(
                    it.x - hero.x,
                    it.y - hero.y
                )
            }

        val avg =
            radii.average()
                .toFloat()

        if (avg < 1f) return 0f

        val radialStd =
            sqrt(
                radii.map {
                    (it - avg).pow(2)
                }.average()
            ).toFloat()

        val radial =
            (
                1f -
                radialStd / avg
            ).coerceIn(
                0f,
                1f
            )

        val angles =
            points.map {
                atan2(
                    it.y - hero.y,
                    it.x - hero.x
                )
            }.sorted()

        val ideal =
            PI.toFloat() / 2f

        var err = 0f

        for (i in 0..3) {
            val a = angles[i]

            var b =
                angles[(i + 1) % 4]

            if (i == 3) {
                b +=
                    2f *
                    PI.toFloat()
            }

            err +=
                abs(
                    (b - a) -
                    ideal
                )
        }

        err /= 4f

        val angular =
            (
                1f -
                err / ideal
            ).coerceIn(
                0f,
                1f
            )

        return radial * 0.46f +
            angular * 0.54f
    }

    private fun stableRing(
        a: List<SwordPoint>,
        b: List<SwordPoint>,
        width: Int,
        height: Int
    ): Boolean {
        if (
            a.size != 4 ||
            b.size != 4
        ) return false

        val threshold =
            min(
                width,
                height
            ) *
            0.07f

        val aa =
            a.sortedBy {
                atan2(
                    it.y -
                        lastHero.y,
                    it.x -
                        lastHero.x
                )
            }

        val bb =
            b.sortedBy {
                atan2(
                    it.y -
                        lastHero.y,
                    it.x -
                        lastHero.x
                )
            }

        for (i in 0..3) {
            if (
                hypot(
                    aa[i].x -
                        bb[i].x,
                    aa[i].y -
                        bb[i].y
                ) >
                threshold
            ) return false
        }

        return true
    }

    private fun endSequence(
        reason: String
    ) {
        Log.d(
            TAG_DETECTOR,
            "SEQUENCE_END " +
                "reason=$reason " +
                "dashes=$dashCount"
        )

        sequenceActive = false
        dashCount = 0
        waitingGesture = false
        nextDashAt = 0L
        targetWaitStart = 0L

        stableFrames = 0
        lastRing = emptyList()
    }

    override fun onDestroy() {
        AutomationState
            .autoExecutionEnabled =
            false

        imageReader
            ?.setOnImageAvailableListener(
                null,
                null
            )

        virtualDisplay?.release()
        imageReader?.close()

        try {
            projection?.stop()
        } catch (_: Throwable) {}

        workerThread?.quitSafely()

        super.onDestroy()
    }

    private fun createChannel() {
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {
            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager
                .createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Screen capture V2.6",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
        }
    }

    private fun pct(
        value: Float
    ): String {
        return "${(value * 100f).roundToInt()}%"
    }
}
