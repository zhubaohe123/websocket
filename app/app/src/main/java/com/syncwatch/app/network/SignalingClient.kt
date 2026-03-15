package com.syncwatch.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val listener: SignalingListener
) {
    companion object {
        private const val TAG = "SignalingClient"
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_INTERVAL_MS = 3000L
        private const val PING_INTERVAL_MS = 20000L
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var isConnected = false
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private var clientId: String? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pingJob: Job? = null

    interface SignalingListener {
        fun onConnected(clientId: String)
        fun onDisconnected()
        fun onReconnecting(attempt: Int, maxAttempts: Int)
        fun onRoomCreated(roomId: String, password: String, members: List<MemberInfo>)
        fun onRoomJoined(roomId: String, hostNickname: String, members: List<MemberInfo>, state: SyncState?)
        fun onMemberJoined(nickname: String, members: List<MemberInfo>)
        fun onMemberLeft(nickname: String, members: List<MemberInfo>)
        fun onRoomDissolved(message: String)
        fun onSyncReceived(sync: SyncState)
        fun onControlRequest(fromId: String, fromNickname: String)
        fun onControlChanged(controllerId: String, controllerNickname: String, members: List<MemberInfo>)
        fun onError(message: String)
        fun onInfo(message: String)
    }

    data class MemberInfo(
        val id: String,
        val nickname: String,
        val isHost: Boolean,
        val isController: Boolean
    )

    data class SyncState(
        val action: String = "pause",
        val position: Long = 0,
        val speed: Float = 1.0f,
        val videoUrl: String = "",
        val timestamp: Long = 0,
        val fromNickname: String = ""
    )

    fun connect() {
        shouldReconnect = true
        reconnectAttempts = 0
        doConnect()
    }

    private fun doConnect() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                isConnected = true
                reconnectAttempts = 0
                startPing()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                isConnected = false
                stopPing()
                listener.onDisconnected()
                attemptReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}")
                isConnected = false
                stopPing()
                listener.onDisconnected()
                attemptReconnect()
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return

            when (type) {
                "connected" -> {
                    clientId = json.get("clientId")?.asString
                    clientId?.let { listener.onConnected(it) }
                }
                "room_created" -> {
                    val roomId = json.get("roomId")?.asString ?: ""
                    val password = json.get("password")?.asString ?: ""
                    val members = parseMemberList(json)
                    listener.onRoomCreated(roomId, password, members)
                }
                "room_joined" -> {
                    val roomId = json.get("roomId")?.asString ?: ""
                    val hostNickname = json.get("hostNickname")?.asString ?: ""
                    val members = parseMemberList(json)
                    val state = parseState(json)
                    listener.onRoomJoined(roomId, hostNickname, members, state)
                }
                "member_joined" -> {
                    val nickname = json.get("nickname")?.asString ?: ""
                    val members = parseMemberList(json)
                    listener.onMemberJoined(nickname, members)
                }
                "member_left" -> {
                    val nickname = json.get("nickname")?.asString ?: ""
                    val members = parseMemberList(json)
                    listener.onMemberLeft(nickname, members)
                }
                "room_dissolved" -> {
                    val message = json.get("message")?.asString ?: "房间已解散"
                    listener.onRoomDissolved(message)
                }
                "sync" -> {
                    val videoUrl = json.get("videoUrl")?.asString ?: ""
                    Log.d(TAG, "Received sync: videoUrl length = ${videoUrl.length}")
                    Log.d(TAG, "Received sync: videoUrl = $videoUrl")
                    
                    val sync = SyncState(
                        action = json.get("action")?.asString ?: "pause",
                        position = json.get("position")?.asLong ?: 0,
                        speed = json.get("speed")?.asFloat ?: 1.0f,
                        videoUrl = videoUrl,
                        timestamp = json.get("timestamp")?.asLong ?: 0,
                        fromNickname = json.get("fromNickname")?.asString ?: ""
                    )
                    listener.onSyncReceived(sync)
                }
                "state_update" -> {
                    val state = parseState(json)
                    state?.let { listener.onSyncReceived(it) }
                }
                "control_request" -> {
                    val fromId = json.get("fromId")?.asString ?: ""
                    val fromNickname = json.get("fromNickname")?.asString ?: ""
                    listener.onControlRequest(fromId, fromNickname)
                }
                "control_changed" -> {
                    val controllerId = json.get("controllerId")?.asString ?: ""
                    val controllerNickname = json.get("controllerNickname")?.asString ?: ""
                    val members = parseMemberList(json)
                    listener.onControlChanged(controllerId, controllerNickname, members)
                }
                "error" -> {
                    val message = json.get("message")?.asString ?: "未知错误"
                    listener.onError(message)
                }
                "info" -> {
                    val message = json.get("message")?.asString ?: ""
                    listener.onInfo(message)
                }
                "pong" -> { /* heartbeat response */ }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}")
        }
    }

    private fun parseMemberList(json: JsonObject): List<MemberInfo> {
        val membersArray = json.getAsJsonArray("members") ?: return emptyList()
        return membersArray.map { elem ->
            val obj = elem.asJsonObject
            MemberInfo(
                id = obj.get("id")?.asString ?: "",
                nickname = obj.get("nickname")?.asString ?: "",
                isHost = obj.get("isHost")?.asBoolean ?: false,
                isController = obj.get("isController")?.asBoolean ?: false
            )
        }
    }

    private fun parseState(json: JsonObject): SyncState? {
        val stateObj = json.getAsJsonObject("state") ?: return null
        return SyncState(
            action = stateObj.get("action")?.asString ?: "pause",
            position = stateObj.get("position")?.asLong ?: 0,
            speed = stateObj.get("speed")?.asFloat ?: 1.0f,
            videoUrl = stateObj.get("videoUrl")?.asString ?: "",
            timestamp = stateObj.get("timestamp")?.asLong ?: 0
        )
    }

    private fun attemptReconnect() {
        if (!shouldReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return

        scope.launch {
            reconnectAttempts++
            listener.onReconnecting(reconnectAttempts, MAX_RECONNECT_ATTEMPTS)
            delay(RECONNECT_INTERVAL_MS)
            if (shouldReconnect) {
                doConnect()
            }
        }
    }

    private fun startPing() {
        pingJob = scope.launch {
            while (isActive && isConnected) {
                send(mapOf("type" to "ping"))
                delay(PING_INTERVAL_MS)
            }
        }
    }

    private fun stopPing() {
        pingJob?.cancel()
        pingJob = null
    }

    fun send(data: Map<String, Any?>) {
        val json = gson.toJson(data)
        webSocket?.send(json)
    }

    fun createRoom(nickname: String, videoUrl: String? = null) {
        val data = mutableMapOf<String, Any?>("type" to "create_room", "nickname" to nickname)
        if (!videoUrl.isNullOrEmpty()) {
            data["videoUrl"] = videoUrl
        }
        send(data)
    }

    fun joinRoom(roomId: String, password: String, nickname: String) {
        send(mapOf("type" to "join_room", "roomId" to roomId, "password" to password, "nickname" to nickname))
    }

    fun leaveRoom() {
        send(mapOf("type" to "leave_room"))
    }

    fun sendSync(action: String, position: Long, speed: Float, videoUrl: String? = null) {
        val data = mutableMapOf<String, Any?>(
            "type" to "sync",
            "action" to action,
            "position" to position,
            "speed" to speed,
            "timestamp" to System.currentTimeMillis()
        )
        if (!videoUrl.isNullOrEmpty()) {
            data["videoUrl"] = videoUrl
            Log.d(TAG, "sendSync: videoUrl length = ${videoUrl.length}")
            Log.d(TAG, "sendSync: videoUrl = $videoUrl")
        }
        send(data)
    }

    fun requestControl() {
        send(mapOf("type" to "control", "action" to "request"))
    }

    fun grantControl(targetId: String) {
        send(mapOf("type" to "control", "action" to "grant", "targetId" to targetId))
    }

    fun revokeControl() {
        send(mapOf("type" to "control", "action" to "revoke"))
    }

    fun requestState() {
        send(mapOf("type" to "request_state"))
    }

    fun disconnect() {
        shouldReconnect = false
        stopPing()
        scope.cancel()
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        isConnected = false
    }

    fun isConnected() = isConnected
    fun getClientId() = clientId
}
