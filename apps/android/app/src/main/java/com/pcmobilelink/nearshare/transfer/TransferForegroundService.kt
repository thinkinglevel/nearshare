package com.pcmobilelink.nearshare.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.pcmobilelink.nearshare.R
import com.pcmobilelink.nearshare.diagnostics.NearShareDiagnostics
import com.pcmobilelink.nearshare.discovery.PairedPcEndpointResolver
import com.pcmobilelink.nearshare.pairing.PairingErrorMessage
import com.pcmobilelink.nearshare.sound.TransferSoundPlayer
import com.pcmobilelink.nearshare.sound.TransferSoundResult
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import com.pcmobilelink.nearshare.storage.PairedPcStore
import java.io.File
import java.util.concurrent.CancellationException

class TransferForegroundService : Service() {
    private lateinit var pairedPcStore: PairedPcStore
    private lateinit var activeTransferStore: ActiveTransferManifestStore
    private lateinit var endpointResolver: PairedPcEndpointResolver
    private val transferClient = AndroidFileTransferClient()
    private val stateLock = Any()
    private var activeBatchId: String? = null
    private var activeControl: FileTransferControl? = null
    private var activeThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        pairedPcStore = PairedPcStore(this)
        activeTransferStore = ActiveTransferManifestStore(File(filesDir, "active-transfers.json"))
        endpointResolver = PairedPcEndpointResolver(
            discoverAndroidReceiveCandidates = { record ->
                com.pcmobilelink.nearshare.receiver.AndroidReceiveEndpointDiscoveryClient(
                    diagnostics = { message -> NearShareDiagnostics.info(this, message) },
                ).discover(record)
            },
            diagnostics = { message -> NearShareDiagnostics.info(this, message) },
        )
        ensureNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> cancelTransfer(intent.getStringExtra(EXTRA_BATCH_ID))
            ACTION_RETRY -> startTransferFromIntent(intent, isRetry = true)
            ACTION_START -> startTransferFromIntent(intent, isRetry = false)
        }
        return START_REDELIVER_INTENT
    }

    private fun startTransferFromIntent(intent: Intent, isRetry: Boolean) {
        val batchId = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
        val pcDeviceId = intent.getStringExtra(EXTRA_PC_DEVICE_ID).orEmpty()
        if (batchId.isBlank() || pcDeviceId.isBlank()) {
            publishTerminalFailure(batchId, "Transfer could not start because its saved state is incomplete.")
            return
        }

        synchronized(stateLock) {
            if (activeThread?.isAlive == true) {
                return
            }
            activeBatchId = batchId
        }

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                title = if (isRetry) "Retrying transfer" else "Preparing transfer",
                text = "Preparing files for NearShare transfer...",
                progress = 0,
                indeterminate = true,
                batchId = batchId,
                showCancel = true,
                showRetry = false,
                ongoing = true,
            ),
        )

        val thread = Thread({ runTransfer(batchId, pcDeviceId) }, "NearShareTransfer-$batchId")
        synchronized(stateLock) { activeThread = thread }
        thread.start()
    }

    private fun runTransfer(batchId: String, pcDeviceId: String) {
        val control = FileTransferControl()
        synchronized(stateLock) { activeControl = control }

        var batch: PreparedTransferBatch? = null
        try {
            val manifests = activeTransferStore.loadAll()
                .filter { manifest -> manifest.batchId == batchId && manifest.status == ActiveTransferStatus.Active }
            if (manifests.isEmpty()) {
                throw IllegalStateException("No active transfer files were found. Share the files again.")
            }

            batch = PreparedTransferBatch.fromActiveManifests(manifests)
            val storedRecord = pairedPcStore.loadAll().firstOrNull { record ->
                record.pcDeviceId.equals(pcDeviceId, ignoreCase = true)
            } ?: throw IllegalStateException("The selected device is no longer paired. Pair it again before sending files.")

            updateNotification(
                title = "Finding ${storedRecord.pcName}",
                text = "Looking for the paired device on this local network...",
                progress = 0,
                indeterminate = true,
                batchId = batchId,
                showCancel = true,
                showRetry = false,
                ongoing = true,
            )

            val resolvedRecord = endpointResolver.resolve(storedRecord)
            if (resolvedRecord.endpoints != storedRecord.endpoints) {
                pairedPcStore.addOrUpdate(resolvedRecord)
            }

            val transfers = transferClient.sendPreparedFiles(batch, resolvedRecord, control) { progress ->
                publishProgress(batchId, resolvedRecord, progress)
            }

            val totalBytes = transfers.sumOf { it.sizeBytes }
            activeTransferStore.deleteBatch(batchId)
            batch.close()
            publishCompleted(batchId, resolvedRecord, transfers.size, totalBytes)
            playTransferSound(TransferSoundResult.Success)
            stopForegroundAfterTerminalNotification(removeNotification = true)
            stopSelf()
        } catch (exception: Exception) {
            val removeNotification: Boolean
            if (exception is CancellationException || control.isCancellationRequested) {
                activeTransferStore.deleteBatch(batchId)
                batch?.close()
                publishCancelled(batchId)
                playTransferSound(TransferSoundResult.Failure)
                removeNotification = true
            } else {
                val message = PairingErrorMessage.from(exception)
                publishFailed(batchId, message)
                playTransferSound(TransferSoundResult.Failure)
                removeNotification = false
                updateNotification(
                    title = "NearShare transfer stopped",
                    text = message,
                    progress = 0,
                    indeterminate = false,
                    batchId = batchId,
                    showCancel = false,
                    showRetry = true,
                    ongoing = false,
                )
            }
            stopForegroundAfterTerminalNotification(removeNotification)
            stopSelf()
        } finally {
            synchronized(stateLock) {
                activeControl = null
                activeThread = null
                activeBatchId = null
            }
        }
    }

    private fun cancelTransfer(batchId: String?) {
        synchronized(stateLock) {
            if (batchId == null || batchId == activeBatchId) {
                activeControl?.cancel()
            }
        }
    }

    private fun publishProgress(batchId: String, record: PairedPcRecord, progress: FileTransferProgress) {
        updateNotification(
            title = "Sending to ${record.pcName}",
            text = "${progress.fileIndex}/${progress.totalFiles}: ${progress.fileName} (${progress.batchPercent}%)",
            progress = progress.batchPercent,
            indeterminate = false,
            batchId = batchId,
            showCancel = true,
            showRetry = false,
            ongoing = true,
        )
        sendBroadcast(
            Intent(ACTION_TRANSFER_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_EVENT_STATUS, STATUS_PROGRESS)
                .putExtra(EXTRA_BATCH_ID, batchId)
                .putExtra(EXTRA_PC_NAME, record.pcName)
                .putExtra(EXTRA_FILE_INDEX, progress.fileIndex)
                .putExtra(EXTRA_TOTAL_FILES, progress.totalFiles)
                .putExtra(EXTRA_FILE_NAME, progress.fileName)
                .putExtra(EXTRA_SENT_BYTES, progress.sentBytes)
                .putExtra(EXTRA_TOTAL_BYTES, progress.totalBytes)
                .putExtra(EXTRA_BATCH_SENT_BYTES, progress.batchSentBytes)
                .putExtra(EXTRA_BATCH_TOTAL_BYTES, progress.batchTotalBytes),
        )
    }

    private fun publishCompleted(batchId: String, record: PairedPcRecord, fileCount: Int, totalBytes: Long) {
        sendBroadcast(
            Intent(ACTION_TRANSFER_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_EVENT_STATUS, STATUS_COMPLETED)
                .putExtra(EXTRA_BATCH_ID, batchId)
                .putExtra(EXTRA_PC_NAME, record.pcName)
                .putExtra(EXTRA_TOTAL_FILES, fileCount)
                .putExtra(EXTRA_TOTAL_BYTES, totalBytes),
        )
    }

    private fun publishFailed(batchId: String, message: String) {
        sendBroadcast(
            Intent(ACTION_TRANSFER_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_EVENT_STATUS, STATUS_FAILED)
                .putExtra(EXTRA_BATCH_ID, batchId)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    private fun publishTerminalFailure(batchId: String, message: String) {
        if (batchId.isNotBlank()) {
            publishFailed(batchId, message)
        }
        playTransferSound(TransferSoundResult.Failure)
        updateNotification(
            title = "NearShare transfer could not start",
            text = message,
            progress = 0,
            indeterminate = false,
            batchId = batchId,
            showCancel = false,
            showRetry = false,
            ongoing = false,
        )
    }

    private fun publishCancelled(batchId: String) {
        sendBroadcast(
            Intent(ACTION_TRANSFER_EVENT)
                .setPackage(packageName)
                .putExtra(EXTRA_EVENT_STATUS, STATUS_CANCELLED)
                .putExtra(EXTRA_BATCH_ID, batchId),
        )
    }

    private fun playTransferSound(result: TransferSoundResult) {
        TransferSoundPlayer(this).play(result)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "File transfers",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows active NearShare file transfer progress."
        }
        manager.createNotificationChannel(channel)
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        batchId: String,
        showCancel: Boolean,
        showRetry: Boolean,
        ongoing: Boolean,
    ) {
        val notification = buildNotification(title, text, progress, indeterminate, batchId, showCancel, showRetry, ongoing)
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean,
        batchId: String,
        showCancel: Boolean,
        showRetry: Boolean,
        ongoing: Boolean,
    ): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        builder
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setProgress(100, progress.coerceIn(0, 100), indeterminate)

        if (showCancel) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Cancel",
                    servicePendingIntent(ACTION_CANCEL, batchId),
                ).build(),
            )
        }
        if (showRetry) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    "Retry",
                    servicePendingIntent(ACTION_RETRY, batchId),
                ).build(),
            )
        }

        return builder.build()
    }

    private fun servicePendingIntent(action: String, batchId: String): PendingIntent {
        val pcDeviceId = activeTransferStore.loadAll()
            .firstOrNull { it.batchId == batchId }
            ?.pcDeviceId
            .orEmpty()
        val intent = Intent(this, TransferForegroundService::class.java)
            .setAction(action)
            .putExtra(EXTRA_BATCH_ID, batchId)
            .putExtra(EXTRA_PC_DEVICE_ID, pcDeviceId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode() xor batchId.hashCode(), intent, flags)
    }

    private fun stopForegroundAfterTerminalNotification(removeNotification: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(removeNotification)
        }
    }

    companion object {
        const val ACTION_START = "com.pcmobilelink.nearshare.transfer.START"
        const val ACTION_CANCEL = "com.pcmobilelink.nearshare.transfer.CANCEL"
        const val ACTION_RETRY = "com.pcmobilelink.nearshare.transfer.RETRY"
        const val ACTION_TRANSFER_EVENT = "com.pcmobilelink.nearshare.transfer.EVENT"

        const val EXTRA_BATCH_ID = "batchId"
        const val EXTRA_PC_DEVICE_ID = "pcDeviceId"
        const val EXTRA_EVENT_STATUS = "status"
        const val EXTRA_PC_NAME = "pcName"
        const val EXTRA_FILE_INDEX = "fileIndex"
        const val EXTRA_TOTAL_FILES = "totalFiles"
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_SENT_BYTES = "sentBytes"
        const val EXTRA_TOTAL_BYTES = "totalBytes"
        const val EXTRA_BATCH_SENT_BYTES = "batchSentBytes"
        const val EXTRA_BATCH_TOTAL_BYTES = "batchTotalBytes"
        const val EXTRA_MESSAGE = "message"

        const val STATUS_PROGRESS = "progress"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
        const val STATUS_CANCELLED = "cancelled"

        private const val CHANNEL_ID = "nearshare_transfer_progress"
        private const val NOTIFICATION_ID = 1101

        fun startTransfer(context: Context, batchId: String, pcDeviceId: String) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_BATCH_ID, batchId)
                .putExtra(EXTRA_PC_DEVICE_ID, pcDeviceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun retryTransfer(context: Context, batchId: String, pcDeviceId: String) {
            val intent = Intent(context, TransferForegroundService::class.java)
                .setAction(ACTION_RETRY)
                .putExtra(EXTRA_BATCH_ID, batchId)
                .putExtra(EXTRA_PC_DEVICE_ID, pcDeviceId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancelTransfer(context: Context, batchId: String) {
            context.startService(
                Intent(context, TransferForegroundService::class.java)
                    .setAction(ACTION_CANCEL)
                    .putExtra(EXTRA_BATCH_ID, batchId),
            )
        }
    }
}
