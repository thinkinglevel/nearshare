@file:Suppress("DEPRECATION")

package com.pcmobilelink.nearshare

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.pcmobilelink.nearshare.connectivity.AndroidPrivateConnectionHost
import com.pcmobilelink.nearshare.connectivity.AndroidPrivateConnectionJoiner
import com.pcmobilelink.nearshare.connectivity.PrivateConnectionOffer
import com.pcmobilelink.nearshare.connectivity.PrivateConnectionOfferCodec
import com.pcmobilelink.nearshare.connectivity.PrivateConnectionSecurityCode
import com.pcmobilelink.nearshare.diagnostics.NearShareDiagnostics
import com.pcmobilelink.nearshare.pairing.AndroidLocalPairingServer
import com.pcmobilelink.nearshare.pairing.LocalPairingPendingRequest
import com.pcmobilelink.nearshare.pairing.PairingClient
import com.pcmobilelink.nearshare.pairing.PairingCodeInput
import com.pcmobilelink.nearshare.pairing.PairingEndpointCandidate
import com.pcmobilelink.nearshare.pairing.PairingErrorMessage
import com.pcmobilelink.nearshare.pairing.PairingPayload
import com.pcmobilelink.nearshare.pairing.PairingPayloadCodec
import com.pcmobilelink.nearshare.pairing.PairingRequestResult
import com.pcmobilelink.nearshare.pairing.PairingShortCode
import com.pcmobilelink.nearshare.pairing.qr.QrCodeBitmap
import com.pcmobilelink.nearshare.pairing.qr.QrScannerActivity
import com.pcmobilelink.nearshare.receiver.AndroidReceiveEndpointMetadata
import com.pcmobilelink.nearshare.receiver.AndroidReceiveEndpointRegistry
import com.pcmobilelink.nearshare.receiver.AndroidReceiveForegroundService
import com.pcmobilelink.nearshare.receiver.ReceiveTransferProgress
import com.pcmobilelink.nearshare.receiver.ReceiveTransferStatus
import com.pcmobilelink.nearshare.settings.BootRestoreAction
import com.pcmobilelink.nearshare.settings.ReceiveFolder
import com.pcmobilelink.nearshare.settings.ReceiveSettings
import com.pcmobilelink.nearshare.settings.ReceiveSettingsStore
import com.pcmobilelink.nearshare.share.PairedPcShareSelection
import com.pcmobilelink.nearshare.share.PairedPcShareTargets
import com.pcmobilelink.nearshare.share.ShareActivity
import com.pcmobilelink.nearshare.sound.TransferSoundPlayer
import com.pcmobilelink.nearshare.sound.TransferSoundResult
import com.pcmobilelink.nearshare.storage.AndroidDeviceIdentityStore
import com.pcmobilelink.nearshare.storage.PairedPcRecord
import com.pcmobilelink.nearshare.storage.PairedPcStore
import com.pcmobilelink.nearshare.transfer.ActiveTransferManifestStore
import com.pcmobilelink.nearshare.transfer.ActiveTransferStatus
import com.pcmobilelink.nearshare.transfer.PairedPcReachabilityClient
import com.pcmobilelink.nearshare.transfer.TransferForegroundService
import com.pcmobilelink.nearshare.ui.AppTypeface
import com.pcmobilelink.nearshare.ui.MainNavigationSection
import com.pcmobilelink.nearshare.ui.ThemedAlertDialog
import com.pcmobilelink.nearshare.updates.GitHubReleaseUpdateChecker
import com.pcmobilelink.nearshare.updates.ReleaseUpdateCheckResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var contentHost: FrameLayout
    private lateinit var bottomNav: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var statusCard: LinearLayout
    private lateinit var statusTitleText: TextView
    private lateinit var statusBodyText: TextView
    private lateinit var pairedPcsList: LinearLayout
    private lateinit var transferStatusTitleText: TextView
    private lateinit var transferStatusBodyText: TextView
    private lateinit var transferProgressBar: ProgressBar
    private lateinit var transferCurrentFileText: TextView
    private lateinit var receiveModeSummaryText: TextView
    private lateinit var settingsFolderTitleText: TextView
    private lateinit var settingsFolderBodyText: TextView
    private lateinit var alwaysOnSwitch: Switch
    private lateinit var transferSoundsSwitch: Switch
    private lateinit var bootRestoreBodyText: TextView
    private lateinit var updateStatusText: TextView
    private lateinit var transferSendDeviceSpinner: Spinner
    private lateinit var transferSendButton: Button
    private lateinit var identityStore: AndroidDeviceIdentityStore
    private lateinit var pairedPcStore: PairedPcStore
    private lateinit var receiveSettingsStore: ReceiveSettingsStore
    private lateinit var activeTransferStore: ActiveTransferManifestStore
    private lateinit var privateConnectionHost: AndroidPrivateConnectionHost
    private lateinit var privateConnectionJoiner: AndroidPrivateConnectionJoiner

    private val bottomNavItems = mutableMapOf<MainNavigationSection, TextView>()
    private val transferEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleTransferEvent(intent ?: return)
        }
    }

    private val updateDownloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                return
            }
            handleUpdateDownloadComplete(
                intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L),
            )
        }
    }
    private var selectedSection = MainNavigationSection.defaultSection()
    private var receiveSettings = ReceiveSettings.defaultSettings()
    private var privateConnectionOffer: PrivateConnectionOffer? = null
    private var localPairingServer: AndroidLocalPairingServer? = null
    private var pendingPrivateConnectionJoinOffer: PrivateConnectionOffer? = null
    private var joinedPrivateConnectionName: String? = null
    private var pendingPrivateConnectionRetryBatchId: String? = null
    private var privateConnectionAutoReceiveStarted = false
    private var pendingReceiveNotificationPermissionAction: (() -> Unit)? = null
    private var pendingUpdateDownloadRequest: ReleaseUpdateCheckResult.Checked? = null
    private var transferSendPairedPcDropdownRecords: List<PairedPcRecord> = emptyList()
    private var transferSelectedSendRecord: PairedPcRecord? = null
    private var pendingUpdateDownload: PendingUpdateDownload? = null
    private var downloadedUpdateForInstall: DownloadedUpdate? = null
    private var waitingForUnknownSourcePermission = false
    private var qrScanPurpose = QrScanPurpose.Pairing
    private var currentDashboardStatus = DashboardStatus(
        title = "Ready to pair",
        message = "Scan a pairing code from Windows or another Android device, or show this device's code.",
        tone = StatusTone.Neutral,
    )

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        handleQrScanResult(result.resultCode, result.data)
    }

    private val receiveFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        handleReceiveFolderSelected(uri)
    }

    private val transferFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        handleTransferFilesSelected(uris)
    }

    private val receiveNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingReceiveNotificationPermissionAction
        pendingReceiveNotificationPermissionAction = null
        action?.invoke()
        if (!granted) {
            setStatus(
                title = "Notifications are off",
                message = "Receiving can still run, but Android may hide the receive notification. Enable NearShare notifications in system settings to keep receiver status visible.",
                tone = StatusTone.Error,
            )
        }
    }

    private val updateStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingRequest = pendingUpdateDownloadRequest
        pendingUpdateDownloadRequest = null
        if (granted && pendingRequest != null) {
            startUpdateDownload(pendingRequest)
        } else {
            updateStatusText.text = "Storage permission is needed to download updates."
            showStatusDialog(
                title = "Permission needed",
                message = "NearShare needs storage permission on this Android version to save the update in Downloads.",
            )
        }
    }

    private val nearbyWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startPrivateConnection()
        } else {
            setPrivateConnectionStatus(
                title = "Permission needed",
                message = "Private connection needs Nearby devices permission.",
                tone = StatusTone.Error,
            )
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
            setPrivateConnectionStatus(
                title = "Permission needed",
                message = "Private connection needs nearby Wi-Fi permission.",
                tone = StatusTone.Error,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        identityStore = AndroidDeviceIdentityStore(this)
        pairedPcStore = PairedPcStore(this)
        receiveSettingsStore = ReceiveSettingsStore(this)
        activeTransferStore = ActiveTransferManifestStore(File(filesDir, "active-transfers.json"))
        receiveSettings = receiveSettingsStore.load()
        privateConnectionHost = AndroidPrivateConnectionHost(this) {
            privateConnectionOffer = null
            runOnUiThread {
                renderDashboardIfVisible()
                setPrivateConnectionStatus(
                    title = "Private connection stopped",
                    message = "Create or join a private connection again when paired devices are not on the same Wi-Fi.",
                    tone = StatusTone.Neutral,
                )
            }
        }
        privateConnectionJoiner = AndroidPrivateConnectionJoiner(this)

        setContentView(buildShell())
        PairedPcShareTargets.publish(this, pairedPcStore.loadAll())
        navigateTo(MainNavigationSection.defaultSection())
        setStatus(
            title = "Ready to pair",
            message = "Scan a pairing code from Windows or another Android device, or show this device's code.",
            tone = StatusTone.Neutral,
        )
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(TransferForegroundService.ACTION_TRANSFER_EVENT).apply {
            addAction(AndroidReceiveForegroundService.ACTION_RECEIVE_EVENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(transferEventReceiver, filter, RECEIVER_NOT_EXPORTED)
            registerReceiver(updateDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), RECEIVER_EXPORTED)
        } else {
            registerReceiver(transferEventReceiver, filter)
            registerReceiver(updateDownloadReceiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForUnknownSourcePermission && canRequestPackageInstalls()) {
            waitingForUnknownSourcePermission = false
            downloadedUpdateForInstall?.let { showUpdateDownloadCompleteDialog(it) }
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(transferEventReceiver) }
        runCatching { unregisterReceiver(updateDownloadReceiver) }
        super.onStop()
    }

    override fun onDestroy() {
        localPairingServer?.close()
        localPairingServer = null
        if (::privateConnectionHost.isInitialized) {
            privateConnectionHost.stop()
        }
        if (::privateConnectionJoiner.isInitialized) {
            privateConnectionJoiner.disconnect()
        }
        super.onDestroy()
    }

    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    private fun buildShell(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BACKGROUND)
        }
        contentHost = FrameLayout(this).apply {
            setBackgroundColor(COLOR_BACKGROUND)
        }
        bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = topStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(1))
            elevation = 0f
        }

        MainNavigationSection.bottomNavigationOrder().forEach { section ->
            val item = bottomNavItem(section)
            bottomNavItems[section] = item
            bottomNav.addView(
                item,
                LinearLayout.LayoutParams(0, dp(54), 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                },
            )
        }

        root.addView(contentHost, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            contentHost.setPadding(bars.left, bars.top, bars.right, 0)
            bottomNav.setPadding(dp(10) + bars.left, dp(8), dp(10) + bars.right, dp(8) + bars.bottom)
            insets
        }

        return root
    }

    private fun bottomNavItem(section: MainNavigationSection): TextView {
        return TextView(this).apply {
            text = section.label
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = AppTypeface.bold
            includeFontPadding = false
            setOnClickListener { navigateTo(section) }
        }
    }

    private fun navigateTo(section: MainNavigationSection) {
        selectedSection = section
        contentHost.removeAllViews()
        contentHost.addView(
            when (section) {
                MainNavigationSection.Dashboard -> buildDashboardView()
                MainNavigationSection.Transfer -> buildTransferView()
                MainNavigationSection.Settings -> buildSettingsView()
            },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        updateBottomNavigation()
    }

    private fun updateBottomNavigation() {
        bottomNavItems.forEach { (section, item) ->
            val selected = section == selectedSection
            item.setTextColor(if (selected) COLOR_PRIMARY else COLOR_MUTED)
            item.background = if (selected) {
                roundedDrawable(COLOR_PRIMARY_SOFT, dp(18))
            } else {
                roundedDrawable(Color.TRANSPARENT, dp(18))
            }
        }
    }

    private fun buildDashboardView(): View {
        val content = screenContent()
        content.addView(appHeader("Dashboard", "Pair and monitor trusted nearby devices."))
        content.addView(privateConnectionCard())
        content.addView(pairingHeroCard())
        content.addView(statusSection())
        content.addView(currentPairCard())
        return scrollContainer(content)
    }

    private fun transferSendCard(): View {
        val card = cardContainer()
        card.addView(titleText("Send files", 22f))
        card.addView(
            bodyText("Choose a paired device, then pick one or more files to send locally.").apply {
                setPadding(0, dp(10), 0, dp(14))
            },
        )
        transferSendDeviceSpinner = Spinner(this, Spinner.MODE_DROPDOWN).apply {
            background = roundedStrokeDrawable(COLOR_SUBTLE_SURFACE, COLOR_BORDER, dp(16), dp(1))
            setPadding(dp(10), 0, dp(10), 0)
            prompt = SELECT_DEVICE_LABEL
        }
        card.addView(
            transferSendDeviceSpinner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply {
                bottomMargin = dp(12)
            },
        )
        transferSendButton = primaryButton("Send files").apply {
            setOnClickListener { openTransferFilePicker() }
        }
        card.addView(transferSendButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)))
        renderTransferSendTargets()
        return withBottomMargin(card, dp(16))
    }

    private fun renderTransferSendTargets() {
        val pairedPcs = pairedPcStore.loadAll()
        transferSendPairedPcDropdownRecords = pairedPcs
        val initialTarget = PairedPcShareSelection.selectInitialDropdownTarget(
            records = pairedPcs,
            requestedPcDeviceId = null,
            lastSelectedPcDeviceId = pairedPcStore.loadLastSelectedSendPcDeviceId(),
        ) ?: pairedPcs.firstOrNull()
        populateTransferSendDeviceDropdown(pairedPcs, initialTarget)
        updateTransferSendButtonState()
    }

    private fun populateTransferSendDeviceDropdown(pairedPcs: List<PairedPcRecord>, initialTarget: PairedPcRecord?) {
        val items = listOf(PairedDeviceDropdownItem(record = null, label = SELECT_DEVICE_LABEL)) +
            pairedPcs.map { record -> PairedDeviceDropdownItem(record = record, label = record.pcName) }
        val adapter = PairedDeviceDropdownAdapter(items)
        transferSendDeviceSpinner.onItemSelectedListener = null
        transferSendDeviceSpinner.adapter = adapter

        val selectedIndex = initialTarget?.let { target ->
            pairedPcs.indexOfFirst { it.pcDeviceId.equals(target.pcDeviceId, ignoreCase = true) }
                .takeIf { it >= 0 }
                ?.plus(1)
        } ?: 0
        transferSelectedSendRecord = initialTarget
        transferSendDeviceSpinner.setSelection(selectedIndex, false)
        transferSendDeviceSpinner.isEnabled = pairedPcs.isNotEmpty()
        transferSendDeviceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val record = (parent?.getItemAtPosition(position) as? PairedDeviceDropdownItem)?.record
                transferSelectedSendRecord = record
                if (record != null) {
                    pairedPcStore.saveLastSelectedSendPcDeviceId(record.pcDeviceId)
                } else {
                    pairedPcStore.clearLastSelectedSendPcDeviceId()
                }
                updateTransferSendButtonState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                transferSelectedSendRecord = null
                pairedPcStore.clearLastSelectedSendPcDeviceId()
                updateTransferSendButtonState()
            }
        }
    }

    private fun updateTransferSendButtonState() {
        val enabled = transferSelectedSendRecord != null && transferSendPairedPcDropdownRecords.isNotEmpty()
        transferSendButton.isEnabled = enabled
        transferSendButton.alpha = if (enabled) 1f else 0.55f
    }

    private fun openTransferFilePicker() {
        val record = transferSelectedSendRecord
        if (record == null) {
            showStatusDialog(
                title = "Select a device",
                message = "Choose a paired device before selecting files.",
            )
            return
        }
        pairedPcStore.saveLastSelectedSendPcDeviceId(record.pcDeviceId)
        transferFilePickerLauncher.launch(arrayOf("*/*"))
    }

    private fun handleTransferFilesSelected(uris: List<Uri>) {
        val record = transferSelectedSendRecord
        if (record == null || uris.isEmpty()) {
            return
        }

        val sendIntent = Intent(this, ShareActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            putExtra(ShareActivity.EXTRA_TARGET_PC_DEVICE_ID, record.pcDeviceId)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        startActivity(sendIntent)
    }

    private inner class PairedDeviceDropdownAdapter(
        items: List<PairedDeviceDropdownItem>,
    ) : ArrayAdapter<PairedDeviceDropdownItem>(this, android.R.layout.simple_spinner_item, items) {
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
                background = if (isDropdown) roundedDrawable(Color.WHITE, 0) else null
            }
        }
    }

    private data class PairedDeviceDropdownItem(
        val record: PairedPcRecord?,
        val label: String,
    )

    private fun pairingHeroCard(): View {
        val card = cardContainer()
        card.addView(titleText("Pair a device", 22f))
        card.addView(
            bodyText("Scan the QR code shown by NearShare on Windows or another Android device. To pair from another device, show this device's code.").apply {
                setPadding(0, dp(10), 0, dp(18))
            },
        )
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scanButton = primaryButton("Scan pairing QR").apply {
            setOnClickListener { startQrScanner(QrScanPurpose.Pairing) }
        }
        actionRow.addView(
            scanButton,
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(6) },
        )
        actionRow.addView(
            secondaryButton(if (localPairingServer == null) "Show this code" else "Stop code").apply {
                setOnClickListener {
                    if (localPairingServer == null) {
                        startLocalPairingOffer()
                    } else {
                        stopLocalPairingOffer()
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginStart = dp(6) },
        )
        card.addView(actionRow)
        card.addView(
            secondaryButton("Enter code").apply {
                setOnClickListener { showManualPairingCodeDialog() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                topMargin = dp(10)
            },
        )
        card.addView(
            captionText("Use the same Wi-Fi network, or create a private connection before pairing or transferring.").apply {
                setPadding(0, dp(14), 0, 0)
            },
        )
        localPairingServer?.let { server -> addLocalPairingOfferDetails(card, server) }
        return withBottomMargin(card, dp(16))
    }

    private fun addLocalPairingOfferDetails(card: LinearLayout, server: AndroidLocalPairingServer) {
        card.addView(
            captionText("Pairing code expires in 5 minutes. Keep this screen open until the other device is approved.").apply {
                setPadding(0, dp(16), 0, dp(10))
            },
        )
        card.addView(pairingShortCodeBoxes(server.offer.shortCode.orEmpty()))
        val qrSize = dp(184)
        card.addView(
            ImageView(this).apply {
                setImageBitmap(QrCodeBitmap.create(server.encodedOffer, qrSize))
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            },
            LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            },
        )
        card.addView(
            secondaryButton("Copy setup link").apply {
                setOnClickListener { copyPairingSetupLink(server.encodedOffer) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply {
                bottomMargin = dp(12)
            },
        )

        val pendingRequest = server.currentPendingRequest()
        if (pendingRequest == null) {
            card.addView(
                bodyText("Waiting for a device to connect with this code.").apply {
                    setPadding(0, dp(12), 0, 0)
                },
            )
        } else {
            card.addView(localPairingPendingRequestView(pendingRequest))
        }
    }

    private fun pairingShortCodeBoxes(shortCode: String): View {
        val normalized = PairingShortCode.normalize(shortCode)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(14))
        }
        val parts = if (normalized.length == 9) {
            listOf(normalized.substring(0, 3), normalized.substring(3, 6), normalized.substring(6, 9))
        } else {
            listOf("---", "---", "---")
        }
        parts.forEachIndexed { index, part ->
            row.addView(
                TextView(this).apply {
                    text = part
                    textSize = 22f
                    typeface = AppTypeface.bold
                    setTextColor(COLOR_PRIMARY)
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    background = roundedStrokeDrawable(COLOR_PRIMARY_SOFT, COLOR_PRIMARY_STROKE, dp(14), dp(1))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(8)
                },
            )
        }
        return row
    }

    private fun copyPairingSetupLink(pairingUri: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("NearShare setup link", pairingUri))
        Toast.makeText(this, "Advanced setup link copied", Toast.LENGTH_SHORT).show()
    }

    private fun localPairingPendingRequestView(request: LocalPairingPendingRequest): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedStrokeDrawable(COLOR_PRIMARY_SOFT, COLOR_PRIMARY_STROKE, dp(14), dp(1))
        }
        panel.addView(titleText("${request.deviceName} wants to pair", 17f))
        panel.addView(
            bodyText("Approve only if this is the device you are pairing now.").apply {
                setPadding(0, dp(8), 0, dp(12))
            },
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            secondaryButton("Reject").apply {
                setOnClickListener { rejectLocalPairingRequest(request.requestId) }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) },
        )
        row.addView(
            primaryButton("Approve").apply {
                setOnClickListener { approveLocalPairingRequest(request.requestId) }
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) },
        )
        panel.addView(row)
        return withBottomMargin(panel, dp(0)).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(14)
        }
    }

    private fun statusSection(): View {
        statusCard = cardContainer().apply {
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        statusTitleText = TextView(this).apply {
            textSize = 15f
            typeface = AppTypeface.bold
            includeFontPadding = false
        }
        statusBodyText = TextView(this).apply {
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1.0f)
            typeface = AppTypeface.regular
            setPadding(0, dp(8), 0, 0)
        }
        statusCard.addView(statusTitleText)
        statusCard.addView(statusBodyText)
        applyDashboardStatus(currentDashboardStatus)
        return withBottomMargin(statusCard, dp(16))
    }

    private fun currentPairCard(): View {
        val card = cardContainer()
        card.addView(titleText("Current pair", 20f))
        card.addView(
            bodyText("Trusted devices paired with this phone. Send from Dashboard or the Android share sheet; receive from the Transfer tab or Always On receive.").apply {
                setPadding(0, dp(8), 0, dp(12))
            },
        )
        pairedPcsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(pairedPcsList)
        renderPairedPcs()
        return card
    }

    private fun buildTransferView(): View {
        val content = screenContent()
        content.addView(appHeader("Transfer", "Send, receive, and track active file movement."))
        content.addView(transferSendCard())
        content.addView(receiveModeCard())
        content.addView(transferProgressCard())
        return scrollContainer(content)
    }

    private fun receiveModeCard(): View {
        val card = cardContainer()
        card.addView(titleText("Receive files", 22f))
        receiveModeSummaryText = bodyText(receiveModeSummary()).apply {
            setPadding(0, dp(10), 0, dp(16))
        }
        card.addView(receiveModeSummaryText)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            secondaryButton("Manual receive").apply {
                setOnClickListener {
                    requestReceiveNotificationPermissionIfNeeded {
                        AndroidReceiveForegroundService.startManual(this@MainActivity)
                        setTransferIdleText("Manual receive is on. Keep this phone awake while sending from your paired device.")
                    }
                }
            },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) },
        )
        row.addView(
            primaryButton(if (receiveSettings.alwaysOnReceiveEnabled) "Always On active" else "Enable Always On").apply {
                setOnClickListener {
                    updateAlwaysOnReceive(!receiveSettings.alwaysOnReceiveEnabled)
                }
            },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(8) },
        )
        card.addView(row)
        return withBottomMargin(card, dp(16))
    }

    private fun privateConnectionCard(): View {
        val card = cardContainer()
        card.addView(titleText("Private connection", 22f))
        val offer = privateConnectionOffer
        val joinedName = joinedPrivateConnectionName
        if (offer == null && joinedName == null) {
            card.addView(
                bodyText("Use this when paired devices are not on the same Wi-Fi. Keep NearShare open while the other device connects.").apply {
                    setPadding(0, dp(10), 0, dp(16))
                },
            )
            card.addView(
                primaryButton("Create private connection").apply {
                    setOnClickListener { requestPrivateConnectionStart() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)),
            )
            val joinRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, 0)
            }
            joinRow.addView(
                secondaryButton("Scan connection QR").apply {
                    setOnClickListener { startQrScanner(QrScanPurpose.PrivateConnection) }
                },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(6) },
            )
            joinRow.addView(
                secondaryButton("Enter details").apply {
                    setOnClickListener { showManualPrivateConnectionDialog() }
                },
                LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(6) },
            )
            card.addView(joinRow)
        } else if (joinedName != null) {
            card.addView(
                bodyText("Connected to $joinedName. Try the transfer again.").apply {
                    setPadding(0, dp(10), 0, dp(16))
                },
            )
            card.addView(
                secondaryButton("Disconnect private connection").apply {
                    setOnClickListener { disconnectPrivateConnection() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)),
            )
        } else {
            val activeOffer = requireNotNull(offer)
            card.addView(
                bodyText("On the other device, choose Connect private connection, then scan or enter the details below.").apply {
                    setPadding(0, dp(10), 0, dp(14))
                },
            )
            val qrSize = dp(184)
            card.addView(privateConnectionQrCaption("Scan in NearShare"))
            addPrivateConnectionQr(card, PrivateConnectionOfferCodec.encode(activeOffer), qrSize)
            card.addView(privateConnectionQrCaption("Scan with phone camera"))
            addPrivateConnectionQr(card, PrivateConnectionOfferCodec.encodeWifiQrPayload(activeOffer), qrSize)
            card.addView(privateConnectionDetail("Connection name", activeOffer.connectionName))
            card.addView(privateConnectionDetail("Password", activeOffer.password.ifBlank { "No password" }))
            card.addView(privateConnectionDetail("Security code", PrivateConnectionSecurityCode.format(activeOffer.code)))
            card.addView(
                secondaryButton("Stop private connection").apply {
                    setOnClickListener { stopPrivateConnection() }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply {
                    topMargin = dp(14)
                },
            )
        }
        return withBottomMargin(card, dp(16))
    }

    private fun privateConnectionQrCaption(label: String): View {
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = AppTypeface.bold
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }
    }

    private fun addPrivateConnectionQr(card: LinearLayout, content: String, qrSize: Int) {
        card.addView(
            ImageView(this).apply {
                setImageBitmap(QrCodeBitmap.create(content, qrSize))
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            },
            LinearLayout.LayoutParams(qrSize, qrSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(14)
            },
        )
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

    private fun transferProgressCard(): View {
        val card = cardContainer()
        card.addView(titleText("Current progress", 22f))
        transferStatusTitleText = TextView(this).apply {
            text = "No active transfer"
            textSize = 16f
            typeface = AppTypeface.bold
            setTextColor(COLOR_TEXT)
            setPadding(0, dp(12), 0, 0)
        }
        transferStatusBodyText = bodyText("Send and receive progress will appear here while active.").apply {
            setPadding(0, dp(6), 0, dp(14))
        }
        transferProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressDrawable.setTint(COLOR_PRIMARY)
        }
        transferCurrentFileText = captionText("Current file: none").apply {
            setPadding(0, dp(10), 0, 0)
        }
        card.addView(transferStatusTitleText)
        card.addView(transferStatusBodyText)
        card.addView(transferProgressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        card.addView(transferCurrentFileText)
        return card
    }

    private fun buildSettingsView(): View {
        receiveSettings = receiveSettingsStore.load()
        val content = screenContent()
        content.addView(appHeader("Settings", "Control receive storage and background readiness."))
        content.addView(receiveFolderCard())
        content.addView(alwaysOnSettingsCard())
        content.addView(transferSoundsSettingsCard())
        content.addView(bootPersistenceCard())
        content.addView(updatesSettingsCard())
        return scrollContainer(content)
    }

    private fun receiveFolderCard(): View {
        val card = cardContainer()
        card.addView(titleText("Receive folder", 22f))
        val folderSummary = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedStrokeDrawable(COLOR_SUBTLE_SURFACE, COLOR_BORDER, dp(18), dp(1))
            isClickable = true
            isFocusable = true
            setOnClickListener { openReceiveFolder() }
        }
        settingsFolderTitleText = TextView(this).apply {
            text = receiveSettings.receiveFolder.displayName
            textSize = 18f
            typeface = AppTypeface.bold
            setTextColor(COLOR_TEXT)
        }
        settingsFolderBodyText = bodyText(receiveFolderDescription()).apply {
            setPadding(0, dp(6), 0, 0)
        }
        folderSummary.addView(settingsFolderTitleText)
        folderSummary.addView(settingsFolderBodyText)
        card.addView(
            folderSummary,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
                bottomMargin = dp(16)
            },
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            secondaryButton("Use Downloads").apply {
                setOnClickListener { updateReceiveFolder(ReceiveFolder.DefaultDownloads) }
            },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(8) },
        )
        row.addView(
            primaryButton("Choose custom").apply {
                setOnClickListener { receiveFolderLauncher.launch(null) }
            },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(8) },
        )
        card.addView(row)
        return withBottomMargin(card, dp(16))
    }

    private fun alwaysOnSettingsCard(): View {
        val card = cardContainer()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(titleText("Always On receive", 22f))
        textColumn.addView(
                bodyText("Keep a visible foreground notification so paired devices can send files while the phone is awake and Android allows the receiver to run.").apply {
                setPadding(0, dp(8), 0, 0)
            },
        )
        alwaysOnSwitch = Switch(this).apply {
            isChecked = receiveSettings.alwaysOnReceiveEnabled
            typeface = AppTypeface.regular
            setOnCheckedChangeListener { _, checked -> updateAlwaysOnReceive(checked) }
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(alwaysOnSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        card.addView(row)
        return withBottomMargin(card, dp(16))
    }

    private fun transferSoundsSettingsCard(): View {
        val card = cardContainer()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(titleText("Transfer sounds", 22f))
        textColumn.addView(
            bodyText("Play one sound when a send or receive finishes. Progress stays silent.").apply {
                setPadding(0, dp(8), 0, 0)
            },
        )
        transferSoundsSwitch = Switch(this).apply {
            isChecked = receiveSettings.transferSoundsEnabled
            typeface = AppTypeface.regular
            setOnCheckedChangeListener { _, checked -> updateTransferSounds(checked) }
        }
        row.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(transferSoundsSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        card.addView(row)
        return withBottomMargin(card, dp(16))
    }

    private fun bootPersistenceCard(): View {
        val card = cardContainer()
        card.addView(titleText("After phone restart", 22f))
        bootRestoreBodyText = bodyText(bootPersistenceDescription()).apply {
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(bootRestoreBodyText)
        return withBottomMargin(card, dp(16))
    }

    private fun updatesSettingsCard(): View {
        val card = cardContainer()
        card.addView(titleText("Updates", 22f))
        updateStatusText = bodyText("Installed version ${installedAppVersionName()}. Checks use GitHub Releases.").apply {
            setPadding(0, dp(8), 0, dp(16))
        }
        card.addView(updateStatusText)
        card.addView(
            primaryButton("Check for updates").apply {
                setOnClickListener { checkForAppUpdates() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)),
        )
        return card
    }

    private fun appHeader(title: String, subtitle: String): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(24))
        }
        val mark = TextView(this).apply {
            text = "N"
            gravity = Gravity.CENTER
            textSize = 18f
            typeface = AppTypeface.bold
            setTextColor(Color.WHITE)
            background = roundedDrawable(COLOR_PRIMARY, dp(14))
        }
        header.addView(mark, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(12) })

        val textColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(
            TextView(this).apply {
                text = title
                textSize = 30f
                typeface = AppTypeface.bold
                setTextColor(COLOR_TEXT)
                includeFontPadding = false
            },
        )
        textColumn.addView(
            TextView(this).apply {
                text = subtitle
                textSize = 14f
                typeface = AppTypeface.regular
                setTextColor(COLOR_MUTED)
                includeFontPadding = false
                setPadding(0, dp(4), 0, 0)
            },
        )
        header.addView(textColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return header
    }

    private fun handleQrScanResult(resultCode: Int, data: Intent?) {
        val purpose = qrScanPurpose
        qrScanPurpose = QrScanPurpose.Pairing
        if (resultCode != Activity.RESULT_OK) {
            if (purpose == QrScanPurpose.PrivateConnection) {
                setPrivateConnectionStatus(
                    title = "Scan cancelled",
                    message = "Scan a private connection QR when you are ready to connect.",
                    tone = StatusTone.Neutral,
                )
            } else {
                setStatus(
                    title = "Scan cancelled",
                    message = "Tap Scan pairing QR when you are ready to try again.",
                    tone = StatusTone.Neutral,
                )
            }
            return
        }

        val scannedText = data?.getStringExtra(QrScannerActivity.EXTRA_SCANNED_TEXT).orEmpty()
        if (scannedText.isBlank()) {
            if (purpose == QrScanPurpose.PrivateConnection) {
                setPrivateConnectionStatus(
                    title = "No QR code found",
                    message = "The scanner did not return private connection details. Try again with the QR code fully visible.",
                    tone = StatusTone.Error,
                )
            } else {
                setStatus(
                    title = "No QR code found",
                    message = "The scanner did not return a pairing code. Try again with the pairing QR code fully visible.",
                    tone = StatusTone.Error,
                )
            }
            return
        }

        if (purpose == QrScanPurpose.PrivateConnection) {
            startPrivateConnectionJoinFromCode(scannedText)
        } else {
            startPairingFromCode(scannedText)
        }
    }

    private fun startQrScanner(purpose: QrScanPurpose) {
        qrScanPurpose = purpose
        qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
    }

    private fun showManualPairingCodeDialog() {
        val codeInput = pairingCodeDialogInput()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(privateConnectionDialogLabel("Pairing code"))
            addView(codeInput.root)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enter pairing code")
            .setView(content)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Connect", null)
            .create()
        dialog.setOnShowListener {
            ThemedAlertDialog.apply(dialog)
            codeInput.input.requestFocus()
            getSystemService(InputMethodManager::class.java)?.showSoftInput(
                codeInput.input,
                InputMethodManager.SHOW_IMPLICIT,
            )
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = PairingShortCode.normalize(codeInput.input.text.toString())
                if (!PairingShortCode.isValid(code)) {
                    setStatus(
                        title = "Check pairing code",
                        message = "Enter the 9-character code shown on the other device.",
                        tone = StatusTone.Error,
                    )
                    return@setOnClickListener
                }

                dialog.dismiss()
                startPairingFromCode(code)
            }
        }
        dialog.show()
    }

    private fun pairingCodeDialogInput(): PairingCodeDialogInput {
        return segmentedCodeDialogInput(PairingCodeLength) { value -> PairingShortCode.normalize(value) }
    }

    private fun privateConnectionSecurityCodeDialogInput(): PairingCodeDialogInput {
        return segmentedCodeDialogInput(PrivateConnectionSecurityCodeLength) { value ->
            PrivateConnectionSecurityCode.normalize(value)
        }
    }

    private fun segmentedCodeDialogInput(
        codeLength: Int,
        normalize: (String) -> String,
    ): PairingCodeDialogInput {
        val boxes = mutableListOf<TextView>()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(codeLength + 2))
            setSingleLine(true)
            setTextColor(Color.TRANSPARENT)
            setHintTextColor(Color.TRANSPARENT)
            background = null
            isCursorVisible = false
            setPadding(0, 0, 0, 0)
            minHeight = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                input.requestFocus()
                getSystemService(InputMethodManager::class.java)?.showSoftInput(
                    input,
                    InputMethodManager.SHOW_IMPLICIT,
                )
            }
        }

        repeat(codeLength) { index ->
            val box = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 15f
                typeface = AppTypeface.bold
                setTextColor(COLOR_TEXT)
                background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(9), dp(1))
                includeFontPadding = false
            }
            boxes += box
            row.addView(
                box,
                LinearLayout.LayoutParams(dp(26), dp(42)).apply {
                    leftMargin = if (index == 0) 0 else if (index % 3 == 0) dp(8) else dp(4)
                },
            )
        }

        fun updateBoxes(value: String) {
            val normalized = normalize(value).take(codeLength)
            boxes.forEachIndexed { index, box ->
                val hasCharacter = index < normalized.length
                val isActive = index == normalized.length && normalized.length < codeLength
                box.text = if (hasCharacter) normalized[index].toString() else ""
                box.background = when {
                    hasCharacter -> roundedStrokeDrawable(COLOR_PRIMARY_SOFT, COLOR_PRIMARY_STROKE, dp(9), dp(1))
                    isActive -> roundedStrokeDrawable(Color.WHITE, COLOR_PRIMARY, dp(9), dp(2))
                    else -> roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(9), dp(1))
                }
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            private var updating = false

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (updating) return
                val normalized = normalize(editable?.toString().orEmpty()).take(codeLength)
                if (editable?.toString() != normalized) {
                    updating = true
                    input.setText(normalized)
                    input.setSelection(normalized.length)
                    updating = false
                }
                updateBoxes(normalized)
            }
        })
        updateBoxes("")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)))
            addView(input, LinearLayout.LayoutParams(1, 1))
        }

        return PairingCodeDialogInput(root, input)
    }

    private fun startLocalPairingOffer() {
        setPairingControlsEnabled(false)
        setStatus(
            title = "Starting pairing code",
            message = "Preparing this device to pair and receive files locally.",
            tone = StatusTone.Progress,
        )
        AndroidReceiveForegroundService.stop(this)
        localPairingServer?.close()
        localPairingServer = null
        runOnUiThread { renderDashboardIfVisible() }

        Thread {
            try {
                val server = AndroidLocalPairingServer.start(
                    context = this,
                    deviceName = androidDeviceName(),
                    devicePublicKey = identityStore.devicePublicKey(),
                    lifetimeSeconds = AndroidPairingOfferLifetimeSeconds,
                    progressChanged = ::handleHostedReceiveProgress,
                    onPendingRequestChanged = {
                        runOnUiThread {
                            if (selectedSection == MainNavigationSection.Dashboard) {
                                navigateTo(MainNavigationSection.Dashboard)
                            }
                        }
                    },
                )
                localPairingServer = server
                setStatus(
                    title = "Pairing code ready",
                    message = "Scan this code from another device, then approve the request here.",
                    tone = StatusTone.Progress,
                )
                runOnUiThread {
                    setPairingControlsEnabled(true)
                    renderDashboardIfVisible()
                }
            } catch (exception: Exception) {
                localPairingServer = null
                setStatus(
                    title = "Could not show pairing code",
                    message = PairingErrorMessage.from(exception),
                    tone = StatusTone.Error,
                )
                runOnUiThread {
                    setPairingControlsEnabled(true)
                    renderDashboardIfVisible()
                }
            }
        }.start()
    }

    private fun stopLocalPairingOffer() {
        localPairingServer?.close()
        localPairingServer = null
        setStatus(
            title = "Pairing code stopped",
            message = "Show this device's code again when another device needs to pair.",
            tone = StatusTone.Neutral,
        )
        renderDashboardIfVisible()
    }

    private fun approveLocalPairingRequest(requestId: String) {
        try {
            val record = localPairingServer?.approve(requestId)
                ?: throw IllegalStateException("Pairing code is not active.")
            PairedPcShareTargets.publish(this, pairedPcStore.loadAll())
            setStatus(
                title = "Paired with ${record.pcName}",
                message = "This device can now receive from ${record.pcName}. Keep this screen open or use Receive files when sending here.",
                tone = StatusTone.Success,
            )
            renderDashboardIfVisible()
        } catch (exception: Exception) {
            setStatus(
                title = "Approval failed",
                message = PairingErrorMessage.from(exception),
                tone = StatusTone.Error,
            )
        }
    }

    private fun rejectLocalPairingRequest(requestId: String) {
        val rejected = localPairingServer?.reject(requestId) == true
        setStatus(
            title = if (rejected) "Pairing rejected" else "No pending request",
            message = if (rejected) "The other device was not paired." else "There is no active pairing request to reject.",
            tone = if (rejected) StatusTone.Neutral else StatusTone.Error,
        )
        renderDashboardIfVisible()
    }

    private fun handleHostedReceiveProgress(progress: ReceiveTransferProgress) {
        if (progress.status == ReceiveTransferStatus.Completed && progress.fileIndex >= progress.totalFiles) {
            TransferSoundPlayer(this).play(TransferSoundResult.Success)
        }
        runOnUiThread {
            if (selectedSection != MainNavigationSection.Transfer) {
                return@runOnUiThread
            }
            transferStatusTitleText.text = "Receiving from ${progress.pcName.ifBlank { "paired device" }}"
            transferStatusBodyText.text = "${progress.fileIndex.coerceAtLeast(1)} of ${progress.totalFiles.coerceAtLeast(1)} files • ${progress.batchPercent}%"
            transferProgressBar.progress = progress.batchPercent
            transferCurrentFileText.text = "Current file: ${progress.fileName}"
        }
    }

    private fun startPairingFromCode(rawCode: String) {
        setPairingControlsEnabled(false)
        setStatus(
            title = "Reading QR code",
            message = "Checking the pairing details...",
            tone = StatusTone.Progress,
        )

        Thread {
            try {
                val payload = PairingCodeInput.decode(rawCode)
                val client = PairingClient()
                val deviceName = androidDeviceName()
                val devicePublicKey = identityStore.devicePublicKey()
                val existingPairedPc = pairedPcStore.findByTlsCertificateSha256(payload.tlsCertificateSha256)
                val receiveEndpoint = receiveEndpointForPairing()

                setStatus(
                    title = if (existingPairedPc == null) "Connecting to ${payload.pcName}" else "Refreshing ${payload.pcName}",
                    message = if (existingPairedPc == null) {
                        "Using pinned local HTTPS. Keep both devices awake and on the same network."
                    } else {
                        "This device is already paired. Approve on the other device only if you want to refresh the trust record."
                    },
                    tone = StatusTone.Progress,
                )
                val receipt = client.submitPairingRequest(
                    payload = payload,
                    deviceName = deviceName,
                    devicePublicKey = devicePublicKey,
                    receiveEndpoints = receiveEndpoint?.let { listOf(PairingEndpointCandidate(it.host, it.port)) }.orEmpty(),
                    receiveTlsCertificateSha256 = receiveEndpoint?.tlsCertificateSha256,
                )

                setStatus(
                    title = "Approve on other device",
                    message = receipt.message.ifBlank { "NearShare is waiting for approval on the other device." },
                    tone = StatusTone.Progress,
                )
                val result = pollPairingResult(client, payload, receipt.requestId)
                handlePairingResult(payload, result)
            } catch (exception: Exception) {
                setStatus(
                    title = "Pairing failed",
                    message = PairingErrorMessage.from(exception),
                    tone = StatusTone.Error,
                )
            } finally {
                runOnUiThread { setPairingControlsEnabled(true) }
            }
        }.start()
    }

    private fun pollPairingResult(
        client: PairingClient,
        payload: PairingPayload,
        requestId: String,
    ): PairingRequestResult {
        repeat(PAIRING_POLL_ATTEMPTS) {
            val result = client.getPairingResult(payload, requestId)
            when (result.status) {
                "approved", "rejected" -> return result
                "pending_confirmation" -> {
                    setStatus(
                        title = "Approve on other device",
                        message = result.message ?: "NearShare is waiting for approval on the other device.",
                        tone = StatusTone.Progress,
                    )
                    Thread.sleep(PAIRING_POLL_INTERVAL_MILLIS)
                }
                else -> throw IllegalStateException("Unexpected pairing status: ${result.status}")
            }
        }

        throw IllegalStateException("Timed out waiting for Windows pairing approval.")
    }

    private fun handlePairingResult(payload: PairingPayload, result: PairingRequestResult) {
        when (result.status) {
            "approved" -> {
                val pcDeviceId = result.deviceId
                    ?: throw IllegalStateException("Approved pairing result did not include a device ID.")
                val sharedSecret = result.sharedSecret
                    ?: throw IllegalStateException("Approved pairing result did not include a shared secret.")

                val pairedPcRecord = PairedPcRecord(
                    pcDeviceId = pcDeviceId,
                    pcName = payload.pcName,
                    endpoints = payload.endpoints,
                    tlsCertificateSha256 = payload.tlsCertificateSha256,
                    sharedSecret = sharedSecret,
                    pairedAtUnixTimeSeconds = System.currentTimeMillis() / 1000,
                )
                pairedPcStore.addOrUpdate(pairedPcRecord)
                PairedPcShareTargets.publish(this, pairedPcStore.loadAll())

                setStatus(
                    title = "Checking ${payload.pcName}",
                    message = "Pairing is approved. Verifying the authenticated local connection now.",
                    tone = StatusTone.Progress,
                )
                runOnUiThread { renderDashboardIfVisible() }
                verifyPairedPcReachability(payload, pairedPcRecord)
            }
            "rejected" -> setStatus(
                title = "Pairing rejected",
                message = result.message ?: "The request was rejected on Windows.",
                tone = StatusTone.Error,
            )
            else -> throw IllegalStateException("Unexpected pairing status: ${result.status}")
        }
    }

    private fun verifyPairedPcReachability(payload: PairingPayload, record: PairedPcRecord) {
        val result = runCatching { PairedPcReachabilityClient().checkReachability(record) }
        result.onSuccess { reachability ->
            setStatus(
                title = "Paired with ${payload.pcName}",
                message = if (reachability.status == "reachable") {
                    "Authenticated local connection verified. You can send files to this device from Android sharesheet."
                } else {
                    "Pairing was saved, but the other device returned an unexpected reachability status: ${reachability.status}."
                },
                tone = if (reachability.status == "reachable") StatusTone.Success else StatusTone.Neutral,
            )
        }.onFailure { exception ->
            setStatus(
                title = "Paired with ${payload.pcName}",
                message = "Pairing was saved, but the authenticated reachability check failed: ${PairingErrorMessage.from(exception)}",
                tone = StatusTone.Neutral,
            )
        }
    }

    private fun renderPairedPcs() {
        val records = pairedPcStore.loadAll()
        pairedPcsList.removeAllViews()

        if (records.isEmpty()) {
            pairedPcsList.addView(
                bodyText("No paired devices yet. Scan a pairing QR code or show this device's code."),
            )
            return
        }

        records.forEachIndexed { index, record ->
            pairedPcsList.addView(
                pairedPcRow(record),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (index < records.lastIndex) {
                        bottomMargin = dp(10)
                    }
                },
            )
        }
    }

    private fun pairedPcRow(record: PairedPcRecord): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = roundedStrokeDrawable(COLOR_SUBTLE_SURFACE, COLOR_BORDER, dp(18), dp(1))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(this).apply {
                text = record.pcName
                textSize = 16f
                typeface = AppTypeface.bold
                setTextColor(COLOR_TEXT)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(
            dangerButton("Remove").apply {
                setOnClickListener { confirmRemovePairedPc(record) }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginStart = dp(12) },
        )
        row.addView(header)
        row.addView(
            captionText("Paired and trusted for local authenticated transfers.").apply {
                setPadding(0, dp(8), 0, 0)
            },
        )
        return row
    }

    private fun confirmRemovePairedPc(record: PairedPcRecord) {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Remove ${record.pcName}?")
            .setMessage("You will need to pair this device again before future transfers.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                val removed = pairedPcStore.remove(record.pcDeviceId)
                PairedPcShareTargets.publish(this, pairedPcStore.loadAll())
                renderDashboardIfVisible()
                setStatus(
                    title = if (removed) "Removed ${record.pcName}" else "Device already removed",
                    message = if (removed) {
                        "This device is no longer trusted on this phone. Pair again before transferring files."
                    } else {
                        "The paired device record was not found."
                    },
                    tone = if (removed) StatusTone.Success else StatusTone.Neutral,
                )
            }
            .show()
        ThemedAlertDialog.apply(dialog)
    }

    private fun handleReceiveFolderSelected(uri: Uri?) {
        if (uri == null) {
            return
        }

        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }

        updateReceiveFolder(
            ReceiveFolder.CustomTree(
                uri = uri.toString(),
                displayName = displayNameForTreeUri(uri),
            ),
        )
    }

    private fun updateReceiveFolder(folder: ReceiveFolder) {
        receiveSettings = receiveSettings.copy(receiveFolder = folder)
        receiveSettingsStore.save(receiveSettings)
        refreshSettingsIfVisible()
        setTransferIdleText("Receive folder updated to ${folder.displayName}. New incoming files will use this folder without restarting NearShare.")
    }

    private fun openReceiveFolder() {
        when (val folder = receiveSettings.receiveFolder) {
            ReceiveFolder.DefaultDownloads -> openDefaultDownloads()
            is ReceiveFolder.CustomTree -> openCustomReceiveFolder(folder)
        }
    }

    private fun openDefaultDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: ActivityNotFoundException) {
            showStatusDialog(
                title = "Could not open Downloads",
                message = "No file manager on this device can open the system Downloads view directly.",
            )
        }
    }

    private fun openCustomReceiveFolder(folder: ReceiveFolder.CustomTree) {
        val treeUri = Uri.parse(folder.uri)
        val documentUri = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        }.getOrDefault(treeUri)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

        try {
            startActivity(viewIntent)
        } catch (_: ActivityNotFoundException) {
            showStatusDialog(
                title = "Could not open folder",
                message = "Android's file manager on this device does not support opening this selected folder directly.",
            )
        } catch (exception: SecurityException) {
            showStatusDialog(
                title = "Folder permission needed",
                message = exception.message ?: "Choose the receive folder again to refresh access.",
            )
        }
    }

    private fun updateAlwaysOnReceive(enabled: Boolean) {
        if (enabled) {
            requestReceiveNotificationPermissionIfNeeded {
                applyAlwaysOnReceive(enabled = true)
            }
            return
        }

        applyAlwaysOnReceive(enabled = false)
    }

    private fun applyAlwaysOnReceive(enabled: Boolean) {
        if (receiveSettings.alwaysOnReceiveEnabled == enabled) {
            return
        }
        receiveSettings = receiveSettings.copy(alwaysOnReceiveEnabled = enabled)
        receiveSettingsStore.save(receiveSettings)
        if (enabled) {
            AndroidReceiveForegroundService.startAlwaysOn(this)
        } else {
            AndroidReceiveForegroundService.stop(this)
        }
        refreshSettingsIfVisible()
        if (selectedSection == MainNavigationSection.Transfer) {
            navigateTo(MainNavigationSection.Transfer)
        }
        setTransferIdleText(
            if (enabled) {
                "Always On receive is enabled. The foreground receiver notification keeps receiving visible."
            } else {
                "Always On receive is off. Use Manual receive when you want this phone to accept files from a paired device."
            },
        )
    }

    private fun updateTransferSounds(enabled: Boolean) {
        if (receiveSettings.transferSoundsEnabled == enabled) {
            return
        }
        receiveSettings = receiveSettings.copy(transferSoundsEnabled = enabled)
        receiveSettingsStore.save(receiveSettings)
        refreshSettingsIfVisible()
        setTransferIdleText(
            if (enabled) {
                "Transfer sounds are on."
            } else {
                "Transfer sounds are off."
            },
        )
    }

    private fun checkForAppUpdates() {
        if (::updateStatusText.isInitialized) {
            updateStatusText.text = "Checking GitHub Releases..."
        }

        Thread {
            val result = GitHubReleaseUpdateChecker().check(installedAppVersionName())
            runOnUiThread { handleUpdateCheckResult(result) }
        }.start()
    }

    private fun handleUpdateCheckResult(result: ReleaseUpdateCheckResult) {
        when (result) {
            is ReleaseUpdateCheckResult.Checked -> {
                if (result.updateAvailable) {
                    val assetText = result.assetName?.let { "\n\nAndroid package: $it" }.orEmpty()
                    val hasDownload = !result.assetUrl.isNullOrBlank()
                    updateStatusText.text = "Update available: ${result.latestVersion}"
                    val dialog = AlertDialog.Builder(this)
                        .setTitle("Update available")
                        .setMessage("Installed: ${result.currentVersion}\nLatest: ${result.latestVersion}$assetText")
                        .setNegativeButton("Not now", null)
                        .setPositiveButton(if (hasDownload) "Download" else "Open release") { _, _ ->
                            if (hasDownload) {
                                startUpdateDownload(result)
                            } else {
                                openExternalUrl(result.releaseUrl)
                            }
                        }
                        .show()
                    ThemedAlertDialog.apply(dialog)
                } else {
                    updateStatusText.text = "NearShare is up to date. Installed version ${result.currentVersion}."
                    showStatusDialog(
                        title = "No update available",
                        message = "NearShare ${result.currentVersion} is the latest public release.",
                    )
                }
            }
            is ReleaseUpdateCheckResult.Unavailable -> {
                updateStatusText.text = result.message
                showStatusDialog(title = "Could not check for updates", message = result.message)
            }
        }
    }

    private fun installedAppVersionName(): String {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionName
            }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            showStatusDialog(
                title = "Could not open link",
                message = url,
            )
        }
    }

    private fun startUpdateDownload(result: ReleaseUpdateCheckResult.Checked) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingUpdateDownloadRequest = result
            updateStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }

        val assetUrl = result.assetUrl
        if (assetUrl.isNullOrBlank()) {
            openExternalUrl(result.releaseUrl)
            return
        }

        val fileName = sanitizeUpdateFileName(
            result.assetName?.takeIf { it.isNotBlank() } ?: "nearshare-${result.latestVersion}.apk",
        )
        val displayFolder = "Downloads/$UpdateDownloadFolderName"
        val destinationPath = "$UpdateDownloadFolderName/$fileName"
        val request = DownloadManager.Request(Uri.parse(assetUrl)).apply {
            setTitle("NearShare ${result.latestVersion}")
            setDescription("Downloading $fileName")
            setMimeType(AndroidPackageMimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destinationPath)
        }

        try {
            val downloadManager = getSystemService(DownloadManager::class.java)
            val downloadId = downloadManager.enqueue(request)
            pendingUpdateDownload = PendingUpdateDownload(
                downloadId = downloadId,
                latestVersion = result.latestVersion,
                fileName = fileName,
                displayFolder = displayFolder,
                checksumAssetUrl = result.checksumAssetUrl,
            )
            updateStatusText.text = "Downloading ${result.latestVersion} to $displayFolder..."
            setStatus(
                title = "Downloading update",
                message = "NearShare ${result.latestVersion} is downloading to $displayFolder.",
                tone = StatusTone.Progress,
            )
        } catch (exception: Exception) {
            updateStatusText.text = "Could not start update download."
            showStatusDialog(
                title = "Could not start download",
                message = exception.message ?: "Android could not start the update download.",
            )
        }
    }

    private fun handleUpdateDownloadComplete(downloadId: Long) {
        val pendingDownload = pendingUpdateDownload ?: return
        if (pendingDownload.downloadId != downloadId) {
            return
        }

        val downloadManager = getSystemService(DownloadManager::class.java)
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor == null) {
            updateStatusText.text = "Could not read completed update download."
            return
        }
        cursor.use {
            if (!it.moveToFirst()) {
                updateStatusText.text = "Could not read completed update download."
                return
            }

            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                val reason = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                pendingUpdateDownload = null
                updateStatusText.text = "Update download failed."
                showStatusDialog(
                    title = "Download failed",
                    message = "Android stopped the update download. Reason code: $reason",
                )
                return
            }
        }

        val apkUri = downloadManager.getUriForDownloadedFile(downloadId)
        if (apkUri == null) {
            pendingUpdateDownload = null
            updateStatusText.text = "Downloaded update could not be opened."
            showStatusDialog(
                title = "Download complete",
                message = "The update finished downloading, but Android did not provide an installable file URI. Open Downloads and install ${pendingDownload.fileName} manually.",
            )
            return
        }

        updateStatusText.text = "Verifying downloaded update..."
        Thread {
            val checksumStatus = verifyDownloadedUpdate(apkUri, pendingDownload)
            val downloadedUpdate = DownloadedUpdate(
                latestVersion = pendingDownload.latestVersion,
                fileName = pendingDownload.fileName,
                displayFolder = pendingDownload.displayFolder,
                apkUri = apkUri,
                checksumStatus = checksumStatus,
            )
            pendingUpdateDownload = null
            downloadedUpdateForInstall = downloadedUpdate
            runOnUiThread {
                updateStatusText.text = "Update downloaded to ${pendingDownload.displayFolder}."
                showUpdateDownloadCompleteDialog(downloadedUpdate)
            }
        }.start()
    }

    private fun verifyDownloadedUpdate(apkUri: Uri, pendingDownload: PendingUpdateDownload): UpdateChecksumStatus {
        val checksumUrl = pendingDownload.checksumAssetUrl
        if (checksumUrl.isNullOrBlank()) {
            return UpdateChecksumStatus.NotAvailable("No checksum file was published with this release.")
        }

        return try {
            val checksumText = readTextUrl(checksumUrl)
            val expectedHash = findExpectedSha256(checksumText, pendingDownload.fileName)
                ?: return UpdateChecksumStatus.NotAvailable("The checksum file did not include ${pendingDownload.fileName}.")
            val actualHash = sha256(apkUri)
            if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                UpdateChecksumStatus.Failed("Checksum mismatch. Download the update from the release page instead.")
            } else {
                UpdateChecksumStatus.Verified
            }
        } catch (exception: Exception) {
            UpdateChecksumStatus.NotAvailable(exception.message ?: "Could not verify the checksum.")
        }
    }

    private fun showUpdateDownloadCompleteDialog(downloadedUpdate: DownloadedUpdate) {
        if (isFinishing || isDestroyed) {
            return
        }

        val checksumMessage = when (downloadedUpdate.checksumStatus) {
            UpdateChecksumStatus.Verified -> "SHA-256 checksum verified."
            is UpdateChecksumStatus.NotAvailable -> "Checksum not verified: ${downloadedUpdate.checksumStatus.reason}"
            is UpdateChecksumStatus.Failed -> "Install blocked: ${downloadedUpdate.checksumStatus.reason}"
        }
        val canInstall = downloadedUpdate.checksumStatus !is UpdateChecksumStatus.Failed
        val dialog = AlertDialog.Builder(this)
            .setTitle("Download complete")
            .setMessage(
                "Saved to:\n${downloadedUpdate.displayFolder}\n\nFile:\n${downloadedUpdate.fileName}\n\n$checksumMessage",
            )
            .setNegativeButton("Not now", null)
            .setPositiveButton("Install") { _, _ -> installDownloadedUpdate(downloadedUpdate) }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = canInstall
        ThemedAlertDialog.apply(dialog)
    }

    private fun installDownloadedUpdate(downloadedUpdate: DownloadedUpdate) {
        if (!canRequestPackageInstalls()) {
            showInstallPermissionDialog()
            return
        }

        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = downloadedUpdate.apkUri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        try {
            startActivity(installIntent)
        } catch (_: Exception) {
            val fallbackIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(downloadedUpdate.apkUri, AndroidPackageMimeType)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            try {
                startActivity(fallbackIntent)
            } catch (exception: Exception) {
                showStatusDialog(
                    title = "Could not open installer",
                    message = exception.message ?: "Android could not open the downloaded APK installer.",
                )
            }
        }
    }

    private fun canRequestPackageInstalls(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()
    }

    private fun showInstallPermissionDialog() {
        waitingForUnknownSourcePermission = true
        val dialog = AlertDialog.Builder(this)
            .setTitle("Allow update installs")
            .setMessage("Android needs permission before NearShare can open the downloaded APK installer. After enabling it, return to NearShare.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Open settings") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName"),
                        ),
                    )
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                }
            }
            .show()
        ThemedAlertDialog.apply(dialog)
    }

    private fun sanitizeUpdateFileName(fileName: String): String {
        val sanitized = fileName.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return sanitized.takeIf { it.endsWith(".apk", ignoreCase = true) } ?: "$sanitized.apk"
    }

    private fun readTextUrl(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "NearShare-Android")
        }
        return try {
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun findExpectedSha256(checksumText: String, fileName: String): String? {
        val hashPattern = Regex("""\b[0-9a-fA-F]{64}\b""")
        val directMatch = checksumText
            .lineSequence()
            .firstOrNull { fileName in it }
            ?.let { hashPattern.find(it)?.value }
        if (directMatch != null) {
            return directMatch
        }

        val allHashes = hashPattern.findAll(checksumText).map { it.value }.toList()
        return allHashes.singleOrNull()
    }

    private fun sha256(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        } ?: throw IllegalStateException("Could not open downloaded update for checksum verification.")
        return digest.digest().joinToString(separator = "") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    private fun requestPrivateConnectionStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            return
        }

        startPrivateConnection()
    }

    private fun startPrivateConnection() {
        setPrivateConnectionStatus(
            title = "Creating private connection",
            message = "Preparing a local connection for paired devices.",
            tone = StatusTone.Progress,
        )
        privateConnectionHost.start { result ->
            runOnUiThread {
                result.onSuccess { offer ->
                    privateConnectionOffer = offer
                    startReceiveForPrivateConnection("created")
                    renderDashboardIfVisible()
                    setPrivateConnectionStatus(
                        title = "Private connection ready",
                        message = "Receiving is on while the other device connects.",
                        tone = StatusTone.Success,
                    )
                }.onFailure { exception ->
                    privateConnectionOffer = null
                    renderDashboardIfVisible()
                    setPrivateConnectionStatus(
                        title = "Could not create private connection",
                        message = exception.message ?: "Try again.",
                        tone = StatusTone.Error,
                    )
                }
            }
        }
    }

    private fun stopPrivateConnection() {
        privateConnectionHost.stop()
        privateConnectionOffer = null
        stopAutoReceiveForPrivateConnection()
        renderDashboardIfVisible()
        setPrivateConnectionStatus(
            title = "Private connection stopped",
            message = "Create or join a private connection again when paired devices are not on the same Wi-Fi.",
            tone = StatusTone.Neutral,
        )
    }

    private fun startPrivateConnectionJoinFromCode(rawCode: String) {
        setPrivateConnectionStatus(
            title = "Reading private connection QR",
            message = "Checking the connection details...",
            tone = StatusTone.Progress,
        )
        val offer = runCatching { PrivateConnectionOfferCodec.decode(rawCode) }
            .getOrElse { exception ->
                setPrivateConnectionStatus(
                    title = "Could not read private connection QR",
                    message = exception.message ?: "Try again.",
                    tone = StatusTone.Error,
                )
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
        val securityCodeInput = privateConnectionSecurityCodeDialogInput()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addPrivateConnectionDialogField("Connection name", connectionNameInput)
            addPrivateConnectionDialogField("Password", passwordInput)
            addPrivateConnectionDialogField("Security code", securityCodeInput.root)
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
                val securityCode = PrivateConnectionSecurityCode.normalize(securityCodeInput.input.text.toString())
                when {
                    connectionName.isBlank() -> {
                        setPrivateConnectionStatus(
                            title = "Connection name needed",
                            message = "Enter the connection name shown on the other device.",
                            tone = StatusTone.Error,
                        )
                    }
                    !PrivateConnectionSecurityCode.isValid(securityCode) -> {
                        setPrivateConnectionStatus(
                            title = "Security code needed",
                            message = "Enter the 9-character security code shown on the other device.",
                            tone = StatusTone.Error,
                        )
                    }
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

    private fun LinearLayout.addPrivateConnectionDialogField(label: String, field: View) {
        addView(privateConnectionDialogLabel(label))
        val height = if (field is EditText) dp(48) else ViewGroup.LayoutParams.WRAP_CONTENT
        addView(
            field,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                bottomMargin = dp(2)
            },
        )
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
            setPrivateConnectionStatus(
                title = "Not supported",
                message = "Android 10 or newer is required to join a private connection inside NearShare.",
                tone = StatusTone.Error,
            )
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
        setPrivateConnectionStatus(
            title = "Connecting private connection",
            message = "Android will ask before joining this local connection.",
            tone = StatusTone.Progress,
        )
        privateConnectionJoiner.connect(offer) { result ->
            runOnUiThread {
                result.onSuccess { joined ->
                    joinedPrivateConnectionName = joined.connectionName
                    startReceiveForPrivateConnection("joined")
                    renderDashboardIfVisible()
                    if (retryPendingTransferAfterPrivateConnectionJoin()) {
                        setPrivateConnectionStatus(
                            title = "Connected to ${joined.connectionName}",
                            message = "Retrying transfer...",
                            tone = StatusTone.Success,
                        )
                    } else {
                        setPrivateConnectionStatus(
                            title = "Connected to ${joined.connectionName}",
                            message = "Try the transfer again.",
                            tone = StatusTone.Success,
                        )
                    }
                }.onFailure { exception ->
                    joinedPrivateConnectionName = null
                    renderDashboardIfVisible()
                    setPrivateConnectionStatus(
                        title = "Could not connect private connection",
                        message = exception.message ?: "Try again.",
                        tone = StatusTone.Error,
                    )
                }
            }
        }
    }

    private fun disconnectPrivateConnection() {
        privateConnectionJoiner.disconnect()
        joinedPrivateConnectionName = null
        stopAutoReceiveForPrivateConnection()
        renderDashboardIfVisible()
        setPrivateConnectionStatus(
            title = "Private connection disconnected",
            message = "Create or join a private connection again when needed.",
            tone = StatusTone.Neutral,
        )
    }

    private fun startReceiveForPrivateConnection(routeState: String) {
        requestReceiveNotificationPermissionIfNeeded {
            val alreadyRunning = AndroidReceiveEndpointRegistry.currentEndpoint() != null
            if (receiveSettings.alwaysOnReceiveEnabled) {
                AndroidReceiveForegroundService.startAlwaysOn(this)
                NearShareDiagnostics.info(this, "Private connection $routeState; ensured always-on receive")
            } else {
                AndroidReceiveForegroundService.startManual(this)
                privateConnectionAutoReceiveStarted = !alreadyRunning
                NearShareDiagnostics.info(this, "Private connection $routeState; ensured manual receive alreadyRunning=$alreadyRunning")
            }
        }
    }

    private fun stopAutoReceiveForPrivateConnection() {
        if (!privateConnectionAutoReceiveStarted || receiveSettings.alwaysOnReceiveEnabled) {
            privateConnectionAutoReceiveStarted = false
            return
        }

        AndroidReceiveForegroundService.stop(this)
        privateConnectionAutoReceiveStarted = false
        NearShareDiagnostics.info(this, "Private connection ended; stopped auto-started manual receive")
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

    private fun retryPendingTransferAfterPrivateConnectionJoin(): Boolean {
        val batchId = pendingPrivateConnectionRetryBatchId ?: return false
        val manifest = activeTransferStore.loadAll()
            .firstOrNull { manifest ->
                manifest.batchId == batchId && manifest.status == ActiveTransferStatus.Active
            }
        if (manifest == null) {
            pendingPrivateConnectionRetryBatchId = null
            return false
        }

        pendingPrivateConnectionRetryBatchId = null
        TransferForegroundService.retryTransfer(this, manifest.batchId, manifest.pcDeviceId)
        return true
    }

    private fun updatePendingPrivateConnectionRetryState(status: String, intent: Intent) {
        when (status) {
            TransferForegroundService.STATUS_FAILED -> {
                pendingPrivateConnectionRetryBatchId = intent.getStringExtra(TransferForegroundService.EXTRA_BATCH_ID)
                    ?.takeIf { batchId -> batchId.isNotBlank() }
            }
            TransferForegroundService.STATUS_PROGRESS,
            TransferForegroundService.STATUS_COMPLETED,
            TransferForegroundService.STATUS_CANCELLED -> {
                pendingPrivateConnectionRetryBatchId = null
            }
        }
    }

    private fun handleTransferEvent(intent: Intent) {
        if (intent.action == AndroidReceiveForegroundService.ACTION_RECEIVE_EVENT) {
            handleReceiveEvent(intent)
            return
        }

        val status = intent.getStringExtra(TransferForegroundService.EXTRA_EVENT_STATUS).orEmpty()
        updatePendingPrivateConnectionRetryState(status, intent)
        if (selectedSection != MainNavigationSection.Transfer) {
            return
        }

        when (status) {
            TransferForegroundService.STATUS_PROGRESS -> {
                val fileIndex = intent.getIntExtra(TransferForegroundService.EXTRA_FILE_INDEX, 0)
                val totalFiles = intent.getIntExtra(TransferForegroundService.EXTRA_TOTAL_FILES, 0)
                val fileName = intent.getStringExtra(TransferForegroundService.EXTRA_FILE_NAME).orEmpty()
                val sentBytes = intent.getLongExtra(TransferForegroundService.EXTRA_BATCH_SENT_BYTES, 0L)
                val totalBytes = intent.getLongExtra(TransferForegroundService.EXTRA_BATCH_TOTAL_BYTES, 0L)
                val percent = percent(sentBytes, totalBytes)
                transferStatusTitleText.text = "Transfer in progress"
                transferStatusBodyText.text = "${fileIndex.coerceAtLeast(1)} of ${totalFiles.coerceAtLeast(1)} files • $percent%"
                transferProgressBar.progress = percent
                transferCurrentFileText.text = "Current file: $fileName"
            }
            TransferForegroundService.STATUS_COMPLETED -> {
                val pcName = intent.getStringExtra(TransferForegroundService.EXTRA_PC_NAME).orEmpty()
                val totalFiles = intent.getIntExtra(TransferForegroundService.EXTRA_TOTAL_FILES, 0)
                transferStatusTitleText.text = "Transfer complete"
                transferStatusBodyText.text = "Sent $totalFiles ${if (totalFiles == 1) "file" else "files"} to $pcName."
                transferProgressBar.progress = 100
                transferCurrentFileText.text = "Current file: complete"
            }
            TransferForegroundService.STATUS_FAILED -> {
                transferStatusTitleText.text = "Transfer stopped"
                transferStatusBodyText.text = intent.getStringExtra(TransferForegroundService.EXTRA_MESSAGE).orEmpty()
                transferProgressBar.progress = 0
                transferCurrentFileText.text = "Current file: none"
            }
            TransferForegroundService.STATUS_CANCELLED -> {
                setTransferIdleText("Transfer cancelled.")
            }
        }
    }

    private fun handleReceiveEvent(intent: Intent) {
        val status = intent.getStringExtra(AndroidReceiveForegroundService.EXTRA_RECEIVE_STATUS).orEmpty()
        val message = intent.getStringExtra(AndroidReceiveForegroundService.EXTRA_MESSAGE).orEmpty()
        if (selectedSection != MainNavigationSection.Transfer) {
            return
        }

        when (status) {
            AndroidReceiveForegroundService.STATUS_MANUAL_READY,
            AndroidReceiveForegroundService.STATUS_ALWAYS_ON_READY -> {
                transferStatusTitleText.text = "Ready to receive from PC"
                transferStatusBodyText.text = message.ifBlank { "Paired devices can send files to this phone." }
                transferProgressBar.progress = 0
                transferCurrentFileText.text = "Current file: none"
            }
            AndroidReceiveForegroundService.STATUS_PROGRESS -> {
                val fileIndex = intent.getIntExtra(AndroidReceiveForegroundService.EXTRA_FILE_INDEX, 0)
                val totalFiles = intent.getIntExtra(AndroidReceiveForegroundService.EXTRA_TOTAL_FILES, 0)
                val fileName = intent.getStringExtra(AndroidReceiveForegroundService.EXTRA_FILE_NAME).orEmpty()
                val pcName = intent.getStringExtra(AndroidReceiveForegroundService.EXTRA_PC_NAME).orEmpty()
                val receivePercent = intent.getIntExtra(AndroidReceiveForegroundService.EXTRA_PERCENT, 0).coerceIn(0, 100)
                transferStatusTitleText.text = "Receiving from ${pcName.ifBlank { "paired PC" }}"
                transferStatusBodyText.text = "${fileIndex.coerceAtLeast(1)} of ${totalFiles.coerceAtLeast(1)} files • $receivePercent%"
                transferProgressBar.progress = receivePercent
                transferCurrentFileText.text = "Current file: $fileName"
            }
            AndroidReceiveForegroundService.STATUS_FAILED -> {
                transferStatusTitleText.text = "Receive stopped"
                transferStatusBodyText.text = message.ifBlank { "Could not keep the Android receiver running." }
                transferProgressBar.progress = 0
                transferCurrentFileText.text = "Current file: none"
            }
            AndroidReceiveForegroundService.STATUS_STOPPED -> {
                setTransferIdleText(message.ifBlank { "NearShare receiving is off." })
            }
        }
    }

    private fun receiveEndpointForPairing(): AndroidReceiveEndpointMetadata? {
        AndroidReceiveEndpointRegistry.currentEndpoint()?.let { return it }

        if (receiveSettings.alwaysOnReceiveEnabled) {
            AndroidReceiveForegroundService.startAlwaysOn(this)
        } else {
            AndroidReceiveForegroundService.startManual(this)
        }
        repeat(25) {
            AndroidReceiveEndpointRegistry.currentEndpoint()?.let { return it }
            Thread.sleep(100L)
        }
        return null
    }

    private fun setTransferIdleText(message: String) {
        if (selectedSection != MainNavigationSection.Transfer) {
            return
        }
        transferStatusTitleText.text = "No active transfer"
        transferStatusBodyText.text = message
        transferProgressBar.progress = 0
        transferCurrentFileText.text = "Current file: none"
    }

    private fun setPrivateConnectionStatus(title: String, message: String, tone: StatusTone) {
        setStatus(title = title, message = message, tone = tone)
        if (selectedSection == MainNavigationSection.Transfer) {
            setTransferIdleText(message)
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

    private fun showStatusDialog(title: String, message: String) {
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

    private fun renderDashboardIfVisible() {
        runOnUiThread {
            if (selectedSection == MainNavigationSection.Dashboard) {
                navigateTo(MainNavigationSection.Dashboard)
            }
        }
    }

    private fun refreshSettingsIfVisible() {
        if (selectedSection == MainNavigationSection.Settings) {
            navigateTo(MainNavigationSection.Settings)
        }
    }

    private fun setPairingControlsEnabled(enabled: Boolean) {
        runOnUiThread {
            scanButton.isEnabled = enabled
            scanButton.alpha = if (enabled) 1f else 0.55f
        }
    }

    private fun setStatus(title: String, message: String, tone: StatusTone) {
        val status = DashboardStatus(title = title, message = message, tone = tone)
        currentDashboardStatus = status
        runOnUiThread {
            if (!::statusTitleText.isInitialized || !::statusBodyText.isInitialized || !::statusCard.isInitialized) {
                return@runOnUiThread
            }
            applyDashboardStatus(status)
        }
    }

    private fun applyDashboardStatus(status: DashboardStatus) {
        statusTitleText.text = status.title
        statusBodyText.text = status.message
        statusTitleText.setTextColor(status.tone.titleColor)
        statusBodyText.setTextColor(status.tone.bodyColor)
        statusCard.background = roundedStrokeDrawable(status.tone.backgroundColor, status.tone.strokeColor, dp(20), dp(1))
    }

    private fun receiveModeSummary(): String {
        return if (receiveSettings.alwaysOnReceiveEnabled) {
            "Always On receive is enabled. A foreground notification will make receiver status visible while the Android receiver is active."
        } else {
            "Manual receive is selected. Open this tab and start receive mode when you want this phone to accept files from a paired device."
        }
    }

    private fun receiveFolderDescription(): String {
        return when (receiveSettings.receiveFolder) {
            ReceiveFolder.DefaultDownloads -> "Incoming files use Android's system Downloads collection. Android may block manually selecting Downloads in the custom folder picker; use this default option for Downloads."
            is ReceiveFolder.CustomTree -> "Incoming files will be written through Android's folder access permission. Pick a normal writable folder; Android blocks some protected roots. Changes apply to new incoming files immediately."
        }
    }

    private fun bootPersistenceDescription(): String {
        val action = receiveSettings.bootRestoreAction()
        return when (action) {
            BootRestoreAction.DoNothing -> "Always On is off, so NearShare will not try to resume receiving after a phone restart."
            BootRestoreAction.StartReceiver -> "NearShare will try to resume receiving after Android finishes booting. On Android 15+ and some OEM builds, the system may require tapping a notification before the receiver can start."
            BootRestoreAction.ShowResumeNotification -> action.userMessage
        }
    }

    private fun displayNameForTreeUri(uri: Uri): String {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        val candidate = treeId
            ?.substringAfter(':', missingDelimiterValue = treeId)
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "Selected folder"
        return URLDecoder.decode(candidate, Charsets.UTF_8.name()).ifBlank { "Selected folder" }
    }

    private fun screenContent(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(30))
        }
    }

    private fun scrollContainer(content: LinearLayout): ScrollView {
        return ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            setBackgroundColor(COLOR_BACKGROUND)
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun cardContainer(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedStrokeDrawable(Color.WHITE, COLOR_BORDER, dp(22), dp(1))
            elevation = 0f
        }
    }

    private fun titleText(textValue: String, sizeSp: Float): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = sizeSp
            typeface = AppTypeface.bold
            setTextColor(COLOR_TEXT)
            includeFontPadding = false
        }
    }

    private fun bodyText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 15f
            setLineSpacing(dp(2).toFloat(), 1.0f)
            typeface = AppTypeface.regular
            setTextColor(COLOR_SECONDARY_TEXT)
        }
    }

    private fun captionText(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 13f
            setLineSpacing(dp(2).toFloat(), 1.0f)
            typeface = AppTypeface.regular
            setTextColor(COLOR_MUTED)
        }
    }

    private fun primaryButton(textValue: String): Button {
        return Button(this).apply {
            text = textValue
            setAllCaps(false)
            textSize = 15f
            typeface = AppTypeface.bold
            setTextColor(Color.WHITE)
            minHeight = dp(50)
            gravity = Gravity.CENTER
            background = roundedDrawable(COLOR_PRIMARY, dp(16))
            stateListAnimator = null
            elevation = 0f
        }
    }

    private fun secondaryButton(textValue: String): Button {
        return Button(this).apply {
            text = textValue
            setAllCaps(false)
            textSize = 15f
            typeface = AppTypeface.bold
            setTextColor(COLOR_PRIMARY)
            minHeight = dp(50)
            gravity = Gravity.CENTER
            background = roundedStrokeDrawable(COLOR_PRIMARY_SOFT, COLOR_PRIMARY_STROKE, dp(16), dp(1))
            stateListAnimator = null
            elevation = 0f
        }
    }

    private fun dangerButton(textValue: String): Button {
        return Button(this).apply {
            text = textValue
            setAllCaps(false)
            textSize = 13f
            typeface = AppTypeface.bold
            setTextColor(COLOR_DANGER)
            minHeight = dp(40)
            background = roundedStrokeDrawable(COLOR_DANGER_SURFACE, COLOR_DANGER_BORDER, dp(12), dp(1))
            elevation = 0f
            stateListAnimator = null
        }
    }

    private fun withBottomMargin(view: View, bottomMargin: Int): View {
        view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, 0, 0, bottomMargin)
        }
        return view
    }

    private fun roundedDrawable(fillColor: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(fillColor)
        }
    }

    private fun roundedStrokeDrawable(fillColor: Int, strokeColor: Int, radius: Int, strokeWidth: Int): GradientDrawable {
        return roundedDrawable(fillColor, radius).apply { setStroke(strokeWidth, strokeColor) }
    }

    private fun topStrokeDrawable(fillColor: Int, strokeColor: Int, strokeWidth: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fillColor)
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

    private fun percent(value: Long, total: Long): Int {
        if (total <= 0L) {
            return 0
        }
        return ((value * 100L) / total).coerceIn(0L, 100L).toInt()
    }

    private enum class QrScanPurpose {
        Pairing,
        PrivateConnection,
    }

    private enum class StatusTone(
        val titleColor: Int,
        val bodyColor: Int,
        val backgroundColor: Int,
        val strokeColor: Int,
    ) {
        Neutral(COLOR_TEXT, COLOR_SECONDARY_TEXT, Color.WHITE, COLOR_BORDER),
        Progress(COLOR_PRIMARY_DARK, COLOR_PRIMARY_DARK, COLOR_PRIMARY_SOFT, COLOR_PRIMARY_STROKE),
        Error(COLOR_ERROR, COLOR_ERROR_TEXT, COLOR_ERROR_SOFT, COLOR_ERROR_STROKE),
        Success(COLOR_SUCCESS, COLOR_SUCCESS_TEXT, COLOR_SUCCESS_SOFT, COLOR_SUCCESS_STROKE),
    }

    private data class DashboardStatus(
        val title: String,
        val message: String,
        val tone: StatusTone,
    )

    private data class PairingCodeDialogInput(
        val root: View,
        val input: EditText,
    )

    private data class PendingUpdateDownload(
        val downloadId: Long,
        val latestVersion: String,
        val fileName: String,
        val displayFolder: String,
        val checksumAssetUrl: String?,
    )

    private data class DownloadedUpdate(
        val latestVersion: String,
        val fileName: String,
        val displayFolder: String,
        val apkUri: Uri,
        val checksumStatus: UpdateChecksumStatus,
    )

    private sealed class UpdateChecksumStatus {
        data object Verified : UpdateChecksumStatus()

        data class NotAvailable(
            val reason: String,
        ) : UpdateChecksumStatus()

        data class Failed(
            val reason: String,
        ) : UpdateChecksumStatus()
    }

    private companion object {
        private const val PAIRING_POLL_ATTEMPTS = 120
        private const val PAIRING_POLL_INTERVAL_MILLIS = 1_500L
        private const val AndroidPairingOfferLifetimeSeconds = 5 * 60L
        private const val ManualPrivateConnectionLifetimeSeconds = 10 * 60L
        private const val PairingCodeLength = 9
        private const val PrivateConnectionSecurityCodeLength = 9
        private const val SELECT_DEVICE_LABEL = "Select device"
        private const val UpdateDownloadFolderName = "NearShare Updates"
        private const val AndroidPackageMimeType = "application/vnd.android.package-archive"

        private const val COLOR_BACKGROUND = 0xFFF8FAFC.toInt()
        private const val COLOR_TEXT = 0xFF0F172A.toInt()
        private const val COLOR_SECONDARY_TEXT = 0xFF475569.toInt()
        private const val COLOR_MUTED = 0xFF64748B.toInt()
        private const val COLOR_BORDER = 0xFFE2E8F0.toInt()
        private const val COLOR_SUBTLE_SURFACE = 0xFFF8FAFC.toInt()
        private const val COLOR_PRIMARY = 0xFF2563EB.toInt()
        private const val COLOR_PRIMARY_DARK = 0xFF1D4ED8.toInt()
        private const val COLOR_PRIMARY_SOFT = 0xFFEFF6FF.toInt()
        private const val COLOR_PRIMARY_STROKE = 0xFFBFDBFE.toInt()
        private const val COLOR_ERROR = 0xFFB91C1C.toInt()
        private const val COLOR_ERROR_TEXT = 0xFF7F1D1D.toInt()
        private const val COLOR_ERROR_SOFT = 0xFFFEF2F2.toInt()
        private const val COLOR_ERROR_STROKE = 0xFFFECACA.toInt()
        private const val COLOR_DANGER = 0xFFB91C1C.toInt()
        private const val COLOR_DANGER_SURFACE = 0xFFFEF2F2.toInt()
        private const val COLOR_DANGER_BORDER = 0xFFFECACA.toInt()
        private const val COLOR_SUCCESS = 0xFF047857.toInt()
        private const val COLOR_SUCCESS_TEXT = 0xFF064E3B.toInt()
        private const val COLOR_SUCCESS_SOFT = 0xFFECFDF5.toInt()
        private const val COLOR_SUCCESS_STROKE = 0xFFA7F3D0.toInt()
    }
}
