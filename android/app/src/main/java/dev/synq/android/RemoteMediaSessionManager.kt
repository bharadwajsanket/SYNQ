package dev.synq.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Base64
import android.util.Log

/**
 * Manages a system [MediaSession] that mirrors the currently-playing media from the paired Mac.
 *
 * When the Mac is connected and playing audio, this session:
 * - Appears on the Android lock screen with artwork, title and controls
 * - Shows a [MediaStyle] notification in the notification shade
 * - Routes user control actions (play/pause, next, prev) back through [QuickActions]
 *
 * When the Mac disconnects or stops playing, the session is cleared and the notification removed.
 */
class RemoteMediaSessionManager(
    private val context: Context,
    private val onCommand: (action: String, value: String) -> Unit,
) {
    private var mediaSession: MediaSession? = null
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    companion object {
        private const val TAG = "SYNQRemoteMedia"
        private const val CHANNEL_ID = "synq_remote_media"
        private const val NOTIFICATION_ID = 72_001
    }

    init {
        ensureChannel()
    }

    // MARK: - Public API

    /** Call whenever a new [MacMediaState] is received from the Mac. */
    fun updateFromMac(state: MacMediaState, macName: String = "Mac") {
        if (state.title.isBlank()) {
            clear()
            return
        }
        val session = ensureSession()

        // Build metadata
        val artworkBitmap: Bitmap? = state.artwork?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (bytes.size > 2_097_152) return@let null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }

        val metaBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title.ifEmpty { "No title" })
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist.ifEmpty { state.player })
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "${state.player} · $macName")
        if (state.duration > 0) {
            metaBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, state.duration * 1000L)
        }
        artworkBitmap?.let { bmp ->
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
            metaBuilder.putBitmap(MediaMetadata.METADATA_KEY_ART, bmp)
        }
        session.setMetadata(metaBuilder.build())

        // Build playback state
        val stateCode = if (state.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val actions = PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                (if (state.duration > 0) PlaybackState.ACTION_SEEK_TO else 0L)
        val pbState = PlaybackState.Builder()
            .setState(stateCode, state.position * 1000L, if (state.playing) 1f else 0f)
            .setActions(actions)
            .build()
        session.setPlaybackState(pbState)

        // Activate and post notification
        session.isActive = true
        postNotification(state, session, artworkBitmap, macName)
    }

    /** Call when the Mac disconnects or media is explicitly cleared. */
    fun clear() {
        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        notificationManager.cancel(NOTIFICATION_ID)
        Log.d(TAG, "Remote media session cleared")
    }

    // MARK: - Session management

    private fun ensureSession(): MediaSession {
        return mediaSession ?: createSession().also { mediaSession = it }
    }

    private fun createSession(): MediaSession {
        Log.d(TAG, "Creating remote media session")
        return MediaSession(context, "SYNQRemoteMedia").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    onCommand("toggle", "")
                }
                override fun onPause() {
                    onCommand("toggle", "")
                }
                override fun onSkipToNext() {
                    onCommand("next", "")
                }
                override fun onSkipToPrevious() {
                    onCommand("previous", "")
                }
                override fun onSeekTo(pos: Long) {
                    onCommand("seek", (pos / 1000L).toString())
                }
            })
        }
    }

    // MARK: - Notification

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SYNQ Remote Media",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mac media playback controls"
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun postNotification(
        state: MacMediaState,
        session: MediaSession,
        artwork: Bitmap?,
        macName: String,
    ) {
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action pending intents — route via SYNQConnectionService
        fun actionIntent(action: String) = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, SYNQConnectionService::class.java)
                .setAction("dev.synq.REMOTE_MEDIA_$action"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val style = Notification.MediaStyle()
            .setMediaSession(session.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_synq_notification)
            .setContentTitle(state.title.ifEmpty { "Media from $macName" })
            .setContentText(
                buildString {
                    if (state.artist.isNotEmpty()) append(state.artist)
                    if (state.artist.isNotEmpty() && state.player.isNotEmpty()) append(" · ")
                    if (state.player.isNotEmpty()) append(state.player)
                    if (isEmpty()) append(macName)
                }
            )
            .setContentIntent(openPendingIntent)
            .setDeleteIntent(null)
            .setOngoing(state.playing)
            .setStyle(style)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous, "Previous",
                    actionIntent("previous")
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    if (state.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                    if (state.playing) "Pause" else "Play",
                    actionIntent(if (state.playing) "pause" else "play")
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next, "Next",
                    actionIntent("next")
                ).build()
            )

        artwork?.let { builder.setLargeIcon(it) }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }
}

