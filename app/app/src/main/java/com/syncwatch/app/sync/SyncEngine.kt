package com.syncwatch.app.sync

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.syncwatch.app.network.SignalingClient

/**
 * SyncEngine manages the synchronization logic between host and guests.
 * - Host mode: monitors player state changes and broadcasts them
 * - Guest mode: receives sync commands and applies them to the local player
 */
class SyncEngine(
    private val signalingClient: SignalingClient,
    private val listener: SyncListener
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val SMOOTH_THRESHOLD_MS = 2000L  // < 2s: smooth catch-up
        private const val JUMP_THRESHOLD_MS = 5000L     // > 5s: direct jump
        private const val SYNC_DEBOUNCE_MS = 500L       // debounce rapid changes
    }

    interface SyncListener {
        fun onSyncStatusChanged(message: String)
        fun onSyncComplete()
    }

    var isHost = false
    var isController = false
    private var player: ExoPlayer? = null
    private var isSyncing = false  // flag to prevent echo when guest applies sync
    private var lastSyncSentTime = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (isSyncing) return
            if (!isController) return
            if (playbackState == Player.STATE_READY) {
                broadcastCurrentState()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isSyncing) return
            if (!isController) return
            val action = if (isPlaying) "play" else "pause"
            broadcastSync(action)
        }

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            if (isSyncing) return
            if (!isController) return
            broadcastSync(if (player?.isPlaying == true) "speed" else "pause")
        }
    }

    fun attachPlayer(exoPlayer: ExoPlayer) {
        player = exoPlayer
        exoPlayer.addListener(playerListener)
    }

    fun detachPlayer() {
        player?.removeListener(playerListener)
        player = null
    }

    fun onSeekComplete() {
        if (isSyncing) return
        if (!isController) return
        broadcastSync("seek")
    }

    private fun broadcastSync(action: String) {
        val now = System.currentTimeMillis()
        if (now - lastSyncSentTime < SYNC_DEBOUNCE_MS) return
        lastSyncSentTime = now

        val p = player ?: return
        
        // Extract current video URI (MediaItem) if available
        val currentUri = if (p.mediaItemCount > 0) {
            p.currentMediaItem?.localConfiguration?.uri?.toString()
        } else null

        signalingClient.sendSync(
            action = action,
            position = p.currentPosition,
            speed = p.playbackParameters.speed,
            videoUrl = currentUri
        )
        Log.d(TAG, "Broadcast: $action pos=${p.currentPosition} speed=${p.playbackParameters.speed} url=$currentUri")
    }

    private fun broadcastCurrentState() {
        val p = player ?: return
        val action = if (p.isPlaying) "play" else "pause"
        
        val currentUri = if (p.mediaItemCount > 0) {
            p.currentMediaItem?.localConfiguration?.uri?.toString()
        } else null
            
        signalingClient.sendSync(action, p.currentPosition, p.playbackParameters.speed, currentUri)
    }

    fun applySyncState(state: SignalingClient.SyncState, isManual: Boolean = false) {
        val p = player ?: return

        handler.post {
            listener.onSyncStatusChanged("正在同步${state.fromNickname}的进度...")
            isSyncing = true

            try {
                // Check if we need to load a new video URL
                if (state.videoUrl.isNotEmpty()) {
                    val currentUri = if (p.mediaItemCount > 0) p.currentMediaItem?.localConfiguration?.uri?.toString() else ""
                    if (currentUri != state.videoUrl) {
                        Log.d(TAG, "Loading synced video URL: ${state.videoUrl}")
                        val mediaItem = androidx.media3.common.MediaItem.fromUri(state.videoUrl)
                        p.setMediaItem(mediaItem)
                        p.prepare()
                    }
                }

                // Apply speed
                if (p.playbackParameters.speed != state.speed) {
                    p.playbackParameters = androidx.media3.common.PlaybackParameters(state.speed)
                }

                // Apply position sync
                val targetPosition = state.position
                val currentPosition = p.currentPosition
                val diff = Math.abs(targetPosition - currentPosition)

                if (isManual) {
                    Log.d(TAG, "Manual calibration: jumping to $targetPosition")
                    p.seekTo(targetPosition)
                } else {
                    when {
                        diff < SMOOTH_THRESHOLD_MS -> {
                            // Small drift: do nothing or adjust speed
                        }
                        else -> {
                            // Large drift or medium: seek
                            p.seekTo(targetPosition)
                        }
                    }
                }

                // Apply play/pause
                when (state.action) {
                    "play", "speed" -> {
                        if (!p.isPlaying) p.play()
                    }
                    "pause" -> {
                        if (p.isPlaying) p.pause()
                    }
                    "seek" -> {
                        p.seekTo(targetPosition)
                    }
                }
            } finally {
                // Delay clearing the sync flag to prevent echo
                handler.postDelayed({
                    isSyncing = false
                    listener.onSyncComplete()
                }, 300)
            }
        }
    }

    fun calibrate() {
        signalingClient.requestState()
        // The resulting onSyncReceived will be handled as manual
        listener.onSyncStatusChanged("正在校准进度...")
    }

    fun destroy() {
        detachPlayer()
        handler.removeCallbacksAndMessages(null)
    }
}
