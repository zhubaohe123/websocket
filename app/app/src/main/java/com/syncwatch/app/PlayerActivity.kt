package com.syncwatch.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import com.syncwatch.app.network.SignalingClient
import com.syncwatch.app.sync.SyncEngine
import androidx.activity.OnBackPressedCallback
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity(), SignalingClient.SignalingListener, SyncEngine.SyncListener {

    companion object {
        private const val TAG = "PlayerActivity"
    }

    private lateinit var playerView: PlayerView
    private lateinit var tvRoomInfo: TextView
    private lateinit var tvSyncStatus: TextView
    private lateinit var tvMemberCount: TextView
    private lateinit var tvConnectionStatus: TextView
    private lateinit var btnCalibrate: Button
    private lateinit var btnRequestControl: Button
    private lateinit var btnRevokeControl: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnLeaveRoom: Button
    private lateinit var layoutRoomBar: LinearLayout
    private lateinit var layoutBottomBar: LinearLayout
    private lateinit var layoutSyncOverlay: FrameLayout

    private var player: ExoPlayer? = null
    private var signalingClient: SignalingClient? = null
    private var syncEngine: SyncEngine? = null
    private var pendingJoinAction: (() -> Unit)? = null

    private var isHost = false
    private var isController = false
    private var clientId: String? = null
    private var currentRoomId: String? = null
    private var currentSpeedIndex = 3
    private val speeds = floatArrayOf(0.5f, 0.75f, 0.8f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)
    private val speedLabels = arrayOf("0.5x", "0.75x", "0.8x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x")

    private val handler = Handler(Looper.getMainLooper())
    private var isCalibrating = false
    
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var dataSourceFactory: OkHttpDataSource.Factory

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setContentView(R.layout.activity_player)

            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    confirmLeaveRoom()
                }
            })

            initViews()
            initPlayer()
            parseIntentAndConnect()
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }


    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        tvRoomInfo = findViewById(R.id.tv_room_info)
        tvSyncStatus = findViewById(R.id.tv_sync_status)
        tvMemberCount = findViewById(R.id.tv_member_count)
        tvConnectionStatus = findViewById(R.id.tv_connection_status)
        btnCalibrate = findViewById(R.id.btn_calibrate)
        btnRequestControl = findViewById(R.id.btn_request_control)
        btnRevokeControl = findViewById(R.id.btn_revoke_control)
        btnSpeed = findViewById(R.id.btn_speed)
        btnLeaveRoom = findViewById(R.id.btn_leave_room)
        layoutRoomBar = findViewById(R.id.layout_room_bar)
        layoutBottomBar = findViewById(R.id.layout_bottom_bar)
        layoutSyncOverlay = findViewById(R.id.layout_sync_overlay)

        layoutRoomBar.visibility = View.GONE
        layoutSyncOverlay.visibility = View.GONE

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            layoutRoomBar.visibility = if (visibility == View.VISIBLE && currentRoomId != null) View.VISIBLE else View.GONE
            layoutBottomBar.visibility = visibility
        })

        btnCalibrate.setOnClickListener {
            isCalibrating = true
            syncEngine?.calibrate()
        }

        btnRequestControl.setOnClickListener {
            signalingClient?.requestControl()
        }

        btnRevokeControl.setOnClickListener {
            signalingClient?.revokeControl()
        }

        btnSpeed.setOnClickListener {
            showSpeedDialog()
        }

        btnLeaveRoom.setOnClickListener {
            confirmLeaveRoom()
        }
    }

    private fun confirmLeaveRoom() {
        AlertDialog.Builder(this)
            .setTitle("确认离开")
            .setMessage("确定要离开观影房间吗？")
            .setPositiveButton("离开") { _, _ ->
                signalingClient?.leaveRoom()
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun initPlayer() {
        try {
            val defaultHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Accept" to "*/*",
                "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
                "Accept-Encoding" to "identity"
            )

            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()

            dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                .setDefaultRequestProperties(defaultHeaders)

            player = ExoPlayer.Builder(this).build()
            
            if (player == null) {
                throw IllegalStateException("Failed to create ExoPlayer instance")
            }
            
            playerView.player = player

            player?.addListener(object : Player.Listener {
                private var wasSeekProcessed = false

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    val videoUrl = player?.currentMediaItem?.localConfiguration?.uri.toString()
                    val isAuthKeyUrl = videoUrl.contains("auth_key", ignoreCase = true)
                    val is402Error = error.message?.contains("402") == true || 
                                    error.cause?.message?.contains("402") == true
                    
                    val errorMsg = buildString {
                        append("播放错误: ${error.errorCodeName}\n")
                        append("类型: ${getErrorTypeName(error.errorCode)}\n")
                        error.message?.let { append("消息: $it\n") }
                        error.cause?.message?.let { append("原因: $it\n") }
                        
                        if (is402Error && isAuthKeyUrl) {
                            append("\n⚠️ CDN授权失败\n")
                            append("视频URL包含auth_key参数，但CDN拒绝访问。\n")
                            append("可能原因：\n")
                            append("• auth_key已过期（通常5-30分钟）\n")
                            append("• auth_key绑定了主机的IP地址\n")
                            append("• auth_key仅限原设备使用\n\n")
                            append("建议：使用公开视频URL测试同步功能")
                        }
                    }
                    
                    Log.e(TAG, "========== PLAYBACK ERROR ==========")
                    Log.e(TAG, errorMsg, error)
                    Log.e(TAG, "Current media item: $videoUrl")
                    Log.e(TAG, "Is auth_key URL: $isAuthKeyUrl")
                    Log.e(TAG, "Is 402 error: $is402Error")
                    Log.e(TAG, "====================================")
                    
                    runOnUiThread {
                        Toast.makeText(this@PlayerActivity, errorMsg, Toast.LENGTH_LONG).show()
                        tvRoomInfo.text = "播放失败: ${error.errorCodeName}"
                        
                        AlertDialog.Builder(this@PlayerActivity)
                            .setTitle(if (is402Error && isAuthKeyUrl) "CDN授权失败" else "播放错误")
                            .setMessage(errorMsg + "\n\n视频URL:\n$videoUrl")
                            .setPositiveButton("复制URL") { _, _ ->
                                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("video_url", videoUrl)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@PlayerActivity, "URL已复制", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("关闭", null)
                            .show()
                    }
                }
                
                private fun getErrorTypeName(errorCode: Int): String {
                    return when (errorCode) {
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络超时"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE -> "无效的内容类型"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP错误状态"
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "文件未找到"
                        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "视频格式错误"
                        androidx.media3.common.PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "清单文件错误"
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "解码器初始化失败"
                        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> "解码器查询失败"
                        else -> "未知错误($errorCode)"
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && wasSeekProcessed) {
                        wasSeekProcessed = false
                        syncEngine?.onSeekComplete()
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        wasSeekProcessed = true
                    }
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isController && currentRoomId != null) {
                        Toast.makeText(this@PlayerActivity, "当前不是你的控制权，操作无效", Toast.LENGTH_SHORT).show()
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize player", e)
            Toast.makeText(this, "播放器初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            throw e
        }
    }

    private fun parseIntentAndConnect() {
        isHost = intent.getBooleanExtra("is_host", false)
        val serverUrl = intent.getStringExtra("server_url") ?: ""
        val nickname = intent.getStringExtra("nickname") ?: "用户"
        val createRoom = intent.getBooleanExtra("create_room", false)
        val roomId = intent.getStringExtra("room_id")
        val password = intent.getStringExtra("password")
        
        loadNewVideoFromIntent(intent)

        if (serverUrl.isNotEmpty()) {
            try {
                signalingClient = SignalingClient(serverUrl, this)
                syncEngine = SyncEngine(signalingClient!!, this)
                player?.let { syncEngine?.attachPlayer(it) }
                syncEngine?.isHost = isHost
                syncEngine?.isController = isHost

                signalingClient?.connect()

                pendingJoinAction = {
                    if (createRoom) {
                        signalingClient?.createRoom(nickname, intent.getStringExtra("video_uri"))
                    } else if (!roomId.isNullOrEmpty() && !password.isNullOrEmpty()) {
                        signalingClient?.joinRoom(roomId, password, nickname)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: $serverUrl", e)
                Toast.makeText(this, "连接服务器失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, "未配置服务器地址", Toast.LENGTH_SHORT).show()
        }

        updateControlUI()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadNewVideoFromIntent(intent)
    }

    private fun loadNewVideoFromIntent(intent: Intent) {
        val videoUri = intent.getStringExtra("video_uri")
        if (!videoUri.isNullOrEmpty()) {
            loadVideo(videoUri)
        }
    }
    
    private fun loadVideo(videoUri: String) {
        try {
            Log.d(TAG, "========== LOADING VIDEO ==========")
            Log.d(TAG, "Video URI: $videoUri")
            Log.d(TAG, "URI length: ${videoUri.length}")
            Log.d(TAG, "URI scheme: ${Uri.parse(videoUri).scheme}")
            Log.d(TAG, "===================================")
            
            val mediaSource = createMediaSource(videoUri)
            
            player?.setMediaSource(mediaSource)
            player?.prepare()
            player?.play()
            
            if (isController) {
                syncEngine?.onSeekComplete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load video: $videoUri", e)
            Toast.makeText(this, "视频加载失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun createMediaSource(videoUri: String): MediaSource {
        val uri = Uri.parse(videoUri)
        
        return when {
            videoUri.contains(".m3u8", ignoreCase = true) -> {
                Log.d(TAG, "Creating HLS media source")
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            videoUri.contains(".mpd", ignoreCase = true) -> {
                Log.d(TAG, "Creating DASH media source")
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            videoUri.contains(".m3u", ignoreCase = true) -> {
                Log.d(TAG, "Creating HLS media source (m3u)")
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
            else -> {
                Log.d(TAG, "Creating progressive media source")
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(uri))
            }
        }
    }

    private fun showSpeedDialog() {
        AlertDialog.Builder(this)
            .setTitle("选择播放倍速")
            .setItems(speedLabels) { _, which ->
                currentSpeedIndex = which
                player?.playbackParameters = PlaybackParameters(speeds[which])
                btnSpeed.text = speedLabels[which]
            }
            .show()
    }

    private fun updateControlUI() {
        runOnUiThread {
            if (isHost) {
                btnRequestControl.visibility = View.GONE
                btnRevokeControl.visibility = if (isController) View.GONE else View.VISIBLE
                btnCalibrate.visibility = View.GONE
            } else {
                btnRequestControl.visibility = if (!isController) View.VISIBLE else View.GONE
                btnRevokeControl.visibility = View.GONE
                btnCalibrate.visibility = View.VISIBLE
            }
        }
    }


    override fun onConnected(clientId: String) {
        this.clientId = clientId
        runOnUiThread {
            tvConnectionStatus.text = "已连接"
            tvConnectionStatus.setTextColor(0xFF4CAF50.toInt())
            pendingJoinAction?.invoke()
            pendingJoinAction = null
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            tvConnectionStatus.text = "已断开"
            tvConnectionStatus.setTextColor(0xFFF44336.toInt())
        }
    }

    override fun onReconnecting(attempt: Int, maxAttempts: Int) {
        runOnUiThread {
            tvConnectionStatus.text = "重连中($attempt/$maxAttempts)..."
            tvConnectionStatus.setTextColor(0xFFFF9800.toInt())
            Toast.makeText(this, "网络波动，正在重连...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRoomCreated(roomId: String, password: String, members: List<SignalingClient.MemberInfo>) {
        currentRoomId = roomId
        MainActivity.lastRoomId = roomId
        MainActivity.lastPassword = password
        runOnUiThread {
            layoutRoomBar.visibility = View.VISIBLE
            tvRoomInfo.text = "房间: $roomId | 密码: $password"
            tvMemberCount.text = "人数: ${members.size}"

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("room_info", "房间号: $roomId\n密码: $password")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "房间已创建，信息已复制到剪贴板", Toast.LENGTH_LONG).show()
        }
    }

    override fun onRoomJoined(roomId: String, hostNickname: String, members: List<SignalingClient.MemberInfo>, state: SignalingClient.SyncState?) {
        currentRoomId = roomId
        runOnUiThread {
            layoutRoomBar.visibility = View.VISIBLE
            tvRoomInfo.text = "房间: $roomId | 主机: $hostNickname"
            tvMemberCount.text = "人数: ${members.size}"
            Toast.makeText(this, "已加入房间", Toast.LENGTH_SHORT).show()

            state?.let { syncEngine?.applySyncState(it, isManual = true) }
        }
    }

    override fun onMemberJoined(nickname: String, members: List<SignalingClient.MemberInfo>) {
        runOnUiThread {
            tvMemberCount.text = "人数: ${members.size}"
            Toast.makeText(this, "${nickname} 加入房间", Toast.LENGTH_SHORT).show()

            if (isHost && isController) {
                player?.let {
                    val currentUri = if (it.mediaItemCount > 0) it.currentMediaItem?.localConfiguration?.uri?.toString() else null
                    signalingClient?.sendSync(
                        action = if (it.isPlaying) "play" else "pause",
                        position = it.currentPosition,
                        speed = it.playbackParameters.speed,
                        videoUrl = currentUri
                    )
                }
            }
        }
    }

    override fun onMemberLeft(nickname: String, members: List<SignalingClient.MemberInfo>) {
        runOnUiThread {
            tvMemberCount.text = "人数: ${members.size}"
            Toast.makeText(this, "${nickname} 离开房间", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRoomDissolved(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            layoutRoomBar.visibility = View.GONE
            currentRoomId = null
        }
    }

    override fun onSyncReceived(sync: SignalingClient.SyncState) {
        if (!isController || isCalibrating) {
            syncEngine?.applySyncState(sync, isManual = isCalibrating)
            isCalibrating = false
        }
    }

    override fun onControlRequest(fromId: String, fromNickname: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("控制权请求")
                .setMessage("${fromNickname} 请求控制权")
                .setPositiveButton("同意") { _, _ ->
                    signalingClient?.grantControl(fromId)
                }
                .setNegativeButton("拒绝", null)
                .show()
        }
    }

    override fun onControlChanged(controllerId: String, controllerNickname: String, members: List<SignalingClient.MemberInfo>) {
        isController = controllerId == clientId
        syncEngine?.isController = isController
        runOnUiThread {
            updateControlUI()
            val msg = if (isController) "你获得了控制权" else "${controllerNickname} 获得了控制权"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onError(message: String) {
        runOnUiThread {
            Toast.makeText(this, "错误: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onInfo(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSyncStatusChanged(message: String) {
        runOnUiThread {
            tvSyncStatus.text = message
            layoutSyncOverlay.visibility = View.VISIBLE
        }
    }

    override fun onSyncComplete() {
        runOnUiThread {
            handler.postDelayed({
                layoutSyncOverlay.visibility = View.GONE
            }, 800)
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy called")
        try {
            syncEngine?.destroy()
            signalingClient?.disconnect()
            player?.release()
            player = null
            handler.removeCallbacksAndMessages(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy", e)
        }
        super.onDestroy()
    }
    
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged called")
    }
}
