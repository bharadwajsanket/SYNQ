package dev.synq.android

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

@Suppress("DEPRECATION")
class SYNQInCallService : InCallService() {

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        instance = this
        Log.d(TAG, "onCallAdded: active call count = ${calls.size}")
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        Log.d(TAG, "onCallRemoved: remaining calls = ${calls.size}")
        if (calls.isEmpty() && instance === this) {
            instance = null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        Log.d(TAG, "onCallAudioStateChanged: route=${audioState?.route}, isMuted=${audioState?.isMuted}")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
    }

    companion object {
        private const val TAG = "SYNQInCallService"

        @Volatile
        var instance: SYNQInCallService? = null
            private set

        fun toggleSpeaker(): Boolean {
            val service = instance ?: return false
            return runCatching {
                val audioState = service.callAudioState
                val isSpeaker = audioState?.route == CallAudioState.ROUTE_SPEAKER
                if (isSpeaker) {
                    val supported = audioState?.supportedRouteMask ?: CallAudioState.ROUTE_EARPIECE
                    val targetRoute = when {
                        (supported and CallAudioState.ROUTE_EARPIECE) != 0 -> CallAudioState.ROUTE_EARPIECE
                        (supported and CallAudioState.ROUTE_WIRED_HEADSET) != 0 -> CallAudioState.ROUTE_WIRED_HEADSET
                        (supported and CallAudioState.ROUTE_BLUETOOTH) != 0 -> CallAudioState.ROUTE_BLUETOOTH
                        else -> CallAudioState.ROUTE_EARPIECE
                    }
                    service.setAudioRoute(targetRoute)
                } else {
                    service.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                }
                true
            }.getOrDefault(false)
        }

        fun toggleMute(): Boolean {
            val service = instance ?: return false
            return runCatching {
                val audioState = service.callAudioState
                val isMuted = audioState?.isMuted ?: false
                service.setMuted(!isMuted)
                true
            }.getOrDefault(false)
        }

        fun endCall(): Boolean {
            val service = instance ?: return false
            return runCatching {
                val call = service.calls.firstOrNull() ?: return false
                call.disconnect()
                true
            }.getOrDefault(false)
        }
    }
}
