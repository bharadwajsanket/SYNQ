package dev.synq.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NowPlayingSourceTest {

    @Test
    fun macMetadataPreferredOverLocalAndroidMediaWhenConnected() {
        val macMedia = MacMediaState(
            player = "Spotify",
            title = "Midnight City",
            artist = "M83",
            playing = true,
            position = 45,
            duration = 243,
            volume = 75,
            artwork = "dummyBase64Artwork"
        )
        val phoneMedia = AndroidMediaState(
            hasActiveSession = true,
            playerName = "Media Player",
            title = "I Met Her Again",
            artist = "tav, Maan Panu",
            playing = true,
            positionSeconds = 8,
            durationSeconds = 216,
            volume = 60
        )

        val result = resolveNowPlayingDisplay(
            isMacConnected = true,
            macMedia = macMedia,
            phoneMedia = phoneMedia,
            macName = "Sanket's MacBook Air"
        )

        assertTrue(result is NowPlayingDisplay.Active)
        val active = result as NowPlayingDisplay.Active
        assertEquals("Midnight City", active.title)
        assertEquals("M83 · Spotify (Sanket's MacBook Air)", active.subtitle)
        assertEquals("dummyBase64Artwork", active.artwork)
        assertTrue(active.playing)
        assertEquals(45L, active.positionSeconds)
        assertEquals(243L, active.durationSeconds)
        assertEquals(75, active.volume)
        assertTrue(active.isMacSource)
    }

    @Test
    fun playbackCommandsReachTheMac() {
        val sentRequests = mutableListOf<Pair<String, JSONObject>>()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val quickActions = QuickActions(
            context = null,
            scope = scope,
            available = { it == SYNQFeature.MEDIA },
            send = { kind, payload ->
                sentRequests.add(kind to payload)
                true
            }
        )

        // 1. Toggle play/pause
        quickActions.request(SYNQFeature.MEDIA, "toggle")
        assertEquals(1, sentRequests.size)
        assertEquals("quick.request", sentRequests[0].first)
        assertEquals("media", sentRequests[0].second.getString("feature"))
        assertEquals("toggle", sentRequests[0].second.getString("action"))

        // Simulate Mac replying quick.result
        val toggleRequestId = sentRequests[0].second.getString("requestId")
        quickActions.receive("quick.result", JSONObject().put("version", 1).put("requestId", toggleRequestId).put("feature", "media").put("accepted", true))

        // 2. Next track
        quickActions.request(SYNQFeature.MEDIA, "next")
        assertEquals(2, sentRequests.size)
        assertEquals("next", sentRequests[1].second.getString("action"))
        val nextRequestId = sentRequests[1].second.getString("requestId")
        quickActions.receive("quick.result", JSONObject().put("version", 1).put("requestId", nextRequestId).put("feature", "media").put("accepted", true))

        // 3. Previous track
        quickActions.request(SYNQFeature.MEDIA, "previous")
        assertEquals(3, sentRequests.size)
        assertEquals("previous", sentRequests[2].second.getString("action"))
        val prevRequestId = sentRequests[2].second.getString("requestId")
        quickActions.receive("quick.result", JSONObject().put("version", 1).put("requestId", prevRequestId).put("feature", "media").put("accepted", true))

        // 4. Seek position
        quickActions.request(SYNQFeature.MEDIA, "seek", "120")
        assertEquals(4, sentRequests.size)
        assertEquals("seek", sentRequests[3].second.getString("action"))
        assertEquals("120", sentRequests[3].second.getString("value"))
        val seekRequestId = sentRequests[3].second.getString("requestId")
        quickActions.receive("quick.result", JSONObject().put("version", 1).put("requestId", seekRequestId).put("feature", "media").put("accepted", true))

        // 5. Volume adjustment
        quickActions.request(SYNQFeature.MEDIA, "volume", "85")
        assertEquals(5, sentRequests.size)
        assertEquals("volume", sentRequests[4].second.getString("action"))
        assertEquals("85", sentRequests[4].second.getString("value"))
    }

    @Test
    fun artworkPausedStateAndMissingArtworkHandling() {
        // Paused state with artwork
        val pausedWithArt = MacMediaState(
            player = "Music",
            title = "Starboy",
            artist = "The Weeknd",
            playing = false,
            position = 100,
            duration = 230,
            volume = 50,
            artwork = "sampleArtworkData"
        )
        val result1 = resolveNowPlayingDisplay(
            isMacConnected = true,
            macMedia = pausedWithArt,
            phoneMedia = AndroidMediaState(),
            macName = "MacBook Pro"
        )
        assertTrue(result1 is NowPlayingDisplay.Active)
        val active1 = result1 as NowPlayingDisplay.Active
        assertFalse(active1.playing)
        assertEquals("sampleArtworkData", active1.artwork)
        assertEquals("The Weeknd · Music (MacBook Pro)", active1.subtitle)

        // Playing state with missing artwork
        val playingNoArt = MacMediaState(
            player = "Spotify",
            title = "Blinding Lights",
            artist = "The Weeknd",
            playing = true,
            position = 10,
            duration = 200,
            volume = 70,
            artwork = null
        )
        val result2 = resolveNowPlayingDisplay(
            isMacConnected = true,
            macMedia = playingNoArt,
            phoneMedia = AndroidMediaState(),
            macName = "MacBook Pro"
        )
        assertTrue(result2 is NowPlayingDisplay.Active)
        val active2 = result2 as NowPlayingDisplay.Active
        assertTrue(active2.playing)
        assertNull(active2.artwork)
    }

    @Test
    fun trackChangesReceiveAndStateUpdates() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val quickActions = QuickActions(
            context = null,
            scope = scope,
            available = { it == SYNQFeature.MEDIA },
            send = { _, _ -> true }
        )

        // Mac pushes initial track state
        val track1 = JSONObject()
            .put("version", 1)
            .put("player", "Music")
            .put("title", "Track One")
            .put("artist", "Artist One")
            .put("playing", true)
            .put("position", 15)
            .put("duration", 180)
            .put("volume", 60)
            .put("detail", "")
            .put("artwork", "artwork1")
        quickActions.receive("media.state", track1)

        assertEquals("Track One", quickActions.media.value.title)
        assertEquals("Artist One", quickActions.media.value.artist)
        assertEquals("Music", quickActions.media.value.player)
        assertTrue(quickActions.media.value.playing)

        // Mac pushes track change to Track Two
        val track2 = JSONObject()
            .put("version", 1)
            .put("player", "Music")
            .put("title", "Track Two")
            .put("artist", "Artist Two")
            .put("playing", true)
            .put("position", 0)
            .put("duration", 210)
            .put("volume", 60)
            .put("detail", "")
            .put("artwork", "artwork2")
        quickActions.receive("media.state", track2)

        assertEquals("Track Two", quickActions.media.value.title)
        assertEquals("Artist Two", quickActions.media.value.artist)
        assertEquals("artwork2", quickActions.media.value.artwork)
    }

    @Test
    fun disconnectHandlingClearsRemoteSession() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val quickActions = QuickActions(
            context = null,
            scope = scope,
            available = { it == SYNQFeature.MEDIA },
            send = { _, _ -> true }
        )

        // Setup active media
        quickActions.receive("media.state", JSONObject()
            .put("version", 1)
            .put("player", "Spotify")
            .put("title", "Song Before Disconnect")
            .put("artist", "Artist")
            .put("playing", true)
            .put("position", 30)
            .put("duration", 200)
            .put("volume", 50)
            .put("detail", "")
        )
        assertEquals("Song Before Disconnect", quickActions.media.value.title)

        // Peer disconnects -> quickActions.reset() called by PairingCoordinator
        quickActions.reset()

        // Verify remote media state is cleared
        assertEquals("", quickActions.media.value.title)
        assertEquals("", quickActions.media.value.artist)
        assertFalse(quickActions.media.value.playing)

        // When disconnected and no phone playback, resolver returns Hidden
        val display = resolveNowPlayingDisplay(
            isMacConnected = false,
            macMedia = quickActions.media.value,
            phoneMedia = AndroidMediaState()
        )
        assertTrue(display is NowPlayingDisplay.Hidden)
    }

    @Test
    fun localPlaybackFallbackWhenNoMacConnected() {
        val phoneMedia = AndroidMediaState(
            hasActiveSession = true,
            playerName = "YouTube Music",
            title = "Local Phone Song",
            artist = "Local Artist",
            playing = true,
            positionSeconds = 120,
            durationSeconds = 240,
            volume = 40
        )

        // Disconnected from Mac
        val result = resolveNowPlayingDisplay(
            isMacConnected = false,
            macMedia = MacMediaState(),
            phoneMedia = phoneMedia
        )

        assertTrue(result is NowPlayingDisplay.Active)
        val active = result as NowPlayingDisplay.Active
        assertEquals("Local Phone Song", active.title)
        assertEquals("Local Artist · YouTube Music (This phone)", active.subtitle)
        assertFalse(active.isMacSource)
        assertTrue(active.playing)
        assertEquals(120L, active.positionSeconds)
        assertEquals(240L, active.durationSeconds)
        assertEquals(40, active.volume)
    }

    @Test
    fun macConnectedWithNoActiveMediaShowsEmptyStateWithoutLeakingPhoneMedia() {
        val phoneMedia = AndroidMediaState(
            hasActiveSession = true,
            playerName = "Media Player",
            title = "Secret Phone Track",
            artist = "Phone Singer",
            playing = true
        )
        val emptyMacMedia = MacMediaState(
            player = "Auto-detect",
            title = "",
            artist = "",
            playing = false,
            detail = "Open Music or Spotify on your Mac"
        )

        // When Mac is connected, phone media must NOT leak into the card
        val result = resolveNowPlayingDisplay(
            isMacConnected = true,
            macMedia = emptyMacMedia,
            phoneMedia = phoneMedia,
            macName = "Sanket's MacBook Air"
        )

        assertTrue(result is NowPlayingDisplay.Empty)
        val empty = result as NowPlayingDisplay.Empty
        assertEquals("No media playing", empty.title)
        assertEquals("Open Music or Spotify on your Mac", empty.detail)
    }

    @Test
    fun sourceListParsingAndManualSourceSelection() {
        val sentRequests = mutableListOf<Pair<String, JSONObject>>()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val quickActions = QuickActions(
            context = null,
            scope = scope,
            available = { it == SYNQFeature.MEDIA },
            send = { kind, payload ->
                sentRequests.add(kind to payload)
                true
            }
        )

        val sourcesArray = org.json.JSONArray()
            .put(JSONObject().put("id", "com.google.Chrome").put("name", "Chrome").put("title", "YouTube Video").put("artist", "Creator").put("playing", true).put("duration", 200).put("position", 10))
            .put(JSONObject().put("id", "com.apple.Music").put("name", "Apple Music").put("title", "Music Song").put("artist", "Artist").put("playing", false).put("duration", 180).put("position", 0))

        quickActions.receive("media.state", JSONObject()
            .put("version", 1)
            .put("player", "Chrome")
            .put("title", "YouTube Video")
            .put("artist", "Creator")
            .put("playing", true)
            .put("position", 10)
            .put("duration", 200)
            .put("volume", 80)
            .put("detail", "")
            .put("selectedSourceId", "com.google.Chrome")
            .put("sources", sourcesArray)
        )

        val media = quickActions.media.value
        assertEquals("Chrome", media.player)
        assertEquals("com.google.Chrome", media.selectedSourceId)
        assertEquals(2, media.sources.size)
        assertEquals("Chrome", media.sources[0].name)
        assertTrue(media.sources[0].playing)
        assertEquals("Apple Music", media.sources[1].name)
        assertFalse(media.sources[1].playing)

        // Select Apple Music manually
        quickActions.selectSource("com.apple.Music")
        assertEquals(1, sentRequests.size)
        assertEquals("quick.request", sentRequests[0].first)
        assertEquals("media", sentRequests[0].second.getString("feature"))
        assertEquals("selectSource", sentRequests[0].second.getString("action"))
        assertEquals("com.apple.Music", sentRequests[0].second.getString("value"))

        // Acknowledge request from Mac
        val req1Id = sentRequests[0].second.getString("requestId")
        quickActions.receive("quick.result", JSONObject().put("version", 1).put("requestId", req1Id).put("feature", "media").put("accepted", true))

        // Select Auto
        quickActions.selectSource("auto")
        assertEquals(2, sentRequests.size)
        assertEquals("selectSource", sentRequests[1].second.getString("action"))
        assertEquals("auto", sentRequests[1].second.getString("value"))
    }

    @Test
    fun mediaPermissionDisabledClearsMediaState() {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        var mediaAllowed = true
        val quickActions = QuickActions(
            context = null,
            scope = scope,
            available = { if (it == SYNQFeature.MEDIA) mediaAllowed else true },
            send = { _, _ -> true }
        )

        quickActions.receive("media.state", JSONObject()
            .put("version", 1)
            .put("player", "Spotify")
            .put("title", "Active Song")
            .put("artist", "Artist")
            .put("playing", true)
            .put("position", 15)
            .put("duration", 150)
            .put("volume", 50)
        )
        assertEquals("Active Song", quickActions.media.value.title)

        // Mac toggles media control off -> remote feature becomes disabled
        mediaAllowed = false
        quickActions.policyChanged()

        // Verify media state is cleared
        assertEquals("", quickActions.media.value.title)
        assertEquals("", quickActions.media.value.player)
        assertFalse(quickActions.media.value.playing)

        // Re-enabled: new state arrives
        mediaAllowed = true
        quickActions.policyChanged()
        quickActions.receive("media.state", JSONObject()
            .put("version", 1)
            .put("player", "Crest")
            .put("title", "Recovered Song")
            .put("artist", "Artist")
            .put("playing", true)
            .put("position", 20)
            .put("duration", 180)
            .put("volume", 70)
        )
        assertEquals("Recovered Song", quickActions.media.value.title)
        assertEquals("Crest", quickActions.media.value.player)
    }
}
