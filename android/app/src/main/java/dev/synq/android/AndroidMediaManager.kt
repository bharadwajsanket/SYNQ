package dev.synq.android

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

data class AndroidMediaState(
    val hasActiveSession: Boolean = false,
    val playerPackage: String = "",
    val playerName: String = "",
    val title: String = "",
    val artist: String = "",
    val playing: Boolean = false,
    val positionSeconds: Long = 0L,
    val durationSeconds: Long = 0L,
    val volume: Int = 0,
    val maxVolume: Int = 100,
    val volumeSupported: Boolean = false,
    val canPlayPause: Boolean = false,
    val canSkipNext: Boolean = false,
    val canSkipPrevious: Boolean = false,
    val canSeek: Boolean = false,
    val artwork: Bitmap? = null,
)

class AndroidMediaManager(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var mediaSessionManager: MediaSessionManager? = null

    private val mutableState = MutableStateFlow(AndroidMediaState())
    val state: StateFlow<AndroidMediaState> = mutableState.asStateFlow()

    private var activeController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    // Call interruption recovery state
    private var wasPlayingBeforeInterruption = false
    private var interruptedPackage: String? = null
    private var interruptionTimestamp = 0L
    private var resumeAttemptWork: Runnable? = null

    companion object {
        private const val TAG = "SYNQMedia"
        private val SUPPORTED_PACKAGES = setOf(
            "com.apple.android.music",
            "com.spotify.music",
            "com.spotify.client"
        )
    }

    fun initSessionManager(listenerComponent: ComponentName) {
        try {
            val sm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            mediaSessionManager = sm
            sm?.addOnActiveSessionsChangedListener(
                { controllers -> onActiveSessionsChanged(controllers) },
                listenerComponent,
                mainHandler
            )
            val current = sm?.getActiveSessions(listenerComponent)
            onActiveSessionsChanged(current)
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize media session listener: ${e.message}")
        }
    }

    fun onActiveSessionsChanged(controllers: List<MediaController>?) {
        mainHandler.post {
            // Prefer supported players (Apple Music / Spotify), otherwise first active
            val preferred = controllers?.firstOrNull { it.packageName in SUPPORTED_PACKAGES }
                ?: controllers?.firstOrNull()

            if (preferred == null) {
                detachCurrentController()
                mutableState.value = AndroidMediaState()
                return@post
            }

            if (activeController?.packageName != preferred.packageName ||
                activeController?.sessionToken != preferred.sessionToken) {
                attachController(preferred)
            } else {
                updateStateFromController(preferred)
            }
        }
    }

    private fun attachController(controller: MediaController) {
        detachCurrentController()
        activeController = controller

        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                updateStateFromController(controller)
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                updateStateFromController(controller)
            }

            override fun onSessionDestroyed() {
                mainHandler.post {
                    if (activeController === controller) {
                        detachCurrentController()
                        mutableState.value = AndroidMediaState()
                    }
                }
            }
        }
        controllerCallback = callback
        controller.registerCallback(callback, mainHandler)
        updateStateFromController(controller)
    }

    private fun detachCurrentController() {
        controllerCallback?.let { callback ->
            activeController?.unregisterCallback(callback)
        }
        controllerCallback = null
        activeController = null
    }

    private fun updateStateFromController(controller: MediaController) {
        val metadata = controller.metadata
        val pbState = controller.playbackState

        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val positionMs = pbState?.position ?: 0L

        val durationSec = if (durationMs > 0) durationMs / 1000L else 0L
        val positionSec = if (positionMs > 0) positionMs / 1000L else 0L

        val actions = pbState?.actions ?: 0L
        val canPlayPause = (actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)) != 0L
        val canSkipNext = (actions and PlaybackState.ACTION_SKIP_TO_NEXT) != 0L
        val canSkipPrev = (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L
        val canSeek = (actions and PlaybackState.ACTION_SEEK_TO) != 0L

        val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 100

        val pkg = controller.packageName.orEmpty()
        val friendlyName = when {
            pkg.contains("apple", ignoreCase = true) -> "Apple Music"
            pkg.contains("spotify", ignoreCase = true) -> "Spotify"
            else -> "Media Player"
        }

        val art = runCatching {
            metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        }.getOrNull()

        mutableState.value = AndroidMediaState(
            hasActiveSession = true,
            playerPackage = pkg,
            playerName = friendlyName,
            title = title,
            artist = artist,
            playing = isPlaying,
            positionSeconds = positionSec,
            durationSeconds = durationSec,
            volume = currentVol,
            maxVolume = maxVol,
            volumeSupported = maxVol > 0,
            canPlayPause = canPlayPause,
            canSkipNext = canSkipNext,
            canSkipPrevious = canSkipPrev,
            canSeek = canSeek,
            artwork = art
        )
    }

    // Controls
    fun togglePlayPause() {
        val controller = activeController ?: return
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) {
            // User deliberate pause: cancel any pending call recovery
            wasPlayingBeforeInterruption = false
            controller.transportControls.pause()
        } else {
            controller.transportControls.play()
        }
    }

    fun play() {
        activeController?.transportControls?.play()
    }

    fun pause() {
        wasPlayingBeforeInterruption = false
        activeController?.transportControls?.pause()
    }

    fun skipNext() {
        activeController?.transportControls?.skipToNext()
    }

    fun skipPrevious() {
        activeController?.transportControls?.skipToPrevious()
    }

    fun seekTo(seconds: Long) {
        activeController?.transportControls?.seekTo(seconds * 1000L)
    }

    fun setVolume(volume: Int) {
        val am = audioManager ?: return
        val clamped = volume.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
        am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        mutableState.value = mutableState.value.copy(volume = clamped)
    }

    // Call Interruption Handling
    fun onCallStateChanged(callState: Int) {
        resumeAttemptWork?.let { mainHandler.removeCallbacks(it) }
        resumeAttemptWork = null

        when (callState) {
            android.telephony.TelephonyManager.CALL_STATE_RINGING,
            android.telephony.TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Call incoming or ongoing: check if player is currently playing
                val controller = activeController
                val isPlaying = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
                if (isPlaying) {
                    wasPlayingBeforeInterruption = true
                    interruptedPackage = controller?.packageName
                    interruptionTimestamp = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Media playing before call interruption: ${controller?.packageName}")
                }
            }
            android.telephony.TelephonyManager.CALL_STATE_IDLE -> {
                // Call ended or rejected: safely resume if was playing before interruption
                if (wasPlayingBeforeInterruption && interruptedPackage != null) {
                    scheduleSafeResume(interruptedPackage!!)
                }
            }
        }
    }

    private fun scheduleSafeResume(targetPackage: String) {
        // Wait 600ms for Telecom audio-focus release, verify player didn't already resume, then play
        val work = Runnable {
            val controller = activeController
            val elapsed = SystemClock.elapsedRealtime() - interruptionTimestamp
            // Do not resume if interruption was too old (> 10 minutes) or user changed player
            if (elapsed > 10 * 60 * 1000L || controller?.packageName != targetPackage) {
                wasPlayingBeforeInterruption = false
                interruptedPackage = null
                return@Runnable
            }

            // Check if player already resumed on its own
            val alreadyPlaying = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
            if (alreadyPlaying) {
                Log.d(TAG, "Player $targetPackage resumed automatically, no intervention needed")
                wasPlayingBeforeInterruption = false
                interruptedPackage = null
                return@Runnable
            }

            // Check audio focus state
            val mode = audioManager?.mode ?: AudioManager.MODE_NORMAL
            if (mode == AudioManager.MODE_NORMAL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                Log.i(TAG, "Resuming interrupted playback for $targetPackage")
                controller?.transportControls?.play()
            }

            wasPlayingBeforeInterruption = false
            interruptedPackage = null
        }
        resumeAttemptWork = work
        mainHandler.postDelayed(work, 650L)
    }
}

