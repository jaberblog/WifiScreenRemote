package com.example.wifiscreenremote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Point
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import fi.iki.elonen.NanoHTTPD
import kotlin.math.min

class StreamService : Service() {
    private var httpServer: LocalSignalingServer? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    @Volatile private var peerConnection: PeerConnection? = null
    private var captureWidth = 1280
    private var captureHeight = 720

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isRunning) return START_NOT_STICKY
                startAsForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val permissionData = getPermissionIntent(intent)
                if (resultCode != Activity.RESULT_OK || permissionData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                try {
                    startStreaming(permissionData)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Unable to start screen streaming", e)
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun getPermissionIntent(source: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) {
            source.getParcelableExtra(EXTRA_PERMISSION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(EXTRA_PERMISSION_DATA)
        }
    }

    private fun startAsForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screen sharing", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("اشتراک‌گذاری صفحه فعال است")
            .setContentText("برای باز کردن کنترل از راه دور، برنامه را لمس کنید.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "توقف", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStreaming(permissionData: Intent) {
        val dimensions = readScreenDimensions()
        val scale = min(1f, MAX_CAPTURE_DIMENSION.toFloat() / maxOf(dimensions.x, dimensions.y).toFloat())
        captureWidth = ((dimensions.x * scale).toInt().coerceAtLeast(2) / 2) * 2
        captureHeight = ((dimensions.y * scale).toInt().coerceAtLeast(2) / 2) * 2

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext)
                .createInitializationOptions()
        )

        eglBase = EglBase.create()
        val eglContext = eglBase!!.eglBaseContext
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglContext))
            .createPeerConnectionFactory()

        surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglContext)
            ?: error("Could not create WebRTC SurfaceTextureHelper")
        videoSource = peerConnectionFactory!!.createVideoSource(true)
        videoCapturer = org.webrtc.ScreenCapturerAndroid(
            permissionData,
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }
        )
        videoCapturer!!.initialize(surfaceTextureHelper!!, applicationContext, videoSource!!.capturerObserver)
        videoCapturer!!.startCapture(captureWidth, captureHeight, TARGET_FPS)
        videoTrack = peerConnectionFactory!!.createVideoTrack("screen-track", videoSource!!)
        videoTrack!!.setEnabled(true)

        httpServer = LocalSignalingServer(
            context = applicationContext,
            bindAddress = NetworkUtils.localIpv4Address() ?: "0.0.0.0",
            onOffer = ::acceptOffer,
            onTouch = { command -> RemoteControlAccessibilityService.current?.dispatchRemoteGesture(command) },
            onKey = { key -> RemoteControlAccessibilityService.current?.performRemoteKey(key) },
            answerProvider = { null },
            statusProvider = {
                JSONObject()
                    .put("running", isRunning)
                    .put("width", captureWidth)
                    .put("height", captureHeight)
                    .put("controlEnabled", RemoteControlAccessibilityService.current != null)
            }
        ).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }

        isRunning = true
        android.util.Log.i(TAG, "Local screen server started on port ${LocalSignalingServer.PORT}")
    }

    @Suppress("DEPRECATION")
    private fun readScreenDimensions(): Point {
        val metrics = DisplayMetrics()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        manager.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    private fun acceptOffer(sdp: String) {
        httpServer?.clearAnswer()
        peerConnection?.close()
        peerConnection = null

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }

        lateinit var createdConnection: PeerConnection
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE && peerConnection === createdConnection) {
                    val localDescription = createdConnection.localDescription
                    if (localDescription?.type == SessionDescription.Type.ANSWER) {
                        httpServer?.publishAnswer(localDescription.description)
                    }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate?) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(dataChannel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) = Unit
            override fun onTrack(transceiver: RtpTransceiver?) = Unit
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) = Unit
        }

        createdConnection = peerConnectionFactory?.createPeerConnection(configuration, observer)
            ?: run {
                android.util.Log.e(TAG, "createPeerConnection returned null")
                return
            }
        peerConnection = createdConnection

        val track = videoTrack
        if (track != null) {
            val sender = createdConnection.addTrack(track, listOf("screen-stream"))
            runCatching {
                val parameters = sender.parameters
                parameters.encodings?.forEach { encoding ->
                    encoding.maxBitrateBps = MAX_VIDEO_BITRATE_BPS
                    encoding.maxFramerate = TARGET_FPS
                }
                sender.setParameters(parameters)
            }.onFailure { android.util.Log.w(TAG, "Could not set custom WebRTC bitrate", it) }
        }

        val remoteOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
        createdConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription?) = Unit
            override fun onSetSuccess() {
                createdConnection.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(description: SessionDescription?) {
                        if (description == null || peerConnection !== createdConnection) return
                        createdConnection.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(description: SessionDescription?) = Unit
                            override fun onSetSuccess() {
                                if (createdConnection.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
                                    val local = createdConnection.localDescription
                                    if (local?.type == SessionDescription.Type.ANSWER) {
                                        httpServer?.publishAnswer(local.description)
                                    }
                                }
                            }
                            override fun onCreateFailure(error: String?) {
                                android.util.Log.e(TAG, "setLocalDescription create failure: $error")
                            }
                            override fun onSetFailure(error: String?) {
                                android.util.Log.e(TAG, "setLocalDescription failed: $error")
                            }
                        }, description)
                    }
                    override fun onSetSuccess() = Unit
                    override fun onCreateFailure(error: String?) {
                        android.util.Log.e(TAG, "createAnswer failed: $error")
                    }
                    override fun onSetFailure(error: String?) = Unit
                }, MediaConstraints())
            }
            override fun onCreateFailure(error: String?) = Unit
            override fun onSetFailure(error: String?) {
                android.util.Log.e(TAG, "setRemoteDescription failed: $error")
            }
        }, remoteOffer)
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { httpServer?.stop() }
        httpServer = null
        peerConnection?.close()
        peerConnection = null
        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null
        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        eglBase?.release()
        eglBase = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.wifiscreenremote.START"
        const val ACTION_STOP = "com.example.wifiscreenremote.STOP"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_PERMISSION_DATA = "projection_permission_data"
        @Volatile var isRunning: Boolean = false
            private set

        private const val TAG = "WifiScreenRemote"
        private const val CHANNEL_ID = "screen_share_channel"
        private const val NOTIFICATION_ID = 7301
        private const val MAX_CAPTURE_DIMENSION = 1920
        private const val TARGET_FPS = 30
        private const val MAX_VIDEO_BITRATE_BPS = 8_000_000
    }
}
