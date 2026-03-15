package com.syncwatch.app.dlna

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.syncwatch.app.PlayerActivity
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DLNA/UPnP receiver service that makes this device discoverable
 * by video apps (Bilibili, Tencent Video, iQiyi, Youku, etc.)
 */
class DlnaReceiverService : Service() {

    companion object {
        private const val TAG = "DlnaReceiver"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "dlna_service"
        private const val HTTP_PORT = 49152
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val DEVICE_NAME = "SyncWatch"

        var currentVideoUrl: String? = null
            private set
        var onVideoReceived: ((String) -> Unit)? = null
        
        @Volatile
        private var isServiceRunning = false

        fun start(context: Context) {
            if (isServiceRunning) {
                Log.w(TAG, "Service already running, skipping start")
                return
            }
            val intent = Intent(context, DlnaReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            Log.d(TAG, "Stopping service...")
            isServiceRunning = false
            try {
                context.stopService(Intent(context, DlnaReceiverService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service", e)
            }
        }
    }

    private var httpServer: DlnaHttpServer? = null
    private var ssdpThread: Thread? = null
    private var ssdpResponderThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var deviceUuid = "uuid:syncwatch-${System.currentTimeMillis()}"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        // 返回 START_NOT_STICKY，确保服务被杀死后不会自动重启
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate() called")
        
        if (isServiceRunning) {
            Log.w(TAG, "Service already running, stopping duplicate")
            stopSelf()
            return
        }
        
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            isServiceRunning = true
            
            acquireMulticastLock()
            startHttpServer()
            startSsdpAdvertise()
            
            Log.d(TAG, "Service started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start DLNA service", e)
            isServiceRunning = false
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy() called")
        isRunning.set(false)
        isServiceRunning = false
        
        // 立即停止所有线程
        try {
            ssdpThread?.interrupt()
            ssdpThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SSDP thread", e)
        }
        
        try {
            ssdpResponderThread?.interrupt()
            ssdpResponderThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping SSDP responder thread", e)
        }
        
        // 停止 HTTP 服务器
        try {
            httpServer?.stop()
            httpServer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping HTTP server", e)
        }
        
        // 释放 multicast lock
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
            multicastLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing multicast lock", e)
        }
        
        // 清理回调
        onVideoReceived = null
        currentVideoUrl = null
        
        Log.d(TAG, "Service destroyed successfully")
        super.onDestroy()
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("syncwatch_dlna")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()
            Log.d(TAG, "Multicast lock acquired successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
            throw e
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get IP", e)
        }
        return "0.0.0.0"
    }

    // ==================== HTTP Server (UPnP) ====================
    private fun startHttpServer() {
        val ip = getLocalIpAddress()
        
        if (ip == "0.0.0.0") {
            Log.e(TAG, "Cannot start HTTP server: no valid IP address")
            throw IllegalStateException("No valid IP address found")
        }
        
        try {
            httpServer = DlnaHttpServer(ip, HTTP_PORT)
            httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "HTTP server started on $ip:$HTTP_PORT")
        } catch (e: java.net.BindException) {
            Log.e(TAG, "Port $HTTP_PORT already in use, trying alternative port", e)
            try {
                // 尝试使用备用端口
                httpServer = DlnaHttpServer(ip, 0) // 0 = 自动分配端口
                httpServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d(TAG, "HTTP server started on $ip with auto-assigned port")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to start HTTP server on any port", e2)
                throw e2
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start HTTP server", e)
            throw e
        }
    }

    inner class DlnaHttpServer(private val ip: String, port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            Log.d(TAG, "HTTP request: ${session.method} $uri")

            return when {
                uri.contains("device.xml") || uri == "/description.xml" -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getDeviceDescXml(ip))
                }
                uri.contains("AVTransport") && session.method == Method.GET -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getAvTransportScpdXml())
                }
                uri.contains("RenderingControl") && session.method == Method.GET -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getRenderingControlScpdXml())
                }
                uri.contains("ConnectionManager") && session.method == Method.GET -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getConnectionManagerScpdXml())
                }
                uri.contains("control") || session.method == Method.POST -> {
                    handleSoapAction(session)
                }
                session.method.name == "SUBSCRIBE" || session.method.name == "UNSUBSCRIBE" -> {
                    val resp = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
                    resp.addHeader("SID", "uuid:${System.currentTimeMillis()}")
                    resp.addHeader("TIMEOUT", "Second-3600")
                    resp
                }
                else -> {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
                }
            }
        }

        private fun handleSoapAction(session: IHTTPSession): Response {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val body = HashMap<String, String>()
            try {
                session.parseBody(body)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse SOAP body", e)
            }

            val postData = body["postData"] ?: ""
            val soapAction = session.headers["soapaction"] ?: ""
            Log.d(TAG, "SOAP Action: $soapAction")
            Log.d(TAG, "Body: $postData")

            return when {
                soapAction.contains("SetAVTransportURI") -> {
                    var videoUrl = extractTagValue(postData, "CurrentURI")
                    if (videoUrl.isNullOrEmpty() || !videoUrl.startsWith("http")) {
                        // Fallback: Check CurrentURIMetaData for <res> tag
                        val metaData = extractTagValue(postData, "CurrentURIMetaData")
                        if (!metaData.isNullOrEmpty()) {
                            // Unescape basic XML entities manually
                            val unescaped = metaData
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .replace("&quot;", "\"")
                                .replace("&amp;", "&")
                                .replace("&#039;", "'")
                                
                            videoUrl = extractTagValue(unescaped, "res")
                        }
                    }
                    
                    if (videoUrl != null && videoUrl.startsWith("http")) {
                        // Fully unescape the URL payload (many apps send URLs with &amp;)
                        val cleanUrl = videoUrl
                            .replace("&amp;", "&")
                            .replace("&lt;", "<")
                            .replace("&gt;", ">")
                            .replace("&quot;", "\"")
                            .replace("&#039;", "'")
                            
                        Log.d(TAG, "Successfully extracted & cleaned video URL: $cleanUrl")
                        currentVideoUrl = cleanUrl
                        onVideoReceived?.invoke(cleanUrl)
                    } else {
                        Log.w(TAG, "Failed to extract valid HTTP URL from SetAVTransportURI. Raw postData:\n$postData")
                    }
                    newFixedLengthResponse(Response.Status.OK, "text/xml", soapResponse("SetAVTransportURI"))
                }
                soapAction.contains("Play") -> {
                    // Trigger play on current video
                    newFixedLengthResponse(Response.Status.OK, "text/xml", soapResponse("Play"))
                }
                soapAction.contains("Pause") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", soapResponse("Pause"))
                }
                soapAction.contains("Stop") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", soapResponse("Stop"))
                }
                soapAction.contains("Seek") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", soapResponse("Seek"))
                }
                soapAction.contains("GetTransportInfo") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getTransportInfoResponse())
                }
                soapAction.contains("GetPositionInfo") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getPositionInfoResponse())
                }
                soapAction.contains("GetVolume") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getVolumeResponse())
                }
                soapAction.contains("SetVolume") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", soapResponse("SetVolume"))
                }
                soapAction.contains("GetProtocolInfo") -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", getProtocolInfoResponse())
                }
                else -> {
                    newFixedLengthResponse(Response.Status.OK, "text/xml", soapResponse("Unknown"))
                }
            }
        }
    }

    private fun extractTagValue(xml: String, tagName: String): String? {
        val patterns = listOf(
            "<$tagName>(.*?)</$tagName>",
            "<$tagName[^>]*>(.*?)</$tagName>",
            "$tagName=\"([^\"]*)\""
        )
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(xml)
            if (match != null) {
                // Return just the string value inside the tag
                val value = match.groupValues[1].trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    // ==================== SSDP Discovery ====================
    private fun startSsdpAdvertise() {
        isRunning.set(true)
        val ip = getLocalIpAddress()
        val location = "http://$ip:$HTTP_PORT/device.xml"

        // SSDP Notify thread (advertise periodically)
        ssdpThread = Thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                val group = InetAddress.getByName(SSDP_ADDRESS)
                val notifyMessages = listOf(
                    buildNotifyMessage("upnp:rootdevice", location),
                    buildNotifyMessage(deviceUuid, location),
                    buildNotifyMessage("urn:schemas-upnp-org:device:MediaRenderer:1", location),
                    buildNotifyMessage("urn:schemas-upnp-org:service:AVTransport:1", location),
                    buildNotifyMessage("urn:schemas-upnp-org:service:RenderingControl:1", location)
                )

                while (isRunning.get()) {
                    try {
                        for (msg in notifyMessages) {
                            val data = msg.toByteArray()
                            val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                            socket?.send(packet)
                        }
                        Thread.sleep(5000)
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: java.net.SocketException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Socket error in SSDP notify", e)
                            break
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error sending SSDP notify", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "SSDP notify initialization error", e)
                }
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }.also { it.isDaemon = true; it.start() }

        // SSDP Response thread (respond to M-SEARCH)
        ssdpResponderThread = Thread {
            var multicastSocket: MulticastSocket? = null
            try {
                multicastSocket = MulticastSocket(SSDP_PORT)
                val group = InetAddress.getByName(SSDP_ADDRESS)
                multicastSocket.joinGroup(group)
                multicastSocket.soTimeout = 5000 // 5 second timeout to allow graceful shutdown

                val buf = ByteArray(4096)
                while (isRunning.get()) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        multicastSocket.receive(packet)
                        val message = String(packet.data, 0, packet.length)

                        if (message.contains("M-SEARCH") && (
                                    message.contains("ssdp:all") ||
                                    message.contains("MediaRenderer") ||
                                    message.contains("AVTransport") ||
                                    message.contains("upnp:rootdevice")
                                )) {
                            Log.d(TAG, "Received M-SEARCH from ${packet.address}:${packet.port}")
                            val response = buildSearchResponse(location)
                            val responseData = response.toByteArray()
                            val responsePacket = DatagramPacket(
                                responseData, responseData.size,
                                packet.address, packet.port
                            )
                            try {
                                val responseSocket = DatagramSocket()
                                responseSocket.send(responsePacket)
                                responseSocket.close()
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to send SSDP response", e)
                            }
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Timeout is expected, continue loop
                        continue
                    } catch (e: java.net.SocketException) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Socket error in SSDP responder", e)
                            break
                        }
                    } catch (e: Exception) {
                        if (isRunning.get()) {
                            Log.e(TAG, "Error processing SSDP packet", e)
                        }
                    }
                }
                try {
                    multicastSocket.leaveGroup(group)
                    multicastSocket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing multicast socket", e)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "SSDP responder initialization error", e)
                }
            } finally {
                try {
                    multicastSocket?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ==================== SSDP Messages ====================
    private fun buildNotifyMessage(nt: String, location: String): String {
        return "NOTIFY * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: $location\r\n" +
                "NT: $nt\r\n" +
                "NTS: ssdp:alive\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 SyncWatch/1.0\r\n" +
                "USN: $deviceUuid::$nt\r\n\r\n"
    }

    private fun buildSearchResponse(location: String): String {
        return "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: $location\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "USN: $deviceUuid::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "SERVER: Android/1.0 UPnP/1.0 SyncWatch/1.0\r\n" +
                "EXT:\r\n\r\n"
    }

    // ==================== UPnP XML Descriptors ====================
    private fun getDeviceDescXml(ip: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$DEVICE_NAME</friendlyName>
    <manufacturer>SyncWatch</manufacturer>
    <manufacturerURL>https://syncwatch.app</manufacturerURL>
    <modelDescription>SyncWatch Media Renderer</modelDescription>
    <modelName>SyncWatch</modelName>
    <modelNumber>1.0</modelNumber>
    <UDN>$deviceUuid</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/AVTransport/scpd.xml</SCPDURL>
        <controlURL>/AVTransport/control</controlURL>
        <eventSubURL>/AVTransport/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/RenderingControl/scpd.xml</SCPDURL>
        <controlURL>/RenderingControl/control</controlURL>
        <eventSubURL>/RenderingControl/event</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/ConnectionManager/scpd.xml</SCPDURL>
        <controlURL>/ConnectionManager/control</controlURL>
        <eventSubURL>/ConnectionManager/event</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    private fun getAvTransportScpdXml(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Play</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Pause</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Stop</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>Seek</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
        <argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetTransportInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument>
        <argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument>
        <argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>GetPositionInfo</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument>
        <argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument>
        <argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument>
        <argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument>
        <argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument>
        <argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument>
        <argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportPlaySpeed</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>TransportStatus</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
    }

    private fun getRenderingControlScpdXml(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action><name>SetVolume</name>
      <argumentList>
        <argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument>
        <argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument>
        <argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>Volume</name><dataType>ui2</dataType><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
  </serviceStateTable>
</scpd>"""
    }

    private fun getConnectionManagerScpdXml(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
    }

    // ==================== SOAP Responses ====================
    private fun soapResponse(actionName: String): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${actionName}Response xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
  </s:Body>
</s:Envelope>"""
    }

    private fun getTransportInfoResponse(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <CurrentTransportState>PLAYING</CurrentTransportState>
      <CurrentTransportStatus>OK</CurrentTransportStatus>
      <CurrentSpeed>1</CurrentSpeed>
    </u:GetTransportInfoResponse>
  </s:Body>
</s:Envelope>"""
    }

    private fun getPositionInfoResponse(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <Track>1</Track>
      <TrackDuration>00:00:00</TrackDuration>
      <TrackURI>${currentVideoUrl ?: ""}</TrackURI>
      <RelTime>00:00:00</RelTime>
      <AbsTime>00:00:00</AbsTime>
      <RelCount>0</RelCount>
      <AbsCount>0</AbsCount>
    </u:GetPositionInfoResponse>
  </s:Body>
</s:Envelope>"""
    }

    private fun getVolumeResponse(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
      <CurrentVolume>50</CurrentVolume>
    </u:GetVolumeResponse>
  </s:Body>
</s:Envelope>"""
    }

    private fun getProtocolInfoResponse(): String {
        return """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
      <Source></Source>
      <Sink>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,http-get:*:video/avi:*,http-get:*:video/x-flv:*,http-get:*:audio/mp3:*,http-get:*:audio/mpeg:*</Sink>
    </u:GetProtocolInfoResponse>
  </s:Body>
</s:Envelope>"""
    }

    // ==================== Notification ====================
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DLNA投屏服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "DLNA投屏接收服务运行中"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("SyncWatch 投屏服务")
            .setContentText("等待投屏连接...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }
}
