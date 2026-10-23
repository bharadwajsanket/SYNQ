package dev.synq.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

internal class QuickRequestSequence {
    private val last = mutableMapOf<String, Long>()
    @Synchronized fun accept(feature: String, sequence: Long): Boolean {
        if (feature !in setOf("links", "media") || sequence !in 1..9_007_199_254_740_991L || sequence <= (last[feature] ?: 0)) return false
        last[feature] = sequence
        return true
    }
}

internal fun validatedWebLink(value: String): String? = SmartLinkDetector.validateSingleLink(value)

internal fun formatQuickActionStatus(feature: String, accepted: Boolean): String {
    return if (accepted) {
        if (feature == "links") "Link sent to Mac" else "Media command completed"
    } else if (feature == "media") "Player unavailable or busy — check Mac media settings and try again"
    else "Link declined — check settings or dismiss the previous link"
}

data class MacMediaState(
    val player: String = "", val title: String = "", val artist: String = "",
    val playing: Boolean = false, val position: Int = 0, val duration: Int = 0, val volume: Int = 0,
    val detail: String = "Enable a player in Mac Settings → Media",
    val artwork: String? = null,
)

class QuickActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val available: (SYNQFeature) -> Boolean,
    private val send: (String, JSONObject) -> Boolean,
) {
    private val mutableLink = MutableStateFlow<String?>(null)
    val receivedLink = mutableLink.asStateFlow()
    private val mutableStatus = MutableStateFlow<String?>(null)
    val status = mutableStatus.asStateFlow()
    private val mutableMedia = MutableStateFlow(MacMediaState())
    val media = mutableMedia.asStateFlow()
    private val pending = mutableMapOf<String, Pair<String, Job>>()
    private var sequence = 0L
    private var lastNotifiedLink: String? = null
    private var lastNotifiedTime: Long = 0L

    @Synchronized
    fun handleReceivedClipboardLink(link: String) {
        val now = System.currentTimeMillis()
        if (lastNotifiedLink == link && (now - lastNotifiedTime) < 60_000L) {
            return
        }
        lastNotifiedLink = link
        lastNotifiedTime = now
        mutableLink.value = link
        postLinkNotification(link, title = "Link from Mac")
    }

    @Synchronized fun request(feature: SYNQFeature, action: String, value: String = "") {
        if (!available(feature)) { mutableStatus.value = "Feature is unavailable on one of your devices"; return }
        if (feature.key in pending) return
        val id = UUID.randomUUID().toString()
        val expiry = scope.launch {
            delay(8000)
            synchronized(this@QuickActions) {
                if (pending[feature.key]?.first == id) {
                    pending.remove(feature.key)
                    mutableStatus.value = "The other device did not confirm the request"
                }
            }
        }
        pending[feature.key] = id to expiry
        sequence += 1
        mutableStatus.value = "Sending…"
        if (!send("quick.request", JSONObject().put("version", 1).put("requestId", id)
                .put("feature", feature.key).put("action", action).put("value", value).put("sequence", sequence))) {
            pending.remove(feature.key)?.second?.cancel()
            mutableStatus.value = "Not connected — request not sent"
        }
    }

    fun sendLink(value: String) {
        val url = validatedWebLink(value)
        if (url == null) { mutableStatus.value = "Copy a valid http or https link first"; return }
        request(SYNQFeature.LINKS, "offer", url)
    }

    @Synchronized fun receive(kind: String, payload: JSONObject) {
        if (payload.opt("version") != 1) return
        when (kind) {
            "quick.request" -> {
                val id = payload.optString("requestId")
                if (runCatching { UUID.fromString(id) }.isFailure) return
                val link = validatedWebLink(payload.optString("value"))
                val accepted = available(SYNQFeature.LINKS) && payload.optString("feature") == "links" &&
                    payload.optString("action") == "offer" && link != null && mutableLink.value == null
                if (accepted) {
                    mutableLink.value = link
                    postLinkNotification(link)
                }
                send("quick.result", JSONObject().put("version", 1).put("requestId", id).put("feature", "links")
                    .put("accepted", accepted))
            }
            "quick.result" -> {
                val feature = payload.optString("feature")
                if (pending[feature]?.first != payload.optString("requestId")) return
                pending.remove(feature)?.second?.cancel()
                mutableStatus.value = formatQuickActionStatus(feature, payload.opt("accepted") == true)
            }
            "quick.event" -> {
                if (payload.optString("feature") == "links" && payload.optString("event") == "opened") {
                    mutableStatus.value = "Link opened on Mac"
                }
            }
            "media.state" -> {
                if (!available(SYNQFeature.MEDIA)) return
                val position = payload.opt("position") as? Int ?: return
                val duration = payload.opt("duration") as? Int ?: return
                val volume = payload.opt("volume") as? Int ?: return
                if (position !in 0..604800 || duration !in 0..604800 || volume !in 0..100 ||
                    payload.opt("playing") !is Boolean) return
                mutableMedia.value = MacMediaState(
                    payload.optString("player").take(64), payload.optString("title").take(256),
                    payload.optString("artist").take(256), payload.getBoolean("playing"),
                    position, duration, volume, payload.optString("detail").take(256),
                    (payload.opt("artwork") as? String)?.takeIf { it.length <= 21848 },
                )
            }
        }
    }

    fun openLink() {
        val link = mutableLink.value?.let(::validatedWebLink) ?: return
        if (!available(SYNQFeature.LINKS)) { dismissLink(); return }
        clearLinkNotification()
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onSuccess {
                mutableLink.value = null
                send(
                    "quick.event",
                    JSONObject()
                        .put("version", 1)
                        .put("requestId", UUID.randomUUID().toString())
                        .put("feature", "links")
                        .put("event", "opened")
                )
            }
            .onFailure { mutableStatus.value = "No browser available" }
    }
    fun dismissLink() {
        clearLinkNotification()
        mutableLink.value = null
    }

    private fun postLinkNotification(link: String, title: String = "Webpage from Mac") {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val openPendingIntent = PendingIntent.getService(
            context,
            link.hashCode(),
            Intent(context, SYNQConnectionService::class.java).setAction(SYNQConnectionService.ACTION_OPEN_LINK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismissPendingIntent = PendingIntent.getService(
            context,
            link.hashCode() + 1,
            Intent(context, SYNQConnectionService::class.java).setAction(SYNQConnectionService.ACTION_DISMISS_LINK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, "bridgey_clipboard_v2")
            .setSmallIcon(R.drawable.ic_synq_notification)
            .setContentTitle(title)
            .setContentText(link)
            .setContentIntent(openPendingIntent)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_view,
                    "Open in browser",
                    openPendingIntent
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Dismiss",
                    dismissPendingIntent
                ).build()
            )
            .setDeleteIntent(dismissPendingIntent)
            .setAutoCancel(true)
            .build()
        nm.notify(LINK_NOTIFICATION_ID, notification)
    }

    private fun clearLinkNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.cancel(LINK_NOTIFICATION_ID)
    }

    companion object {
        private const val LINK_NOTIFICATION_ID = 70_001
    }
    @Synchronized fun policyChanged() {
        if (!available(SYNQFeature.LINKS)) mutableLink.value = null
        if (!available(SYNQFeature.MEDIA)) mutableMedia.value = MacMediaState()
        pending.entries.removeAll { (feature, operation) ->
            val disabled = SYNQFeature.entries.find { it.key == feature }?.let { !available(it) } ?: true
            if (disabled) { operation.second.cancel(); mutableStatus.value = null }
            disabled
        }
    }
    @Synchronized fun reset() {
        pending.values.forEach { it.second.cancel() }; pending.clear()
        mutableLink.value = null; mutableMedia.value = MacMediaState(); mutableStatus.value = null
    }
}
