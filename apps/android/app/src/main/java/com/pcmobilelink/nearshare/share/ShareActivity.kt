@file:Suppress("DEPRECATION")

package com.pcmobilelink.nearshare.share

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.pcmobilelink.nearshare.connectivity.AndroidPrivateConnectionHost
import com.pcmobilelink.nearshare.connectivity.AndroidPrivateConnectionJoiner
import com.pcmobilelink.nearshare.connectivity.PrivateConnectionOffer
import com.pcmobilelink.nearshare.connectivity.PrivateConnectionOfferCodec
import com.pcmobilelink.nearshare.connectivity.PrivateConnectionSecurityCode
import com.pcmobilelink.nearshare.diagnostics.NearShareDiagnostics
import com.pcmobilelink.nearshare.pairing.AndroidLocalPairingServer
import com.pcmobilelink.nearshare.pairing.LocalPairingPendingRequest
import com.pcmobilelink.nearshare.pairing.PairingErrorMessage
import com.pcmobilelink.nearshare.pairing.qr.QrCodeBitmap
import com.pcmobilelink.nearshare.pairing.qr.QrScannerActivity
import com.pcmobilelink.nearshare.receiver.AndroidReceiveEndpointRegistry
import com.pcmobilelink.nearshare.receiver.AndroidReceiveForegroundService
import com.pcmobilelink.nearshare.receiver.ReceiveTransferProgress
import com.pcmobilelink.nearshare.storage.AndroidDeviceIdentityStore
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import com.pcmobilelink.nearshare.storage.PairedPcStore
import com.pcmobilelink.nearshare.transfer.ActiveTransferManifestStore
import com.pcmobilelink.nearshare.transfer.AndroidFileTransferClient
import com.pcmobilelink.nearshare.transfer.FileTransferProgress
import com.pcmobilelink.nearshare.transfer.PreparedTransferBatch
import com.pcmobilelink.nearshare.transfer.TransferForegroundService
import com.pcmobilelink.nearshare.ui.AppTypeface
import com.pcmobilelink.nearshare.ui.ThemedAlertDialog
import java.io.File

class ShareActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var pickerStatusText: TextView
    private lateinit var batchProgressLabel: TextView
    private lateinit var batchProgressBar: ProgressBar
    private lateinit var fileProgressLabel: TextView
    private lateinit var fileProgressBar: ProgressBar
    private lateinit var cancelButton: Button
    private lateinit var retryButton: Button
    private lateinit var selectedFilesHeader: TextView
    private lateinit var selectedFilesRecyclerView: RecyclerView
    private lateinit var selectedFilesAdapter: SelectedFilesAdapter
    private lateinit var deviceSelectorSection: LinearLayout
    private lateinit var bottomTransferPanel: LinearLayout
    private lateinit var deviceSpinner: Spinner
    private lateinit var sendButton: Button
    private lateinit var pairedPcStore: PairedPcStore
    private lateinit var identityStore: AndroidDeviceIdentityStore
    private lateinit var activeTransferStore: ActiveTransferManifestStore
    private lateinit var privateConnectionHost: AndroidPrivateConnectionHost
    private lateinit var privateConnectionJoiner: AndroidPrivateConnectionJoiner
    private val transferClient = AndroidFileTransferClient()
    private val transferEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleTransferEvent(intent ?: return)
        }
    }
    private var sharedFileUris: List<Uri> = emptyList()
    private var currentBatch: PreparedTransferBatch? = null
    private var currentRecord: PairedPcRecord? = null
    private var pairedPcDropdownRecords: List<PairedPcRecord> = emptyList()
    private var selectedRecord: PairedPcRecord? = null
    private var pickerEnabled = true
    private var privateConnectionOffer: PrivateConnectionOffer? = null
    private var pendingPrivateConnectionJoinOffer: PrivateConnectionOffer? = null
    private var pendingReceiveNotificationPermissionAction: (() -> Unit)? = null
    private var privateConnectionAutoReceiveStarted = false
    private var privateConnectionPairingServer: AndroidLocalPairingServer? = null
    private var activePrivateConnectionPairingRequestId: String? = null
    private var selectedFileMetadataGeneration = 0

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        handlePrivateConnectionQrResult(result.resultCode, result.data)
    }

    private val privateConnectionHostPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startPrivateConnection()
        } else {
            setContextStatus("Private connection needs Nearby devices permission.")
        }
    }

    private val privateConnectionJoinPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val offer = pendingPrivateConnectionJoinOffer
        pendingPrivateConnectionJoinOffer = null
        if (offer != null && hasPrivateConnectionJoinPermissions()) {
            joinPrivateConnection(offer)
        } else {
            setContextStatus("Private connection needs nearby Wi-Fi permission.")
        }
    }

    private val receiveNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingReceiveNotificationPermissionAction
        pendingReceiveNotificationPermissionAction = null
        action?.invoke()
        if (!granted) {
            setContextStatus("Android may hide receive status until NearShare notifications are enabled.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        pairedPcStore = PairedPcStore(this)
        identityStore = AndroidDeviceIdentityStore(this)
        activeTransferStore = ActiveTransferManifestStore(File(filesDir, "active-transfers.json"))
        privateConnectionHost = AndroidPrivateConnectionHost(this) {
            privateConnectionOffer = null
            stopPrivateConnectionPairingOffer()
            stopAutoReceiveForPrivateConnection()
            runOnUiThread {
                refreshDeviceDropdownKeepingSelection()
                setContextStatus("Private connection stopped.")
            }
        }
        privateConnectionJoiner = AndroidPrivateConnectionJoiner(this)
        sharedFileUris = ShareIntentFileSelector.selectedFileUris(intent)
        setContentView(buildContent())
        installBackgroundBackHandler()
        renderPicker()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TransferForegroundService.ACTION_TRANSFER_EVENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transferEventReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(transferEventReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(transferEventReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        if (::privateConnectionHost.isInitialized) {
            privateConnectionHost.stop()
        }
        if (::privateConnectionJoiner.isInitialized) {
            privateConnectionJoiner.disconnect()
        }
        privateConnectionPairingServer?.close()
        privateConnectionPairingServer = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun installBackgroundBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (currentBatch != null || !pickerEnabled) {
                        statusText.text = "Transfer continues in the background. Use the notification to watch progress or cancel."
                        moveTaskToBack(true)
                        return
                    }

                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            },
        )
    }

    private fun buildContent(): View {
        val baseContentPadding = ShareActivityInsets.Padding(
            left = dp(24),
            top = dp(32),
            right = dp(24),
            bottom = dp(32),
        )
        val bottomPanelPadding = ShareActivityInsets.Padding(
            left = dp(24),
            top = dp(14),
            right = dp(24),
            bottom = dp(16),
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                baseContentPadding.left,
                baseContentPadding.top,
                baseContentPadding.right,
                baseContentPadding.bottom,
            )
        }
        content.addView(
            TextView(this).apply {
                text = "Send with NearShare"
                textSize = 30f
                typeface = AppTypeface.bold
                setTextColor(COLOR_TEXT)
                includeFontPadding = false
            },
        )
        content.addView(
            TextView(this).apply {
                text = "Choose a paired device. The transfer stays local and uses your paired-device key."
                textSize = 16f
                setTextColor(COLOR_SECONDARY_TEXT)
                setPadding(0, dp(12), 0, dp(20))
            },
        )

        deviceSelectorSection = buildDeviceSelectorSection()
        content.addView(
            deviceSelectorSection,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(14)
            },
        )

        selectedFilesHeader = TextView(this).apply {
            text = "Selected files"
            textSize = 13f
            typeface = AppTypeface.bold
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(0, 0, 0, dp(8))
            visibility = View.GONE
        }
        content.addView(selectedFilesHeader)

        selectedFilesAdapter = SelectedFilesAdapter()
        selectedFilesRecyclerView = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ShareActivity)
            adapter = selectedFilesAdapter
            clipToPadding = false
            setHasFixedSize(false)
            visibility = View.GONE
        }
        content.addView(
            selectedFilesRecyclerView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = dp(12)
            },
        )

        pickerStatusText = TextView(this).apply {
            textSize = 15f
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(18), dp(1))
        }
        content.addView(
            pickerStatusText,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            },
        )

        bottomTransferPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(
                bottomPanelPadding.left,
                bottomPanelPadding.top,
                bottomPanelPadding.right,
                bottomPanelPadding.bottom,
            )
            background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, 0, dp(1))
            elevation = dp(8).toFloat()
        }

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(0, 0, 0, dp(12))
        }
        bottomTransferPanel.addView(
            statusText,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        batchProgressLabel = progressLabel("Overall progress")
        bottomTransferPanel.addView(batchProgressLabel)
        batchProgressBar = progressBar()
        bottomTransferPanel.addView(
            batchProgressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                bottomMargin = dp(12)
            },
        )

        fileProgressLabel = progressLabel("Current file")
        bottomTransferPanel.addView(fileProgressLabel)
        fileProgressBar = progressBar()
        bottomTransferPanel.addView(
            fileProgressBar,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)).apply {
                bottomMargin = dp(14)
            },
        )

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        cancelButton = secondaryButton("Cancel").apply {
            visibility = View.GONE
            setOnClickListener {
                statusText.text = "Cancelling transfer..."
                currentBatch?.batchId?.takeIf { it.isNotBlank() }?.let { batchId ->
                    TransferForegroundService.cancelTransfer(this@ShareActivity, batchId)
                }
            }
        }
        retryButton = secondaryButton("Retry").apply {
            visibility = View.GONE
            setOnClickListener { retryCurrentTransfer() }
        }
        actionRow.addView(
            cancelButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(8) },
        )
        actionRow.addView(
            retryButton,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) },
        )
        bottomTransferPanel.addView(
            actionRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setProgressVisible(false)

        root.addView(
            content,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        root.addView(
            bottomTransferPanel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val safePadding = ShareActivityInsets.contentPadding(
                base = baseContentPadding,
                systemBars = ShareActivityInsets.Padding(
                    left = bars.left,
                    top = bars.top,
                    right = bars.right,
                    bottom = bars.bottom,
                ),
            )
            content.setPadding(safePadding.left, safePadding.top, safePadding.right, safePadding.bottom)
            bottomTransferPanel.setPadding(
                bottomPanelPadding.left + bars.left,
                bottomPanelPadding.top,
                bottomPanelPadding.right + bars.right,
                bottomPanelPadding.bottom + bars.bottom,
            )
            insets
        }
        return root
    }

    private fun buildDeviceSelectorSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(20), dp(1))
            addView(
                TextView(this@ShareActivity).apply {
                    text = "Send to"
                    textSize = 13f
                    typeface = AppTypeface.bold
                    setTextColor(COLOR_SECONDARY_TEXT)
                    setPadding(0, 0, 0, dp(8))
                },
            )
            deviceSpinner = Spinner(this@ShareActivity, Spinner.MODE_DROPDOWN).apply {
                background = roundedStrokeDrawable(COLOR_SUBTLE_SURFACE, COLOR_BORDER, dp(16), dp(1))
                setPadding(dp(10), 0, dp(10), 0)
                prompt = SELECT_DEVICE_LABEL
            }
            addView(
                deviceSpinner,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                    bottomMargin = dp(12)
                },
            )
            sendButton = primaryButton("Send files").apply {
                setOnClickListener { sendSelectedDeviceFiles() }
            }
            addView(sendButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(
                secondaryButton("Create private connection").apply {
                    setOnClickListener { requestPrivateConnectionStart() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    topMargin = dp(10)
                },
            )
            val privateConnectionRow = LinearLayout(this@ShareActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            privateConnectionRow.addView(
                secondaryButton("Scan QR").apply {
                    setOnClickListener { qrScannerLauncher.launch(Intent(this@ShareActivity, QrScannerActivity::class.java)) }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { rightMargin = dp(6) },
            )
            privateConnectionRow.addView(
                secondaryButton("Enter details").apply {
                    setOnClickListener { showManualPrivateConnectionDialog() }
                },
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(6) },
            )
            addView(privateConnectionRow)
        }
    }

    private fun renderPicker() {
        renderSelectedFiles()
        val pairedPcs = pairedPcStore.loadAll()
        pairedPcDropdownRecords = pairedPcs
        val initialTarget = PairedPcShareSelection.selectInitialDropdownTarget(
            records = pairedPcs,
            requestedPcDeviceId = intent.getStringExtra(EXTRA_TARGET_PC_DEVICE_ID),
            lastSelectedPcDeviceId = pairedPcStore.loadLastSelectedSendPcDeviceId(),
        )
        populateDeviceDropdown(pairedPcs, initialTarget)

        if (sharedFileUris.isEmpty()) {
            setPickerStatus("No shared files were found. Try sharing one or more files with NearShare again.")
            updateSendButtonState()
            return
        }

        if (pairedPcs.isEmpty()) {
            setPickerStatus("No paired devices yet. Open NearShare, pair this phone with another device, then share the files again.")
            updateSendButtonState()
            return
        }

        val directTarget = PairedPcShareSelection.selectDirectTarget(
            pairedPcs,
            intent.getStringExtra(EXTRA_TARGET_PC_DEVICE_ID),
        )
        if (directTarget != null) {
            sendFiles(directTarget, sharedFileUris)
            return
        }

        updateReadyStatus()
        updateSendButtonState()
    }

    private fun refreshDeviceDropdownKeepingSelection() {
        val currentSelectedId = selectedRecord?.pcDeviceId
        val pairedPcs = pairedPcStore.loadAll()
        pairedPcDropdownRecords = pairedPcs
        val initialTarget = PairedPcShareSelection.selectInitialDropdownTarget(
            records = pairedPcs,
            requestedPcDeviceId = currentSelectedId,
            lastSelectedPcDeviceId = pairedPcStore.loadLastSelectedSendPcDeviceId(),
        ) ?: pairedPcs.firstOrNull()
        populateDeviceDropdown(pairedPcs, initialTarget)
        updateReadyStatus()
        updateSendButtonState()
    }

    private fun populateDeviceDropdown(pairedPcs: List<PairedPcRecord>, initialTarget: PairedPcRecord?) {
        val items = listOf(PairedPcDropdownItem(record = null, label = SELECT_DEVICE_LABEL)) +
            pairedPcs.map { record -> PairedPcDropdownItem(record = record, label = record.pcName) }
        val adapter = PairedPcDropdownAdapter(items)
        deviceSpinner.onItemSelectedListener = null
        deviceSpinner.adapter = adapter

        val selectedIndex = initialTarget?.let { target ->
            pairedPcs.indexOfFirst { it.pcDeviceId.equals(target.pcDeviceId, ignoreCase = true) }
                .takeIf { it >= 0 }
                ?.plus(1)
        } ?: 0
        selectedRecord = initialTarget
        deviceSpinner.setSelection(selectedIndex, false)
        deviceSpinner.isEnabled = pairedPcs.isNotEmpty()
        deviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val record = (parent?.getItemAtPosition(position) as? PairedPcDropdownItem)?.record
                selectedRecord = record
                if (record != null) {
                    pairedPcStore.saveLastSelectedSendPcDeviceId(record.pcDeviceId)
                } else {
                    pairedPcStore.clearLastSelectedSendPcDeviceId()
                }
                updateReadyStatus()
                updateSendButtonState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedRecord = null
                pairedPcStore.clearLastSelectedSendPcDeviceId()
                updateReadyStatus()
                updateSendButtonState()
            }
        }
    }

    private fun sendSelectedDeviceFiles() {
        val record = selectedRecord
        if (record == null) {
            showIssueAlert(
                title = "Select a device",
                message = "Choose a paired device from the device dropdown before sending files.",
            )
            return
        }
        if (sharedFileUris.isEmpty()) {
            showIssueAlert(
                title = "No files selected",
                message = "No shared files were found. Try sharing one or more files with NearShare again.",
            )
            return
        }

        sendFiles(record, sharedFileUris)
    }

    private fun updateReadyStatus() {
        if (sharedFileUris.isEmpty()) {
            setPickerStatus("No shared files were found. Try sharing one or more files with NearShare again.")
            return
        }
        if (pairedPcDropdownRecords.isEmpty()) {
            setPickerStatus("No paired devices yet. Open NearShare, pair this phone with another device, then share the files again.")
            return
        }

        val fileLabel = if (sharedFileUris.size == 1) "1 file" else "${sharedFileUris.size} files"
        val record = selectedRecord
        setPickerStatus(if (record == null) {
            "Ready to send $fileLabel. Select a paired device first."
        } else {
            "Ready to send $fileLabel to ${record.pcName}. Keep NearShare open on that device."
        })
    }

    private inner class PairedPcDropdownAdapter(
        items: List<PairedPcDropdownItem>,
    ) : ArrayAdapter<PairedPcDropdownItem>(this@ShareActivity, android.R.layout.simple_spinner_item, items) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return dropdownTextView(position, convertView, parent, isDropdown = false)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            return dropdownTextView(position, convertView, parent, isDropdown = true)
        }

        private fun dropdownTextView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
            isDropdown: Boolean,
        ): TextView {
            val item = getItem(position)
            return ((convertView as? TextView) ?: TextView(parent.context)).apply {
                text = item?.label.orEmpty()
                textSize = if (isDropdown) 15f else 16f
                typeface = if (item?.record == null) AppTypeface.regular else AppTypeface.bold
                setTextColor(if (item?.record == null) COLOR_MUTED else COLOR_TEXT)
                gravity = Gravity.CENTER_VERTICAL
                includeFontPadding = false
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(12), 0)
                minHeight = if (isDropdown) dp(48) else dp(52)
                background = if (isDropdown) {
                    roundedDrawable(Color.WHITE, 0)
                } else {
                    null
                }
            }
        }
    }

    private data class PairedPcDropdownItem(
        val record: PairedPcRecord?,
        val label: String,
    )

    private fun requestPrivateConnectionStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            privateConnectionHostPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            return
        }

        startPrivateConnection()
    }

    private fun startPrivateConnection() {
        setContextStatus("Creating private connection...")
        privateConnectionHost.start(
            securityCodeProvider = { startPrivateConnectionPairingOffer().offer.shortCode.orEmpty() },
        ) { result ->
            runOnUiThread {
                result.onSuccess { offer ->
                    privateConnectionOffer = offer
                    startReceiveForPrivateConnection("created")
                    refreshDeviceDropdownKeepingSelection()
                    showPrivateConnectionOfferDialog(offer)
                    setContextStatus("Private connection and pairing are ready. Approve the other device when it asks to pair.")
                }.onFailure { exception ->
                    privateConnectionOffer = null
                    stopPrivateConnectionPairingOffer()
                    setContextStatus("Could not create private connection: ${exception.message ?: "Try again."}")
                }
            }
        }
    }

    private fun showPrivateConnectionOfferDialog(offer: PrivateConnectionOffer) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(
                TextView(this@ShareActivity).apply {
                    text = "Scan this inside NearShare on the other device, or enter the details below. The 9-character code also starts pairing so the device can be saved for later transfers."
                    textSize = 14f
                    setTextColor(COLOR_SECONDARY_TEXT)
                    typeface = AppTypeface.regular
                    setPadding(0, 0, 0, dp(12))
                },
            )
            val qrSize = dp(184)
            addView(
                ImageView(this@ShareActivity).apply {
                    setImageBitmap(QrCodeBitmap.create(PrivateConnectionOfferCodec.encode(offer), qrSize))
                    adjustViewBounds = true
                    setBackgroundColor(Color.WHITE)
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                },
                LinearLayout.LayoutParams(qrSize, qrSize).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(12)
                },
            )
            addView(privateConnectionDetail("Connection name", offer.connectionName))
            addView(privateConnectionDetail("Password", offer.password.ifBlank { "No password" }))
            addView(privateConnectionDetail("Security / pairing code", PrivateConnectionSecurityCode.format(offer.code)))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Private connection ready")
            .setView(content)
            .setNegativeButton("Close", null)
            .setPositiveButton("Stop") { _, _ -> stopPrivateConnection() }
            .show()
        ThemedAlertDialog.apply(dialog)
    }

    private fun privateConnectionDetail(label: String, value: String): View {
        return TextView(this).apply {
            text = "$label: $value"
            textSize = 15f
            typeface = AppTypeface.regular
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(4), 0, 0)
        }
    }

    private fun stopPrivateConnection() {
        privateConnectionHost.stop()
        privateConnectionOffer = null
        stopPrivateConnectionPairingOffer()
        stopAutoReceiveForPrivateConnection()
        refreshDeviceDropdownKeepingSelection()
        setContextStatus("Private connection stopped.")
    }

    private fun startPrivateConnectionPairingOffer(): AndroidLocalPairingServer {
        privateConnectionPairingServer?.close()
        activePrivateConnectionPairingRequestId = null

        val server = AndroidLocalPairingServer.start(
            context = this,
            deviceName = androidDeviceName(),
            devicePublicKey = identityStore.devicePublicKey(),
            lifetimeSeconds = AndroidPairingOfferLifetimeSeconds,
            progressChanged = ::handlePrivateConnectionHostedReceiveProgress,
            onPendingRequestChanged = {
                runOnUiThread { showPrivateConnectionPairingRequestIfNeeded() }
            },
        )
        privateConnectionPairingServer = server
        return server
    }

    private fun stopPrivateConnectionPairingOffer() {
        privateConnectionPairingServer?.close()
        privateConnectionPairingServer = null
        activePrivateConnectionPairingRequestId = null
    }

    private fun showPrivateConnectionPairingRequestIfNeeded() {
        val request = privateConnectionPairingServer?.currentPendingRequest() ?: return
        if (activePrivateConnectionPairingRequestId == request.requestId) {
            return
        }

        activePrivateConnectionPairingRequestId = request.requestId
        showPrivateConnectionPairingRequestDialog(request)
    }

    private fun showPrivateConnectionPairingRequestDialog(request: LocalPairingPendingRequest) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("${request.deviceName} wants to pair")
            .setMessage("Approve only if this is the device that just joined your private connection. Once approved, it appears in the paired-device list for future transfers.")
            .setNegativeButton("Reject") { _, _ -> rejectPrivateConnectionPairingRequest(request.requestId) }
            .setPositiveButton("Approve") { _, _ -> approvePrivateConnectionPairingRequest(request.requestId) }
            .show()
        ThemedAlertDialog.apply(dialog)
    }

    private fun approvePrivateConnectionPairingRequest(requestId: String) {
        try {
            val record = privateConnectionPairingServer?.approve(requestId)
                ?: throw IllegalStateException("Private connection pairing is not active.")
            PairedPcShareTargets.publish(this, pairedPcStore.loadAll())
            pairedPcStore.saveLastSelectedSendPcDeviceId(record.pcDeviceId)
            selectedRecord = record
            activePrivateConnectionPairingRequestId = null
            refreshDeviceDropdownKeepingSelection()
            setContextStatus("${record.pcName} paired. Select it in the dropdown and send when ready.")
        } catch (exception: Exception) {
            setContextStatus("Could not approve pairing: ${PairingErrorMessage.from(exception)}")
        }
    }

    private fun rejectPrivateConnectionPairingRequest(requestId: String) {
        val rejected = privateConnectionPairingServer?.reject(requestId) == true
        activePrivateConnectionPairingRequestId = null
        setContextStatus(if (rejected) "Pairing rejected." else "No pending private connection pairing request.")
    }

    private fun handlePrivateConnectionQrResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            setContextStatus("Private connection scan cancelled.")
            return
        }

        val scannedText = data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_TEXT).orEmpty()
        if (scannedText.isBlank()) {
            setContextStatus("No private connection QR code found.")
            return
        }

        val offer = runCatching { PrivateConnectionOfferCodec.decode(scannedText) }
            .getOrElse { exception ->
                setContextStatus("Could not read private connection QR: ${exception.message ?: "Try again."}")
                return
            }
        requestPrivateConnectionJoin(offer)
    }

    private fun showManualPrivateConnectionDialog() {
        val connectionNameInput = privateConnectionDialogInput("Connection name", InputType.TYPE_CLASS_TEXT)
        val passwordInput = privateConnectionDialogInput(
            "Password",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val securityCodeInput = privateConnectionDialogInput(
            "Security / pairing code",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        ).apply {
            filters = arrayOf(InputFilter.LengthFilter(PrivateConnectionSecurityCodeLength))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(privateConnectionDialogLabel("Connection name"))
            addView(connectionNameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(privateConnectionDialogLabel("Password"))
            addView(passwordInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(privateConnectionDialogLabel("Security / pairing code"))
            addView(securityCodeInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Connect private connection")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect", null)
            .create()
        dialog.setOnShowListener {
            ThemedAlertDialog.apply(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val connectionName = connectionNameInput.text.toString().trim()
                val password = passwordInput.text.toString()
                val securityCode = PrivateConnectionSecurityCode.normalize(securityCodeInput.text.toString())
                when {
                    connectionName.isBlank() -> setContextStatus("Enter the connection name shown on the other device.")
                    !PrivateConnectionSecurityCode.isValid(securityCode) -> setContextStatus("Enter the 9-character security / pairing code shown on the other device.")
                    else -> {
                        val now = System.currentTimeMillis() / 1000
                        dialog.dismiss()
                        requestPrivateConnectionJoin(
                            PrivateConnectionOffer(
                                connectionName = connectionName,
                                password = password,
                                code = securityCode,
                                createdAtUnixTimeSeconds = now,
                                expiresAtUnixTimeSeconds = now + ManualPrivateConnectionLifetimeSeconds,
                            ),
                        )
                    }
                }
            }
        }
        dialog.show()
    }

    private fun privateConnectionDialogLabel(label: String): View {
        return TextView(this).apply {
            text = label
            textSize = 12f
            typeface = AppTypeface.bold
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(10), 0, dp(4))
        }
    }

    private fun privateConnectionDialogInput(hintText: String, inputTypeFlags: Int): EditText {
        return EditText(this).apply {
            hint = hintText
            inputType = inputTypeFlags
            textSize = 15f
            typeface = AppTypeface.regular
            setTextColor(COLOR_TEXT)
            setHintTextColor(COLOR_MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(48)
            background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(10), dp(1))
            setSingleLine(true)
        }
    }

    private fun requestPrivateConnectionJoin(offer: PrivateConnectionOffer) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setContextStatus("Android 10 or newer is required to join a private connection inside NearShare.")
            return
        }

        val missingPermissions = privateConnectionJoinPermissions()
            .filter { permission -> checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) {
            pendingPrivateConnectionJoinOffer = offer
            privateConnectionJoinPermissionLauncher.launch(missingPermissions.toTypedArray())
            return
        }

        joinPrivateConnection(offer)
    }

    private fun joinPrivateConnection(offer: PrivateConnectionOffer) {
        setContextStatus("Connecting private connection...")
        privateConnectionJoiner.connect(offer) { result ->
            runOnUiThread {
                result.onSuccess { joined ->
                    startReceiveForPrivateConnection("joined")
                    refreshDeviceDropdownKeepingSelection()
                    setContextStatus("Connected to ${joined.connectionName}. Select the paired device and send.")
                }.onFailure { exception ->
                    setContextStatus("Could not connect private connection: ${exception.message ?: "Try again."}")
                }
            }
        }
    }

    private fun startReceiveForPrivateConnection(routeState: String) {
        requestReceiveNotificationPermissionIfNeeded {
            val alreadyRunning = AndroidReceiveEndpointRegistry.currentEndpoint() != null
            AndroidReceiveForegroundService.startManual(this)
            privateConnectionAutoReceiveStarted = !alreadyRunning
            NearShareDiagnostics.info(this, "Share private connection $routeState; ensured manual receive alreadyRunning=$alreadyRunning")
        }
    }

    private fun handlePrivateConnectionHostedReceiveProgress(progress: ReceiveTransferProgress) {
        NearShareDiagnostics.info(
            this,
            "Share private connection pairing receive status=${progress.status} file=${progress.fileIndex}/${progress.totalFiles}",
        )
    }

    private fun stopAutoReceiveForPrivateConnection() {
        if (!privateConnectionAutoReceiveStarted) {
            return
        }

        AndroidReceiveForegroundService.stop(this)
        privateConnectionAutoReceiveStarted = false
        NearShareDiagnostics.info(this, "Share private connection ended; stopped auto-started manual receive")
    }

    private fun privateConnectionJoinPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyArray()
        }
    }

    private fun hasPrivateConnectionJoinPermissions(): Boolean {
        return privateConnectionJoinPermissions().all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestReceiveNotificationPermissionIfNeeded(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }

        pendingReceiveNotificationPermissionAction = action
        receiveNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun renderSelectedFiles() {
        selectedFileMetadataGeneration += 1
        val generation = selectedFileMetadataGeneration
        if (sharedFileUris.isEmpty()) {
            selectedFilesHeader.visibility = View.GONE
            selectedFilesRecyclerView.visibility = View.GONE
            selectedFilesAdapter.submit(emptyList())
            return
        }

        selectedFilesHeader.text = if (sharedFileUris.size == 1) {
            "Selected file"
        } else {
            "Selected files (${sharedFileUris.size})"
        }
        selectedFilesHeader.visibility = View.VISIBLE
        selectedFilesRecyclerView.visibility = View.VISIBLE
        selectedFilesAdapter.submit(
            sharedFileUris.map { uri ->
                SelectedFilePreview(
                    uri = uri,
                    displayName = fallbackDisplayName(uri),
                    mimeType = "",
                )
            },
        )

        val snapshot = sharedFileUris.toList()
        Thread {
            val resolved = snapshot.map { uri -> selectedFilePreview(uri) }
            runOnUiThread {
                if (generation == selectedFileMetadataGeneration) {
                    selectedFilesAdapter.submit(resolved)
                }
            }
        }.start()
    }

    private fun removeSharedFile(uri: Uri) {
        if (currentBatch != null) {
            statusText.text = "Cancel the active transfer before removing selected files."
            return
        }

        sharedFileUris = sharedFileUris.filterNot { it == uri }
        renderPicker()
    }

    private fun openSharedFile(uri: Uri, mimeType: String) {
        val type = mimeType.ifBlank { "*/*" }
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, type)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(viewIntent, "Open with"))
        } catch (_: ActivityNotFoundException) {
            setContextStatus("No app found to open this file.")
        } catch (exception: SecurityException) {
            setContextStatus("NearShare does not have permission to open this file: ${exception.message}")
        } catch (exception: RuntimeException) {
            setContextStatus("Could not open this file: ${exception.message ?: "unsupported file"}")
        }
    }

    private fun selectedFilePreview(uri: Uri): SelectedFilePreview {
        return SelectedFilePreview(
            uri = uri,
            displayName = queryDisplayName(uri),
            mimeType = runCatching { contentResolver.getType(uri).orEmpty() }.getOrDefault(""),
        )
    }

    private fun queryDisplayName(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        val value = cursor.getString(index)
                        if (!value.isNullOrBlank()) {
                            return@runCatching value
                        }
                    }
                }
            }

            fallbackDisplayName(uri)
        }.getOrDefault(fallbackDisplayName(uri))
    }

    private fun fallbackDisplayName(uri: Uri): String {
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "Shared file"
    }

    private inner class SelectedFilesAdapter : RecyclerView.Adapter<SelectedFileViewHolder>() {
        private var items: List<SelectedFilePreview> = emptyList()

        fun submit(updatedItems: List<SelectedFilePreview>) {
            items = updatedItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SelectedFileViewHolder {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(10), dp(10))
                background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(18), dp(1))
                isClickable = true
                isFocusable = true
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = dp(8)
                }
            }

            val thumbnailFrame = FrameLayout(parent.context).apply {
                background = roundedDrawable(0xFFEFF6FF.toInt(), dp(14))
                clipToOutline = true
            }
            val thumbnailImage = ImageView(parent.context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            thumbnailFrame.addView(
                thumbnailImage,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            val thumbnailIcon = TextView(parent.context).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(COLOR_PRIMARY)
            }
            thumbnailFrame.addView(
                thumbnailIcon,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            row.addView(
                thumbnailFrame,
                LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(12) },
            )

            val textColumn = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val nameText = TextView(parent.context).apply {
                textSize = 15f
                typeface = AppTypeface.bold
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_TEXT)
            }
            val detailText = TextView(parent.context).apply {
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(COLOR_SECONDARY_TEXT)
                setPadding(0, dp(4), 0, 0)
            }
            textColumn.addView(nameText)
            textColumn.addView(detailText)
            row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val removeButton = TextView(parent.context).apply {
                text = "×"
                textSize = 24f
                gravity = Gravity.CENTER
                typeface = AppTypeface.bold
                setTextColor(0xFFDC2626.toInt())
                background = roundedDrawable(0xFFFEF2F2.toInt(), dp(16))
                isClickable = true
                isFocusable = true
            }
            row.addView(
                removeButton,
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(10) },
            )

            return SelectedFileViewHolder(
                row = row,
                thumbnailImage = thumbnailImage,
                thumbnailIcon = thumbnailIcon,
                nameText = nameText,
                detailText = detailText,
                removeButton = removeButton,
            )
        }

        override fun onBindViewHolder(holder: SelectedFileViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun onViewRecycled(holder: SelectedFileViewHolder) {
            Glide.with(holder.thumbnailImage).clear(holder.thumbnailImage)
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class SelectedFileViewHolder(
        private val row: LinearLayout,
        val thumbnailImage: ImageView,
        private val thumbnailIcon: TextView,
        private val nameText: TextView,
        private val detailText: TextView,
        private val removeButton: TextView,
    ) : RecyclerView.ViewHolder(row) {
        fun bind(item: SelectedFilePreview) {
            nameText.text = item.displayName
            detailText.text = if (item.mimeType.isBlank()) "Tap to preview" else "${item.mimeType} • Tap to preview"
            row.setOnClickListener { openSharedFile(item.uri, item.mimeType) }
            thumbnailImage.setOnClickListener { openSharedFile(item.uri, item.mimeType) }
            thumbnailIcon.setOnClickListener { openSharedFile(item.uri, item.mimeType) }
            removeButton.contentDescription = "Remove ${item.displayName} from queue"
            removeButton.setOnClickListener { removeSharedFile(item.uri) }

            if (item.mimeType.startsWith("image/")) {
                thumbnailIcon.visibility = View.GONE
                thumbnailImage.visibility = View.VISIBLE
                Glide.with(thumbnailImage)
                    .load(item.uri)
                    .centerCrop()
                    .placeholder(ColorDrawable(0xFFEFF6FF.toInt()))
                    .error(ColorDrawable(0xFFEFF6FF.toInt()))
                    .into(thumbnailImage)
            } else {
                Glide.with(thumbnailImage).clear(thumbnailImage)
                thumbnailImage.setImageDrawable(null)
                thumbnailImage.visibility = View.GONE
                thumbnailIcon.visibility = View.VISIBLE
                thumbnailIcon.text = fileIcon(item.mimeType)
            }
        }
    }

    private data class SelectedFilePreview(
        val uri: Uri,
        val displayName: String,
        val mimeType: String,
    )

    private fun fileIcon(mimeType: String): String {
        return when {
            mimeType.startsWith("video/") -> "▶"
            mimeType.startsWith("audio/") -> "♪"
            mimeType == "application/pdf" -> "PDF"
            mimeType.startsWith("text/") -> "TXT"
            else -> "📄"
        }
    }

    private fun primaryButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 16f
            typeface = AppTypeface.bold
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedDrawable(COLOR_PRIMARY, dp(18))
            elevation = 0f
            stateListAnimator = null
        }
    }

    private fun sendFiles(record: PairedPcRecord, fileUris: List<Uri>) {
        currentBatch?.close()
        currentBatch = null
        currentRecord = record
        selectedRecord = record
        pairedPcStore.saveLastSelectedSendPcDeviceId(record.pcDeviceId)
        setTransferPanelVisible(true)
        pickerStatusText.visibility = View.GONE
        retryButton.visibility = View.GONE
        retryButton.text = "Retry"
        setPickerEnabled(false)
        setProgressVisible(true)
        setProgressValues(batchPercent = 0, filePercent = 0)
        val fileLabel = if (fileUris.size == 1) "file" else "files"
        statusText.text = "Preparing ${fileUris.size} $fileLabel for ${record.pcName}..."
        Thread {
            val prepared = runCatching { transferClient.prepareSharedFiles(this, fileUris) }
            runOnUiThread {
                prepared.onSuccess { batch ->
                    currentBatch = batch
                    activeTransferStore.saveBatch(batch.toActiveManifests(record.pcDeviceId))
                    startPreparedTransfer(record, batch)
                }.onFailure { exception ->
                    setPickerEnabled(true)
                    setProgressVisible(false)
                    pickerStatusText.visibility = View.VISIBLE
                    val message = PairingErrorMessage.from(exception)
                    statusText.text = "Could not prepare files: $message"
                    showIssueAlert(title = "Could not prepare files", message = message)
                }
            }
        }.start()
    }

    private fun retryCurrentTransfer() {
        val record = currentRecord
        val batch = currentBatch
        if (record == null || batch == null) {
            statusText.text = "Nothing to retry. Share the files again."
            retryButton.visibility = View.GONE
            return
        }

        retryButton.visibility = View.GONE
        startPreparedTransfer(record, batch)
    }

    private fun startPreparedTransfer(record: PairedPcRecord, batch: PreparedTransferBatch) {
        requestTransferNotificationPermissionIfNeeded()
        currentRecord = record
        setTransferPanelVisible(true)
        pickerStatusText.visibility = View.GONE
        setPickerEnabled(false)
        setProgressVisible(true)
        cancelButton.visibility = View.VISIBLE
        retryButton.visibility = View.GONE
        retryButton.text = "Retry"
        statusText.text = "Starting foreground transfer to ${record.pcName}. You can keep this screen open for progress or use the notification."
        try {
            TransferForegroundService.startTransfer(this, batch.batchId, record.pcDeviceId)
        } catch (exception: RuntimeException) {
            setPickerEnabled(true)
            cancelButton.visibility = View.GONE
            retryButton.visibility = View.VISIBLE
            retryButton.text = "Retry"
            val message = PairingErrorMessage.from(exception)
            statusText.text = "Transfer could not start: $message"
            showIssueAlert(
                title = "Transfer could not start",
                message = "$message\n\nKeep NearShare open and tap Retry to try again.",
            )
        }
    }

    private fun handleTransferEvent(intent: Intent) {
        val batchId = intent.getStringExtra(TransferForegroundService.EXTRA_BATCH_ID).orEmpty()
        if (currentBatch?.batchId != null && currentBatch?.batchId != batchId) {
            return
        }

        when (intent.getStringExtra(TransferForegroundService.EXTRA_EVENT_STATUS)) {
            TransferForegroundService.STATUS_PROGRESS -> {
                val pcName = intent.getStringExtra(TransferForegroundService.EXTRA_PC_NAME).orEmpty()
                val progress = FileTransferProgress(
                    fileIndex = intent.getIntExtra(TransferForegroundService.EXTRA_FILE_INDEX, 1),
                    totalFiles = intent.getIntExtra(TransferForegroundService.EXTRA_TOTAL_FILES, 1),
                    fileName = intent.getStringExtra(TransferForegroundService.EXTRA_FILE_NAME).orEmpty(),
                    sentBytes = intent.getLongExtra(TransferForegroundService.EXTRA_SENT_BYTES, 0L),
                    totalBytes = intent.getLongExtra(TransferForegroundService.EXTRA_TOTAL_BYTES, 0L),
                    batchSentBytes = intent.getLongExtra(TransferForegroundService.EXTRA_BATCH_SENT_BYTES, 0L),
                    batchTotalBytes = intent.getLongExtra(TransferForegroundService.EXTRA_BATCH_TOTAL_BYTES, 0L),
                )
                updateTransferProgress(pcName, progress)
            }
            TransferForegroundService.STATUS_COMPLETED -> {
                val pcName = intent.getStringExtra(TransferForegroundService.EXTRA_PC_NAME).orEmpty()
                val fileCount = intent.getIntExtra(TransferForegroundService.EXTRA_TOTAL_FILES, 0)
                val totalBytes = intent.getLongExtra(TransferForegroundService.EXTRA_TOTAL_BYTES, 0L)
                setPickerEnabled(true)
                cancelButton.visibility = View.GONE
                retryButton.visibility = View.GONE
                setProgressValues(batchPercent = 100, filePercent = 100)
                statusText.text = "Completed all: sent $fileCount ${if (fileCount == 1) "file" else "files"} to $pcName (${formatBytes(totalBytes)})."
                currentBatch?.close()
                currentBatch = null
                currentRecord = null
                deviceSelectorSection.visibility = View.GONE
            }
            TransferForegroundService.STATUS_FAILED -> {
                val message = intent.getStringExtra(TransferForegroundService.EXTRA_MESSAGE).orEmpty()
                setPickerEnabled(true)
                cancelButton.visibility = View.GONE
                retryButton.visibility = View.VISIBLE
                retryButton.text = "Resume"
                statusText.text = "Transfer stopped: $message Tap Resume to continue."
                showIssueAlert(
                    title = "Transfer stopped",
                    message = "$message\n\nWhen the device is reachable again, tap Resume to continue this transfer.",
                )
            }
            TransferForegroundService.STATUS_CANCELLED -> {
                setPickerEnabled(true)
                cancelButton.visibility = View.GONE
                retryButton.visibility = View.GONE
                setProgressVisible(false)
                setTransferPanelVisible(false)
                pickerStatusText.visibility = View.VISIBLE
                setPickerStatus("Transfer cancelled. Share the files again to restart it.")
                currentBatch?.close()
                currentBatch = null
                currentRecord = null
            }
        }
    }

    private fun requestTransferNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
    }

    private fun updateTransferProgress(pcName: String, progress: FileTransferProgress) {
        setProgressValues(progress.batchPercent, progress.currentFilePercent)
        statusText.text = formatProgress(pcName, progress)
    }

    private fun formatProgress(pcName: String, progress: FileTransferProgress): String {
        return "Sending ${progress.fileIndex}/${progress.totalFiles} to $pcName: ${progress.fileName} — file ${progress.currentFilePercent}%, overall ${progress.batchPercent}% (${formatBytes(progress.sentBytes)} of ${formatBytes(progress.totalBytes)})"
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) {
            return "$bytes B"
        }
        val kib = bytes / 1024.0
        if (kib < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f KB", kib)
        }
        val mib = kib / 1024.0
        if (mib < 1024.0) {
            return String.format(java.util.Locale.US, "%.1f MB", mib)
        }
        val gib = mib / 1024.0
        return String.format(java.util.Locale.US, "%.1f GB", gib)
    }

    private fun setProgressVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        batchProgressLabel.visibility = visibility
        batchProgressBar.visibility = visibility
        fileProgressLabel.visibility = visibility
        fileProgressBar.visibility = visibility
    }

    private fun setTransferPanelVisible(visible: Boolean) {
        bottomTransferPanel.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun setPickerStatus(message: String) {
        pickerStatusText.text = message
    }

    private fun setContextStatus(message: String) {
        if (bottomTransferPanel.visibility == View.VISIBLE) {
            statusText.text = message
        } else {
            setPickerStatus(message)
        }
    }

    private fun setProgressValues(batchPercent: Int, filePercent: Int) {
        batchProgressBar.progress = batchPercent.coerceIn(0, 100)
        fileProgressBar.progress = filePercent.coerceIn(0, 100)
    }

    private fun progressBar(): ProgressBar {
        return ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable = progressDrawable.mutate()
        }
    }

    private fun progressLabel(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            typeface = AppTypeface.bold
            setTextColor(COLOR_SECONDARY_TEXT)
            setPadding(0, 0, 0, dp(6))
        }
    }

    private fun secondaryButton(text: String): Button {
        return Button(this).apply {
            this.text = text
            setAllCaps(false)
            textSize = 15f
            typeface = AppTypeface.bold
            setTextColor(COLOR_PRIMARY)
            background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(16), dp(1))
            elevation = 0f
            stateListAnimator = null
        }
    }

    private fun setPickerEnabled(enabled: Boolean) {
        pickerEnabled = enabled
        deviceSpinner.isEnabled = enabled && pairedPcDropdownRecords.isNotEmpty()
        deviceSelectorSection.alpha = if (enabled) 1f else 0.55f
        updateSendButtonState()
    }

    private fun updateSendButtonState() {
        val enabled = pickerEnabled && selectedRecord != null && sharedFileUris.isNotEmpty()
        sendButton.isEnabled = enabled
        sendButton.alpha = if (enabled) 1f else 0.55f
    }

    private fun showIssueAlert(title: String, message: String) {
        if (isFinishing || isDestroyed) {
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
        ThemedAlertDialog.apply(dialog)
    }

    private fun roundedDrawable(fillColor: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
        }
    }

    private fun roundedStrokeDrawable(fillColor: Int, strokeColor: Int, radius: Int, strokeWidth: Int): GradientDrawable {
        return roundedDrawable(fillColor, radius).apply {
            setStroke(strokeWidth, strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun androidDeviceName(): String {
        return listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(separator = " ")
            .ifBlank { "Android Phone" }
    }

    companion object {
        const val EXTRA_TARGET_PC_DEVICE_ID = "com.pcmobilelink.nearshare.extra.TARGET_PC_DEVICE_ID"

        private const val COLOR_BACKGROUND = 0xFFF8FAFC.toInt()
        private const val COLOR_TEXT = 0xFF0F172A.toInt()
        private const val COLOR_SECONDARY_TEXT = 0xFF475569.toInt()
        private const val COLOR_MUTED = 0xFF64748B.toInt()
        private const val COLOR_BORDER = 0xFFE2E8F0.toInt()
        private const val COLOR_SUBTLE_SURFACE = 0xFFF8FAFC.toInt()
        private const val COLOR_PRIMARY = 0xFF2563EB.toInt()
        private const val SELECT_DEVICE_LABEL = "Select device"
        private const val AndroidPairingOfferLifetimeSeconds = 5 * 60L
        private const val ManualPrivateConnectionLifetimeSeconds = 10 * 60L
        private const val PrivateConnectionSecurityCodeLength = 9
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 4102
    }
}
