package dev.synq.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenMirrorService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var lastRecordedLandscape: Boolean? = null
    private var spsPpsData: ByteArray? = null

    private val isRunning = AtomicBoolean(false)
    private var encoderThread: Thread? = null
    private var audioThread: Thread? = null

    private var sessionId: String = ""
    private var videoWidth = 1080
    private var videoHeight = 2400
    private var targetFps = 60
    private var targetBitrate = 4_000_000

    private var screenWidth = 1080
    private var screenHeight = 2400

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopMirrorSession()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        val action = intent.action
        if (action == ACTION_STOP) {
            stopMirrorSession()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val rawW = intent.getIntExtra(EXTRA_WIDTH, 1080)
        val rawH = intent.getIntExtra(EXTRA_HEIGHT, 2400)
        videoWidth = (rawW / 16) * 16
        videoHeight = (rawH / 16) * 16
        targetFps = intent.getIntExtra(EXTRA_FPS, 60)
        targetBitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000)

        updateDisplayMetrics()

        val notification = buildForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (resultCode != 0 && resultData != null) {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            mediaProjection = projectionManager?.getMediaProjection(resultCode, resultData)
            mediaProjection?.registerCallback(projectionCallback, null)
            startEncoding()
            startAudioCapture()
        }

        return START_STICKY
    }

    private fun updateDisplayMetrics() {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager?.maximumWindowMetrics?.bounds ?: windowManager?.currentWindowMetrics?.bounds
            if (metrics != null) {
                screenWidth = metrics.width()
                screenHeight = metrics.height()
            }
        } else {
            val displayMetrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(displayMetrics)
            screenWidth = displayMetrics.widthPixels
            screenHeight = displayMetrics.heightPixels
        }
    }

    private fun startEncoding() {
        if (isRunning.getAndSet(true)) return

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "SYNQ:ScreenMirrorWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock: ${e.message}")
        }

        SYNQAccessibilityService.setSoftKeyboardHidden(true)

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / targetFps)
                }
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = createInputSurface()
                start()

                val densityDpi = resources.displayMetrics.densityDpi
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "SYNQScreenMirror",
                    videoWidth,
                    videoHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null
                )
            }

            registerDisplayListener()
            encoderThread = Thread({ runEncoderLoop() }, "SYNQ-VideoEncoder").apply { start() }
            Log.i(TAG, "Screen mirror encoder started ($videoWidth x $videoHeight @ ${targetFps}fps)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start video encoder", e)
            stopMirrorSession()
        }
    }

    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                checkOrientationChange()
            }
        }
        dm.registerDisplayListener(listener, null)
        displayListener = listener
    }

    private fun checkOrientationChange() {
        updateDisplayMetrics()
        val isLandscape = screenWidth > screenHeight
        if (lastRecordedLandscape == null) {
            lastRecordedLandscape = isLandscape
            return
        }
        if (isLandscape != lastRecordedLandscape) {
            lastRecordedLandscape = isLandscape
            Log.i(TAG, "Screen orientation changed! isLandscape=$isLandscape ($screenWidth x $screenHeight)")
            val targetW = if (isLandscape) maxOf(videoWidth, videoHeight) else minOf(videoWidth, videoHeight)
            val targetH = if (isLandscape) minOf(videoWidth, videoHeight) else maxOf(videoWidth, videoHeight)
            reconfigureEncoder(targetW, targetH)
        }
    }

    @Synchronized
    private fun reconfigureEncoder(newW: Int, newH: Int) {
        val targetW = (newW / 16) * 16
        val targetH = (newH / 16) * 16
        if (targetW == videoWidth && targetH == videoHeight && videoEncoder != null) return

        videoWidth = targetW
        videoHeight = targetH
        spsPpsData = null
        Log.i(TAG, "Reconfiguring video encoder to $videoWidth x $videoHeight @ ${targetFps}fps")

        val oldEncoder = videoEncoder
        videoEncoder = null

        try {
            oldEncoder?.stop()
            oldEncoder?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Old encoder release: ${e.message}")
        }

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000 / targetFps)
                }
            }

            val newEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            val newSurface = newEncoder.createInputSurface()
            newEncoder.start()
            videoEncoder = newEncoder

            val densityDpi = resources.displayMetrics.densityDpi
            if (virtualDisplay != null) {
                virtualDisplay?.resize(videoWidth, videoHeight, densityDpi)
                virtualDisplay?.setSurface(newSurface)
            } else {
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "SYNQScreenMirror",
                    videoWidth,
                    videoHeight,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    newSurface,
                    null,
                    null
                )
            }
            Log.i(TAG, "Reconfigured virtual display and encoder successfully ($videoWidth x $videoHeight)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconfigure encoder to $videoWidth x $videoHeight: ${e.message}", e)
        }
    }

    private fun runEncoderLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val app = application as? SYNQApplication

        while (isRunning.get()) {
            val encoder = videoEncoder
            if (encoder == null) {
                try {
                    Thread.sleep(30)
                } catch (e: InterruptedException) {
                    break
                }
                continue
            }

            val outputBufferIndex = try {
                encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            } catch (e: Exception) {
                try {
                    Thread.sleep(20)
                } catch (ie: InterruptedException) {
                    break
                }
                continue
            }

            if (outputBufferIndex >= 0) {
                try {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                        val outBytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(outBytes)

                        if (isConfig) {
                            spsPpsData = outBytes
                        } else {
                            val header = spsPpsData
                            val packetBytes = if (isKeyframe && header != null) {
                                header + outBytes
                            } else {
                                outBytes
                            }
                            val base64Frame = Base64.encodeToString(packetBytes, Base64.NO_WRAP)
                            app?.pairing?.sendMirrorFrame(
                                sessionId = sessionId,
                                data = base64Frame,
                                isKeyframe = isKeyframe,
                                pts = bufferInfo.presentationTimeUs / 1000,
                                width = videoWidth,
                                height = videoHeight
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error encoding video frame: ${e.message}")
                } finally {
                    try {
                        encoder.releaseOutputBuffer(outputBufferIndex, false)
                    } catch (e: Exception) {
                        // ignore if stopped
                    }
                }
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try {
                    val newFormat = encoder.outputFormat
                    val sps = newFormat.getByteBuffer("csd-0")
                    val pps = newFormat.getByteBuffer("csd-1")
                    if (sps != null && pps != null) {
                        val spsBytes = ByteArray(sps.remaining())
                        sps.get(spsBytes)
                        val ppsBytes = ByteArray(pps.remaining())
                        pps.get(ppsBytes)
                        spsPpsData = spsBytes + ppsBytes
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error handling output format changed: ${e.message}")
                }
            }
        }
    }

    private fun startAudioCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val projection = mediaProjection ?: return

        try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val sampleRate = 48000
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBufferSize * 2)
                .build()

            audioRecord?.startRecording()
            audioThread = Thread({ runAudioLoop() }, "SYNQ-AudioCapture").apply { start() }
            Log.i(TAG, "Audio playback capture started")
        } catch (e: Exception) {
            Log.w(TAG, "Audio capture init failed or not supported on this device: ${e.message}")
        }
    }

    private fun runAudioLoop() {
        val record = audioRecord ?: return
        val buffer = ByteArray(2048)
        val app = application as? SYNQApplication

        while (isRunning.get()) {
            try {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val data = if (read == buffer.size) buffer else buffer.copyOf(read)
                    val base64Pcm = Base64.encodeToString(data, Base64.NO_WRAP)
                    app?.pairing?.sendMirrorAudio(sessionId, base64Pcm)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture loop ended: ${e.message}")
                break
            }
        }
    }

    fun handleInput(action: String, nx: Double?, ny: Double?, deltaX: Double?, deltaY: Double?, keyCode: String?, text: String?) {
        updateDisplayMetrics()
        val x = ((nx ?: 0.5) * screenWidth).toFloat()
        val y = ((ny ?: 0.5) * screenHeight).toFloat()

        when (action) {
            "nav" -> {
                keyCode?.let { SYNQAccessibilityService.performNavAction(it) }
            }
            "tap" -> {
                SYNQAccessibilityService.dispatchTap(x, y)
            }
            "double_tap" -> {
                SYNQAccessibilityService.dispatchDoubleTap(x, y)
            }
            "long_press" -> {
                SYNQAccessibilityService.dispatchLongPress(x, y)
            }
            "swipe" -> {
                val endX = ((deltaX ?: nx ?: 0.5) * screenWidth).toFloat()
                val endY = ((deltaY ?: ny ?: 0.5) * screenHeight).toFloat()
                val duration = (keyCode?.toLongOrNull() ?: 200L).coerceIn(60, 600)
                SYNQAccessibilityService.dispatchSwipe(x, y, endX, endY, duration)
            }
            "drag_step" -> {
                val endX = ((deltaX ?: nx ?: 0.5) * screenWidth).toFloat()
                val endY = ((deltaY ?: ny ?: 0.5) * screenHeight).toFloat()
                val duration = (keyCode?.toLongOrNull() ?: 35L).coerceIn(20, 80)
                SYNQAccessibilityService.dispatchSwipe(x, y, endX, endY, duration)
            }
            "scroll" -> {
                val dx = (deltaX ?: 0.0).toFloat()
                val dy = (deltaY ?: 0.0).toFloat()
                val duration = (keyCode?.toLongOrNull() ?: 85L).coerceIn(60L, 140L)
                val scaleFactor = (screenHeight / 900.0f) * 1.5f
                val moveX = dx * scaleFactor
                val moveY = dy * scaleFactor
                SYNQAccessibilityService.dispatchScroll(x, y, moveX, moveY, duration)
            }
            "text" -> {
                text?.let { SYNQAccessibilityService.inputText(it) }
            }
            "key" -> {
                keyCode?.let { SYNQAccessibilityService.handleKey(it) }
            }
            "shortcut" -> {
                keyCode?.let { SYNQAccessibilityService.handleShortcut(it) }
            }
            "rotate" -> {
                val target = keyCode ?: if (screenWidth > screenHeight) "portrait" else "landscape"
                toggleOrientation(target == "landscape")
            }
            "down", "up" -> {
                SYNQAccessibilityService.dispatchTap(x, y)
            }
        }
    }

    fun toggleOrientation(landscape: Boolean) {
        try {
            val resolver = contentResolver
            Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0)
            Settings.System.putInt(
                resolver,
                Settings.System.USER_ROTATION,
                if (landscape) Surface.ROTATION_90 else Surface.ROTATION_0
            )
            Log.i(TAG, "Set user rotation to landscape=$landscape")
        } catch (e: Exception) {
            Log.w(TAG, "Could not set user_rotation: ${e.message}")
        }
        updateDisplayMetrics()
        val targetW = if (landscape) maxOf(videoWidth, videoHeight) else minOf(videoWidth, videoHeight)
        val targetH = if (landscape) minOf(videoWidth, videoHeight) else maxOf(videoWidth, videoHeight)
        reconfigureEncoder(targetW, targetH)
    }

    fun stopMirrorSession() {
        if (!isRunning.getAndSet(false)) return
        Log.i(TAG, "Stopping screen mirror session")

        encoderThread?.interrupt()
        audioThread?.interrupt()

        displayListener?.let {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            dm?.unregisterDisplayListener(it)
            displayListener = null
        }

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null

        runCatching {
            videoEncoder?.stop()
            videoEncoder?.release()
        }
        videoEncoder = null

        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null

        runCatching {
            SYNQAccessibilityService.setSoftKeyboardHidden(false)
        }

        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
        wakeLock = null

        runCatching {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null

        val app = application as? SYNQApplication
        app?.pairing?.handleMirrorStopped(sessionId)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMirrorSession()
        if (instance === this) {
            instance = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SYNQ View",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active SYNQ View session streaming via SSP"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("SYNQ View")
            .setContentText("Streaming to your Mac via SSP")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "ScreenMirrorService"
        private const val CHANNEL_ID = "synq_screen_mirror"
        private const val NOTIFICATION_ID = 55402

        const val ACTION_STOP = "dev.synq.android.action.STOP_MIRROR"
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_FPS = "fps"
        const val EXTRA_BITRATE = "bitrate"

        @Volatile
        var instance: ScreenMirrorService? = null
            private set

        fun startMirror(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            sessionId: String,
            width: Int,
            height: Int,
            fps: Int,
            bitrate: Int
        ) {
            val intent = Intent(context, ScreenMirrorService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_BITRATE, bitrate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenMirrorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

