package com.pcmobilelink.nearshare.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.pcmobilelink.nearshare.MainActivity
import com.pcmobilelink.nearshare.R
import com.pcmobilelink.nearshare.sound.TransferSoundPlayer
import com.pcmobilelink.nearshare.sound.TransferSoundResult
import com.pcmobilelink.nearshare.storage.PairedPcStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AndroidReceiveForegroundService : Service() {
    private val receiverExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var receiveServer: AndroidReceiveHttpServer? = null
    private var discoveryResponder: AndroidReceiveDiscoveryResponder? = null
    private var lastAlwaysOn: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "Receive service command action=${intent?.action ?: "null"} startId=$startId")
        when (intent?.action) {
            ACTION_START_MANUAL_RECEIVE -> startReceiveForeground(alwaysOn = false)
            ACTION_START_ALWAYS_ON_RECEIVE -> startReceiveForeground(alwaysOn = true)
            ACTION_STOP_RECEIVE -> stopReceiveForeground()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "Receive service destroying")
        stopServer()
        receiverExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startReceiveForeground(alwaysOn: Boolean) {
        lastAlwaysOn = alwaysOn
        Log.i(TAG, "Starting receive foreground service alwaysOn=$alwaysOn")
        val title = if (alwaysOn) {
            "NearShare ready to receive"
        } else {
            "NearShare manual receive is on"
        }
        val text = if (alwaysOn) {
            "Starting authenticated local receiver..."
        } else {
            "Starting manual receive mode..."
        }
        startForeground(NOTIFICATION_ID, buildNotification(title, text, ongoing = true))
        receiverExecutor.execute { ensureServerRunning(alwaysOn) }
    }

    private fun ensureServerRunning(alwaysOn: Boolean) {
        try {
            val existingEndpoint = AndroidReceiveEndpointRegistry.currentEndpoint()
            if (receiveServer != null && existingEndpoint != null) {
                Log.i(TAG, "Receive server already running at ${existingEndpoint.host}:${existingEndpoint.port}; ensuring discovery responder")
                ensureDiscoveryResponder(existingEndpoint)
                publishReady(alwaysOn, existingEndpoint)
                updateNotificationForEndpoint(alwaysOn, existingEndpoint)
                return
            }

            val certificate = AndroidReceiveCertificateStore(this).loadOrCreate()
            Log.i(TAG, "Loaded Android receive certificate fingerprint=${certificate.tlsCertificateSha256}")
            val pairedPcStore = PairedPcStore(this)
            Log.i(TAG, "Starting Android receive HTTP server. pairedPcCount=${pairedPcStore.loadAll().size}")
            val sessionManager = AndroidReceiveSessionManager(
                pairedPcLookup = { pcDeviceId ->
                    pairedPcStore.loadAll().firstOrNull { record ->
                        record.pcDeviceId.equals(pcDeviceId, ignoreCase = true)
                    }
                },
                storage = AndroidReceiveStorageResolver(this),
                tempDirectory = File(cacheDir, "pc-receive-sessions"),
                progressChanged = ::publishTransferProgress,
            )
            val server = AndroidReceiveHttpServer(
                certificate = certificate,
                sessionManager = sessionManager,
            )
            val endpoint = server.start()
            receiveServer = server
            AndroidReceiveEndpointRegistry.markRunning(endpoint)
            Log.i(TAG, "Android receive HTTP server started at ${endpoint.host}:${endpoint.port}")
            ensureDiscoveryResponder(endpoint)
            publishReady(alwaysOn, endpoint)
            updateNotificationForEndpoint(alwaysOn, endpoint)
        } catch (exception: Exception) {
            Log.e(TAG, "Could not start Android receive server", exception)
            publishStatus(STATUS_FAILED, "Could not start NearShare receiving: ${exception.message}")
            updateNotification("NearShare receiver stopped", exception.message ?: "Could not start receiver.", ongoing = false)
            stopServer()
        }
    }

    private fun stopReceiveForeground() {
        Log.i(TAG, "Stopping receive foreground service")
        publishStatus(STATUS_STOPPED, "NearShare receiving is off.")
        stopServer()
        stopForegroundAfterNotification()
        stopSelf()
    }

    private fun stopServer() {
        Log.i(TAG, "Stopping Android receive server and discovery responder")
        discoveryResponder?.close()
        discoveryResponder = null
        receiveServer?.close()
        receiveServer = null
        AndroidReceiveEndpointRegistry.markStopped()
    }

    private fun ensureDiscoveryResponder(endpoint: AndroidReceiveEndpointMetadata) {
        if (discoveryResponder != null) {
            return
        }

        runCatching {
            AndroidReceiveDiscoveryResponder(
                context = this,
                pairedPcStore = PairedPcStore(this),
                endpoint = endpoint,
            ).also { responder ->
                responder.start()
                discoveryResponder = responder
                Log.i(TAG, "Android receive discovery responder started at ${endpoint.host}:${endpoint.port}")
            }
        }.onFailure { exception ->
            Log.e(TAG, "Android receive discovery responder failed to start", exception)
        }
    }

    private fun publishReady(alwaysOn: Boolean, endpoint: AndroidReceiveEndpointMetadata) {
        val message = "Ready to receive from paired PCs."
        Log.i(TAG, "Publishing receive ready alwaysOn=$alwaysOn endpoint=${endpoint.host}:${endpoint.port}")
        sendBroadcast(
            Intent(ACTION_RECEIVE_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_RECEIVE_STATUS, if (alwaysOn) STATUS_ALWAYS_ON_READY else STATUS_MANUAL_READY)
                .putExtra(EXTRA_MESSAGE, message)
                .putExtra(EXTRA_ENDPOINT_HOST, endpoint.host)
                .putExtra(EXTRA_ENDPOINT_PORT, endpoint.port)
                .putExtra(EXTRA_TLS_CERTIFICATE_SHA256, endpoint.tlsCertificateSha256),
        )
    }

    private fun publishTransferProgress(progress: ReceiveTransferProgress) {
        Log.i(TAG, "Receive progress pcDeviceId=${progress.pcDeviceId} file=${progress.fileIndex}/${progress.totalFiles} receivedBytes=${progress.receivedBytes} totalBytes=${progress.totalBytes} percent=${progress.batchPercent} status=${progress.status}")
        if (progress.status == ReceiveTransferStatus.Completed && progress.fileIndex >= progress.totalFiles) {
            TransferSoundPlayer(this).play(TransferSoundResult.Success)
        }
        sendBroadcast(
            Intent(ACTION_RECEIVE_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_RECEIVE_STATUS, STATUS_PROGRESS)
                .putExtra(EXTRA_PC_DEVICE_ID, progress.pcDeviceId)
                .putExtra(EXTRA_PC_NAME, progress.pcName)
                .putExtra(EXTRA_FILE_NAME, progress.fileName)
                .putExtra(EXTRA_FILE_INDEX, progress.fileIndex)
                .putExtra(EXTRA_TOTAL_FILES, progress.totalFiles)
                .putExtra(EXTRA_RECEIVED_BYTES, progress.receivedBytes)
                .putExtra(EXTRA_TOTAL_BYTES, progress.totalBytes)
                .putExtra(EXTRA_BATCH_RECEIVED_BYTES, progress.batchReceivedBytes)
                .putExtra(EXTRA_BATCH_TOTAL_BYTES, progress.batchTotalBytes)
                .putExtra(EXTRA_PERCENT, progress.batchPercent)
                .putExtra(EXTRA_MESSAGE, "Receiving ${progress.fileName} from ${progress.pcName}"),
        )
        if (progress.status == ReceiveTransferStatus.Completed && progress.fileIndex >= progress.totalFiles) {
            updateNotification(
                title = "NearShare ready to receive",
                text = "Received ${progress.totalFiles} ${if (progress.totalFiles == 1) "file" else "files"} from ${progress.pcName}.",
                ongoing = true,
            )
        } else {
            updateNotification(
                title = "NearShare receiving file",
                text = "${progress.fileIndex} of ${progress.totalFiles}: ${progress.fileName} - ${progress.batchPercent}%",
                ongoing = true,
            )
        }
    }

    private fun updateNotificationForEndpoint(alwaysOn: Boolean, endpoint: AndroidReceiveEndpointMetadata) {
        updateNotification(
            title = if (alwaysOn) "NearShare ready to receive" else "NearShare manual receive is on",
            text = "Ready at ${endpoint.host}:${endpoint.port}",
            ongoing = true,
        )
    }

    private fun updateNotification(title: String, text: String, ongoing: Boolean) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(title, text, ongoing))
    }

    private fun buildNotification(title: String, text: String, ongoing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(ongoing)
            .addAction(R.mipmap.ic_launcher, "Stop receiving", stopIntent)
            .build()
    }

    private fun publishStatus(status: String, message: String) {
        sendBroadcast(
            Intent(ACTION_RECEIVE_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_RECEIVE_STATUS, status)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Receive from PC",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows when NearShare is ready to receive files from paired PCs."
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun stopForegroundAfterNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START_MANUAL_RECEIVE = "com.pcmobilelink.nearshare.receiver.START_MANUAL_RECEIVE"
        const val ACTION_START_ALWAYS_ON_RECEIVE = "com.pcmobilelink.nearshare.receiver.START_ALWAYS_ON_RECEIVE"
        const val ACTION_STOP_RECEIVE = "com.pcmobilelink.nearshare.receiver.STOP_RECEIVE"
        const val ACTION_RECEIVE_EVENT = "com.pcmobilelink.nearshare.receiver.RECEIVE_EVENT"
        const val EXTRA_RECEIVE_STATUS = "receiveStatus"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_ENDPOINT_HOST = "endpointHost"
        const val EXTRA_ENDPOINT_PORT = "endpointPort"
        const val EXTRA_TLS_CERTIFICATE_SHA256 = "tlsCertificateSha256"
        const val EXTRA_PC_DEVICE_ID = "pcDeviceId"
        const val EXTRA_PC_NAME = "pcName"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_FILE_INDEX = "fileIndex"
        const val EXTRA_TOTAL_FILES = "totalFiles"
        const val EXTRA_RECEIVED_BYTES = "receivedBytes"
        const val EXTRA_TOTAL_BYTES = "totalBytes"
        const val EXTRA_BATCH_RECEIVED_BYTES = "batchReceivedBytes"
        const val EXTRA_BATCH_TOTAL_BYTES = "batchTotalBytes"
        const val EXTRA_PERCENT = "percent"
        const val STATUS_MANUAL_READY = "manual_ready"
        const val STATUS_ALWAYS_ON_READY = "always_on_ready"
        const val STATUS_PROGRESS = "progress"
        const val STATUS_FAILED = "failed"
        const val STATUS_STOPPED = "stopped"

        private const val CHANNEL_ID = "nearshare_android_receive"
        private const val NOTIFICATION_ID = 1202
        private const val TAG = "NearShare"

        fun startManualIntent(packageContext: Context): Intent {
            return receiveIntent(packageContext, ACTION_START_MANUAL_RECEIVE)
        }

        fun startAlwaysOnIntent(packageContext: Context): Intent {
            return receiveIntent(packageContext, ACTION_START_ALWAYS_ON_RECEIVE)
        }

        fun stopIntent(packageContext: Context): Intent {
            return receiveIntent(packageContext, ACTION_STOP_RECEIVE)
        }

        fun startManual(context: Context) {
            startForegroundIntent(context, startManualIntent(context))
        }

        fun startAlwaysOn(context: Context) {
            startForegroundIntent(context, startAlwaysOnIntent(context))
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        private fun receiveIntent(packageContext: Context, action: String): Intent {
            return Intent(packageContext, AndroidReceiveForegroundService::class.java)
                .setPackage(packageContext.packageName)
                .setAction(action)
        }

        private fun startForegroundIntent(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
