package dev.synq.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MirrorConsentActivity : ComponentActivity() {

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val resultCode = result.resultCode
        val data = result.data

        if (resultCode == Activity.RESULT_OK && data != null) {
            Log.i(TAG, "MediaProjection consent granted by user")
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            val width = intent.getIntExtra(EXTRA_WIDTH, 1080)
            val height = intent.getIntExtra(EXTRA_HEIGHT, 2400)
            val fps = intent.getIntExtra(EXTRA_FPS, 60)
            val bitrate = intent.getIntExtra(EXTRA_BITRATE, 4_000_000)

            ScreenMirrorService.startMirror(
                context = this,
                resultCode = resultCode,
                resultData = data,
                sessionId = sessionId,
                width = width,
                height = height,
                fps = fps,
                bitrate = bitrate
            )
            val app = application as? SYNQApplication
            app?.pairing?.handleMirrorAccepted(sessionId)
        } else {
            Log.w(TAG, "MediaProjection consent cancelled or denied")
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            val app = application as? SYNQApplication
            app?.pairing?.handleMirrorRejected(sessionId, "User denied screen capture permission")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (projectionManager == null) {
            Log.e(TAG, "MediaProjectionManager unavailable")
            finish()
            return
        }
        captureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    companion object {
        private const val TAG = "MirrorConsentActivity"
        const val EXTRA_SESSION_ID = "mirror_session_id"
        const val EXTRA_WIDTH = "mirror_width"
        const val EXTRA_HEIGHT = "mirror_height"
        const val EXTRA_FPS = "mirror_fps"
        const val EXTRA_BITRATE = "mirror_bitrate"

        fun start(context: Context, sessionId: String, width: Int, height: Int, fps: Int, bitrate: Int) {
            val intent = Intent(context, MirrorConsentActivity::class.java).apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_FPS, fps)
                putExtra(EXTRA_BITRATE, bitrate)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            context.startActivity(intent)
        }
    }
}

