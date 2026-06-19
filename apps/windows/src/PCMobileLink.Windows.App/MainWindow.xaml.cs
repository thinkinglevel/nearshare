using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Connectivity;
using PCMobileLink.Windows.Infrastructure.Discovery;
using PCMobileLink.Windows.Infrastructure.Pairing;
using PCMobileLink.Windows.Infrastructure.Settings;
using PCMobileLink.Windows.Infrastructure.Shell;
using PCMobileLink.Windows.Infrastructure.Startup;
using PCMobileLink.Windows.Infrastructure.Transfer;
using PCMobileLink.Windows.Infrastructure.Updates;
using QRCoder;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Brush = System.Windows.Media.Brush;
using Brushes = System.Windows.Media.Brushes;
using Button = System.Windows.Controls.Button;
using Color = System.Windows.Media.Color;
using Forms = System.Windows.Forms;
using Panel = System.Windows.Controls.Panel;
using TextBox = System.Windows.Controls.TextBox;

namespace PCMobileLink.Windows.App;

public partial class MainWindow : Window
{
    private const string PrivateConnectionSecurityCodeAlphabet = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private const int OutgoingPairingPollAttempts = 120;
    private static readonly TimeSpan OutgoingPairingPollInterval = TimeSpan.FromMilliseconds(1_500);
    private static readonly TimeSpan PrivateConnectionRetryDelay = TimeSpan.FromSeconds(3);

    private readonly AppSettingsStore _settingsStore = AppSettingsStore.CreateDefault();
    private readonly PairedDeviceStore _pairedDeviceStore = PairedDeviceStore.CreateDefault();
    private readonly WindowsStartupRegistration _startupRegistration = new();
    private readonly ExplorerSendMenuRegistrar _explorerSendMenuRegistrar = new();
    private readonly GitHubReleaseUpdateChecker _updateChecker = new();
    private readonly GitHubReleaseAssetDownloader _updateDownloader = new();
    private readonly DispatcherTimer _pairingPollTimer;
    private readonly DispatcherTimer _pcToAndroidShellBatchTimer;
    private readonly ShellSendBatchAccumulator _pcToAndroidShellBatchAccumulator = new();
    private readonly Forms.NotifyIcon _trayIcon;
    private readonly Forms.ToolStripMenuItem _trayReceiveModeItem;
    private readonly WindowsWifiConnector _wifiConnector = new();
    private bool _isExplicitExit;
    private bool _hasShownTrayHint;
    private bool _startWithWindows;
    private bool _transferSoundsEnabled = true;
    private bool _startHiddenToTrayOnLoaded;
    private ReceiveMode _receiveMode = ReceiveMode.Manual;
    private string _receiveFolderPath = string.Empty;
    private string? _discoveryStatusMessage;
    private string? _currentPairingSetupLink;
    private PendingPairingRequest? _activePendingPairingRequest;
    private LocalPairingServer? _pairingServer;
    private LocalDiscoveryResponder? _discoveryResponder;
    private string _trayStatusText = "NearShare";
    private bool _isPcToAndroidSendRunning;
    private AppLaunchRequest? _pendingPrivateConnectionRetryRequest;

    public MainWindow()
        : this(new AppLaunchRequest { Mode = LaunchMode.Dashboard })
    {
    }

    public MainWindow(AppLaunchRequest launchRequest)
    {
        ArgumentNullException.ThrowIfNull(launchRequest);
        NearShareLog.Info($"MainWindow initializing. logFile={NearShareLog.LogFilePath}, {DescribeLaunchRequest(launchRequest)}");

        InitializeComponent();
        _startHiddenToTrayOnLoaded = launchRequest.Mode == LaunchMode.Background;
        if (_startHiddenToTrayOnLoaded)
        {
            ShowActivated = false;
            WindowState = WindowState.Minimized;
        }

        (_trayIcon, _trayReceiveModeItem) = CreateTrayIcon();
        WireTrayMenuHandlers();
        Closing += MainWindow_Closing;

        _pairingPollTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromSeconds(1)
        };
        _pairingPollTimer.Tick += PairingPollTimer_Tick;

        _pcToAndroidShellBatchTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(1500)
        };
        _pcToAndroidShellBatchTimer.Tick += PcToAndroidShellBatchTimer_Tick;

        AppSettings settings = _settingsStore.LoadOrDefault(DefaultReceiveFolder.GetCurrentUserDownloadsPath());
        SetReceiveFolder(settings.ReceiveFolderPath);
        _startWithWindows = settings.StartWithWindows;
        _transferSoundsEnabled = settings.TransferSoundsEnabled;
        SetReceiveMode(settings.ReceiveMode);
        UpdateStartWithWindowsButton();
        UpdateTransferSoundsButton();
        UpdateStatusTextBlock.Text = $"Installed version {GetInstalledAppVersion()}. Checks use GitHub Releases.";
        SetTransferProgressStatus(TransferStatus.Queued);
        RefreshPairedDevices();
        ApplyLaunchRequest(launchRequest);
        NavigateTo(NavigationSection.Dashboard);

        Loaded += async (_, _) =>
        {
            UpdateTrayIconState();
            if (_startWithWindows)
            {
                TryEnableStartupRegistration(showStatusOnFailure: true);
            }

            if (_startHiddenToTrayOnLoaded && _startWithWindows && _receiveMode != ReceiveMode.AlwaysOn)
            {
                SetReceiveMode(ReceiveMode.AlwaysOn);
                SaveCurrentSettings();
            }

            if (_receiveMode == ReceiveMode.AlwaysOn)
            {
                await EnsureLocalReceiverAsync("Always-on receiver is active.").ConfigureAwait(true);
            }

            if (_startHiddenToTrayOnLoaded)
            {
                HideToTray(showHint: false);
            }
        };
    }

    public void AcceptLaunchRequest(AppLaunchRequest launchRequest)
    {
        ArgumentNullException.ThrowIfNull(launchRequest);
        NearShareLog.Info($"MainWindow accepted launch request. {DescribeLaunchRequest(launchRequest)}");
        ApplyLaunchRequest(launchRequest);
    }

    private void DashboardNavButton_Click(object sender, RoutedEventArgs e)
    {
        NavigateTo(NavigationSection.Dashboard);
    }

    private void DevicesNavButton_Click(object sender, RoutedEventArgs e)
    {
        NavigateTo(NavigationSection.Devices);
    }

    private void SettingsNavButton_Click(object sender, RoutedEventArgs e)
    {
        NavigateTo(NavigationSection.Settings);
    }

    private async void ManualReceiveModeButton_Click(object sender, RoutedEventArgs e)
    {
        SetReceiveMode(ReceiveMode.Manual);
        SaveCurrentSettings();
        await StopLocalReceiverAsync("Manual receive selected. Click Receive now when you want paired devices to send files.").ConfigureAwait(true);
    }

    private async void AlwaysOnReceiveModeButton_Click(object sender, RoutedEventArgs e)
    {
        SetReceiveMode(ReceiveMode.AlwaysOn);
        SaveCurrentSettings();
        await EnsureLocalReceiverAsync("Always-on receiver is active.").ConfigureAwait(true);
    }

    private async void StartWithWindowsButton_Click(object sender, RoutedEventArgs e)
    {
        if (_startWithWindows)
        {
            try
            {
                _startupRegistration.Disable();
                _startWithWindows = false;
                SaveCurrentSettings();
                UpdateStartWithWindowsButton();
                StatusTextBlock.Text = "Start with Windows disabled. Current receive mode is unchanged.";
            }
            catch (Exception exception)
            {
                StatusTextBlock.Text = $"Could not disable Start with Windows: {exception.Message}";
            }

            return;
        }

        bool confirmed = ThemedConfirmationDialog.Confirm(
            this,
            new ThemedConfirmationOptions
            {
                Title = "Start NearShare with Windows?",
                Subtitle = "This also turns on Always On receiving.",
                Message = "NearShare will open automatically after Windows login, stay in the system tray, and keep the local receiver/discovery active so paired devices can send files without opening the window first.",
                ConfirmText = "Enable startup",
                CancelText = "Not now",
                Kind = ThemedConfirmationKind.Warning
            });
        if (!confirmed)
        {
            return;
        }

        try
        {
            _startupRegistration.Enable(GetCurrentExecutablePath());
            _startWithWindows = true;
            SetReceiveMode(ReceiveMode.AlwaysOn);
            SaveCurrentSettings();
            UpdateStartWithWindowsButton();
            await EnsureLocalReceiverAsync("Start with Windows enabled. Always-on receiver is active.").ConfigureAwait(true);
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not enable Start with Windows: {exception.Message}";
        }
    }

    private void TransferSoundsButton_Click(object sender, RoutedEventArgs e)
    {
        _transferSoundsEnabled = !_transferSoundsEnabled;
        SaveCurrentSettings();
        UpdateTransferSoundsButton();
        StatusTextBlock.Text = _transferSoundsEnabled
            ? "Transfer sounds are on."
            : "Transfer sounds are off.";
    }

    private async void CheckForUpdatesButton_Click(object sender, RoutedEventArgs e)
    {
        CheckForUpdatesButton.IsEnabled = false;
        string installedVersion = GetInstalledAppVersion();
        UpdateStatusTextBlock.Text = "Checking GitHub Releases...";
        StatusTextBlock.Text = "Checking for NearShare updates...";

        try
        {
            ReleaseUpdateCheckResult result = await _updateChecker.CheckLatestAsync(installedVersion).ConfigureAwait(true);
            await HandleUpdateCheckResultAsync(result).ConfigureAwait(true);
        }
        catch (Exception exception)
        {
            UpdateStatusTextBlock.Text = $"Could not update NearShare: {exception.Message}";
            StatusTextBlock.Text = $"Could not update NearShare: {exception.Message}";
        }
        finally
        {
            CheckForUpdatesButton.IsEnabled = true;
        }
    }

    private async Task HandleUpdateCheckResultAsync(ReleaseUpdateCheckResult result)
    {
        switch (result)
        {
            case ReleaseUpdateCheckResult.Checked checkedResult when checkedResult.UpdateAvailable:
            {
                string assetText = string.IsNullOrWhiteSpace(checkedResult.AssetName)
                    ? string.Empty
                    : $"{Environment.NewLine}{Environment.NewLine}Windows package: {checkedResult.AssetName}";
                bool hasDownload = !string.IsNullOrWhiteSpace(checkedResult.AssetUrl);
                UpdateStatusTextBlock.Text = $"Update available: {checkedResult.LatestVersion}";
                StatusTextBlock.Text = $"Update available: {checkedResult.LatestVersion}";
                bool proceed = ThemedConfirmationDialog.Confirm(
                    this,
                    new ThemedConfirmationOptions
                    {
                        Title = "Update available",
                        Subtitle = $"NearShare {checkedResult.LatestVersion}",
                        Message = $"Installed: {checkedResult.CurrentVersion}{Environment.NewLine}Latest: {checkedResult.LatestVersion}{assetText}",
                        ConfirmText = hasDownload ? "Download" : "Open release",
                        CancelText = "Not now",
                        Kind = ThemedConfirmationKind.Information
                    });
                if (!proceed)
                {
                    break;
                }

                if (hasDownload)
                {
                    await DownloadAndPromptInstallUpdateAsync(checkedResult).ConfigureAwait(true);
                }
                else
                {
                    OpenExternalUrl(checkedResult.ReleaseUrl);
                }
                break;
            }
            case ReleaseUpdateCheckResult.Checked checkedResult:
                UpdateStatusTextBlock.Text = $"NearShare is up to date. Installed version {checkedResult.CurrentVersion}.";
                StatusTextBlock.Text = $"NearShare {checkedResult.CurrentVersion} is the latest public release.";
                break;
            case ReleaseUpdateCheckResult.Unavailable unavailable:
                UpdateStatusTextBlock.Text = unavailable.Message;
                StatusTextBlock.Text = $"Could not check for updates: {unavailable.Message}";
                break;
        }
    }

    private async Task DownloadAndPromptInstallUpdateAsync(ReleaseUpdateCheckResult.Checked release)
    {
        string downloadDirectory = Path.Combine(DefaultReceiveFolder.GetCurrentUserDownloadsPath(), "NearShare Updates");
        Progress<ReleaseAssetDownloadProgress> progress = new(updateProgress =>
        {
            string percentText = updateProgress.Percent.HasValue
                ? $" ({updateProgress.Percent.Value:0}%)"
                : string.Empty;
            UpdateStatusTextBlock.Text = $"Downloading {release.LatestVersion}{percentText}: {updateProgress.Message}";
            StatusTextBlock.Text = $"Downloading NearShare update{percentText}.";
        });

        UpdateStatusTextBlock.Text = $"Downloading {release.LatestVersion} to {downloadDirectory}...";
        ReleaseAssetDownloadResult downloadResult = await _updateDownloader.DownloadAsync(
            release,
            downloadDirectory,
            progress).ConfigureAwait(true);

        UpdateStatusTextBlock.Text = $"Update downloaded to {downloadResult.FolderPath}.";
        bool canInstall = downloadResult.ChecksumStatus is not ReleaseAssetChecksumStatus.Failed;
        string checksumText = downloadResult.ChecksumStatus switch
        {
            ReleaseAssetChecksumStatus.Verified => "SHA-256 checksum verified.",
            ReleaseAssetChecksumStatus.NotAvailable notAvailable => $"Checksum not verified: {notAvailable.Reason}",
            ReleaseAssetChecksumStatus.Failed failed => $"Install blocked: {failed.Reason}",
            _ => "Checksum status unknown."
        };

        bool install = ThemedConfirmationDialog.Confirm(
            this,
            new ThemedConfirmationOptions
            {
                Title = "Download complete",
                Subtitle = release.LatestVersion,
                Message = $"Saved to:{Environment.NewLine}{downloadResult.FolderPath}{Environment.NewLine}{Environment.NewLine}File:{Environment.NewLine}{downloadResult.FilePath}{Environment.NewLine}{Environment.NewLine}{checksumText}",
                ConfirmText = canInstall ? "Install" : "Open folder",
                CancelText = "Not now",
                Kind = canInstall ? ThemedConfirmationKind.Information : ThemedConfirmationKind.Warning
            });

        if (!install)
        {
            return;
        }

        if (canInstall)
        {
            LaunchDownloadedInstaller(downloadResult.FilePath);
        }
        else
        {
            OpenFolder(downloadResult.FolderPath);
        }
    }

    private static string GetInstalledAppVersion()
    {
        string? version = Assembly.GetExecutingAssembly()
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()
            ?.InformationalVersion;
        version ??= Assembly.GetExecutingAssembly().GetName().Version?.ToString();

        int metadataIndex = version?.IndexOf('+', StringComparison.Ordinal) ?? -1;
        if (metadataIndex > 0)
        {
            version = version![..metadataIndex];
        }

        return string.IsNullOrWhiteSpace(version) ? "0.0.0" : version;
    }

    private void OpenExternalUrl(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = url,
                UseShellExecute = true
            });
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not open release page: {exception.Message}";
        }
    }

    private void LaunchDownloadedInstaller(string filePath)
    {
        try
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = filePath,
                UseShellExecute = true
            });
            StatusTextBlock.Text = "Windows installer opened.";
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not open installer: {exception.Message}";
        }
    }

    private void OpenFolder(string folderPath)
    {
        try
        {
            Directory.CreateDirectory(folderPath);
            Process.Start(new ProcessStartInfo
            {
                FileName = folderPath,
                UseShellExecute = true
            });
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not open folder: {exception.Message}";
        }
    }

    private async void ReceiveNowButton_Click(object sender, RoutedEventArgs e)
    {
        await EnsureLocalReceiverAsync("Receiver is active.").ConfigureAwait(true);
    }

    private async void SendFilesButton_Click(object sender, RoutedEventArgs e)
    {
        if (SendDeviceComboBox.SelectedItem is not PairedDeviceRecord device)
        {
            StatusTextBlock.Text = "Pair a device before sending files from this PC.";
            NavigateTo(NavigationSection.Devices);
            return;
        }

        Microsoft.Win32.OpenFileDialog dialog = new()
        {
            Title = "Choose files to send with NearShare",
            CheckFileExists = true,
            Multiselect = true,
            Filter = "All files (*.*)|*.*"
        };

        if (dialog.ShowDialog(this) != true || dialog.FileNames.Length == 0)
        {
            return;
        }

        AppLaunchRequest request = new()
        {
            Mode = LaunchMode.Send,
            TargetDeviceId = device.DeviceId,
            Paths = dialog.FileNames
        };

        await BeginPcToAndroidSendAsync(request).ConfigureAwait(true);
    }

    private void PairAnotherDeviceCodeInputHost_MouseDown(object sender, MouseButtonEventArgs e)
    {
        FocusSegmentedCodeInput(PairAnotherDeviceCodeTextBox);
        e.Handled = true;
    }

    private void PrivateConnectionCodeInputHost_MouseDown(object sender, MouseButtonEventArgs e)
    {
        FocusSegmentedCodeInput(PrivateConnectionCodeTextBox);
        e.Handled = true;
    }

    private void PairAnotherDeviceCodeTextBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        UpdateSegmentedCodeBoxes(PairAnotherDeviceCodeBoxes, PairingShortCode.Normalize(PairAnotherDeviceCodeTextBox.Text));
    }

    private void PrivateConnectionCodeTextBox_TextChanged(object sender, TextChangedEventArgs e)
    {
        UpdateSegmentedCodeBoxes(PrivateConnectionCodeBoxes, NormalizePrivateConnectionSecurityCode(PrivateConnectionCodeTextBox.Text));
    }

    private void SegmentedCodeTextBox_GotKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        if (ReferenceEquals(sender, PairAnotherDeviceCodeTextBox))
        {
            SetSegmentedCodeFocus(PairAnotherDeviceCodeBoxes, isFocused: true);
        }
        else if (ReferenceEquals(sender, PrivateConnectionCodeTextBox))
        {
            SetSegmentedCodeFocus(PrivateConnectionCodeBoxes, isFocused: true);
        }
    }

    private void SegmentedCodeTextBox_LostKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        if (ReferenceEquals(sender, PairAnotherDeviceCodeTextBox))
        {
            SetSegmentedCodeFocus(PairAnotherDeviceCodeBoxes, isFocused: false);
        }
        else if (ReferenceEquals(sender, PrivateConnectionCodeTextBox))
        {
            SetSegmentedCodeFocus(PrivateConnectionCodeBoxes, isFocused: false);
        }
    }

    private static void FocusSegmentedCodeInput(TextBox textBox)
    {
        textBox.Focus();
        textBox.CaretIndex = textBox.Text.Length;
    }

    private void UpdateSegmentedCodeBoxes(Panel panel, string normalizedCode)
    {
        int index = 0;
        foreach (Border border in panel.Children.OfType<Border>())
        {
            if (border.Child is TextBlock textBlock)
            {
                textBlock.Text = index < normalizedCode.Length
                    ? normalizedCode[index].ToString()
                    : string.Empty;
            }

            index++;
        }
    }

    private void SetSegmentedCodeFocus(Panel panel, bool isFocused)
    {
        Brush borderBrush = isFocused
            ? (Brush)FindResource("PrimaryBrush")
            : (Brush)FindResource("BorderBrushSoft");

        foreach (Border border in panel.Children.OfType<Border>())
        {
            border.BorderBrush = borderBrush;
        }
    }

    private async void ConnectPrivateConnectionButton_Click(object sender, RoutedEventArgs e)
    {
        string connectionName = PrivateConnectionNameTextBox.Text.Trim();
        string password = PrivateConnectionPasswordTextBox.Text.Trim();
        string code = NormalizePrivateConnectionSecurityCode(PrivateConnectionCodeTextBox.Text);
        if (string.IsNullOrWhiteSpace(connectionName))
        {
            StatusTextBlock.Text = "Enter the connection name shown on the other device.";
            return;
        }

        if (!IsNineCharacterPrivateConnectionSecurityCode(code))
        {
            StatusTextBlock.Text = "Enter the 9-character security code shown on the other device.";
            return;
        }

        PrivateConnectionCodeTextBox.Text = FormatPrivateConnectionSecurityCode(code);
        ConnectPrivateConnectionButton.IsEnabled = false;
        StatusTextBlock.Text = "Connecting this PC...";
        try
        {
            await Task.Run(() => _wifiConnector.Connect(connectionName, password)).ConfigureAwait(true);
            AppLaunchRequest? retryRequest = _pendingPrivateConnectionRetryRequest;
            _pendingPrivateConnectionRetryRequest = null;
            if (retryRequest is not null)
            {
                StatusTextBlock.Text = $"Connection started for {FormatPrivateConnectionSecurityCode(code)}. Retrying transfer...";
                await Task.Delay(PrivateConnectionRetryDelay).ConfigureAwait(true);
                await BeginPcToAndroidSendAsync(retryRequest).ConfigureAwait(true);
            }
            else
            {
                StatusTextBlock.Text = $"Connection started for {FormatPrivateConnectionSecurityCode(code)}. Send again when ready.";
            }
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not connect this PC: {exception.Message}";
        }
        finally
        {
            ConnectPrivateConnectionButton.IsEnabled = true;
        }
    }

    private void SendDeviceComboBox_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        UpdateSendFilesButtonState();
    }

    private void InstallExplorerMenuButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            IReadOnlyList<PairedDeviceRecord> pairedDevices = _pairedDeviceStore.LoadAll();
            _explorerSendMenuRegistrar.Install(GetCurrentExecutablePath(), pairedDevices);
            UpdateExplorerMenuStatus();
            StatusTextBlock.Text = pairedDevices.Count == 0
                ? "Explorer menu installed. Pair a device, then install again to populate device targets."
                : $"Explorer menu installed with {pairedDevices.Count} paired {(pairedDevices.Count == 1 ? "device" : "devices")}.";
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not install Explorer menu: {exception.Message}";
        }
    }

    private void UninstallExplorerMenuButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            _explorerSendMenuRegistrar.Uninstall();
            UpdateExplorerMenuStatus();
            StatusTextBlock.Text = "Explorer menu removed.";
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not remove Explorer menu: {exception.Message}";
        }
    }

    private void OpenSavePathButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            Directory.CreateDirectory(_receiveFolderPath);
            Process.Start(new ProcessStartInfo
            {
                FileName = _receiveFolderPath,
                UseShellExecute = true
            });
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not open save path: {exception.Message}";
        }
    }

    private async void ChangeSavePathButton_Click(object sender, RoutedEventArgs e)
    {
        using System.Windows.Forms.FolderBrowserDialog dialog = new()
        {
            Description = "Choose where NearShare saves received files",
            SelectedPath = Directory.Exists(_receiveFolderPath)
                ? _receiveFolderPath
                : DefaultReceiveFolder.GetCurrentUserDownloadsPath(),
            UseDescriptionForTitle = true
        };

        if (dialog.ShowDialog() != System.Windows.Forms.DialogResult.OK
            || string.IsNullOrWhiteSpace(dialog.SelectedPath))
        {
            return;
        }

        try
        {
            string previousReceiveFolderPath = _receiveFolderPath;
            bool shouldRestartReceiver = ReceiverRestartPolicy.ShouldRestartAfterReceiveFolderChanged(
                oldReceiveFolderPath: previousReceiveFolderPath,
                newReceiveFolderPath: dialog.SelectedPath,
                isReceiverRunning: _pairingServer is not null);

            SetReceiveFolder(dialog.SelectedPath);
            SaveCurrentSettings();

            if (shouldRestartReceiver)
            {
                StatusTextBlock.Text = "Save path updated. Restarting receiver so new files use this folder...";
                await RestartLocalReceiverAsync().ConfigureAwait(true);
                StatusTextBlock.Text = FormatReceiverStatus("Save path updated. Receiver restarted with the new folder.");
            }
            else
            {
                StatusTextBlock.Text = "Save path updated";
            }
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not update save path: {exception.Message}";
        }
    }

    private void PairingPollTimer_Tick(object? sender, EventArgs e)
    {
        RefreshPendingPairingRequest();
    }

    private void ApprovePairingRequestButton_Click(object sender, RoutedEventArgs e)
    {
        if (_pairingServer is null || _activePendingPairingRequest is null)
        {
            return;
        }

        try
        {
            PairedDeviceRecord? existingDevice = _pairedDeviceStore.FindByDevicePublicKey(_activePendingPairingRequest.DevicePublicKey);
            PairedDeviceRecord pairedDevice = _pairingServer.ApproveRequest(_activePendingPairingRequest.RequestId);
            _pairedDeviceStore.AddOrUpdate(pairedDevice);
            RefreshPairedDevices();
            PendingPairingRequestPanel.Visibility = Visibility.Collapsed;
            PairingOfferStatusTextBlock.Text = existingDevice is null
                ? $"{pairedDevice.DeviceName} is paired. The other device can finish pairing now."
                : $"{pairedDevice.DeviceName} was already paired. The trust record was refreshed.";
            StatusTextBlock.Text = existingDevice is null
                ? $"{pairedDevice.DeviceName} paired"
                : $"{pairedDevice.DeviceName} pairing refreshed";
            _activePendingPairingRequest = null;
        }
        catch (Exception exception)
        {
            PairingOfferStatusTextBlock.Text = $"Could not approve pairing: {exception.Message}";
        }
    }

    private void RejectPairingRequestButton_Click(object sender, RoutedEventArgs e)
    {
        if (_pairingServer is null || _activePendingPairingRequest is null)
        {
            return;
        }

        bool rejected = _pairingServer.RejectRequest(_activePendingPairingRequest.RequestId);
        if (rejected)
        {
            PairingOfferStatusTextBlock.Text = $"Rejected pairing request from {_activePendingPairingRequest.DeviceName}.";
            StatusTextBlock.Text = "Pairing rejected";
        }

        PendingPairingRequestPanel.Visibility = Visibility.Collapsed;
        _activePendingPairingRequest = null;
    }

    private async void PairPhoneButton_Click(object sender, RoutedEventArgs e)
    {
        try
        {
            PairingOfferStatusTextBlock.Text = "Starting local HTTPS pairing listener...";
            PairingOfferPanel.Visibility = Visibility.Visible;

            await RestartLocalReceiverAsync().ConfigureAwait(true);

            PairingOffer offer = _pairingServer!.Offer;

            PairingQrImage.Source = GenerateQrImage(offer.QrUri);
            _currentPairingSetupLink = offer.QrUri;
            SetPairingShortCode(offer.Payload.ShortCode);
            PendingPairingRequestPanel.Visibility = Visibility.Collapsed;
            _activePendingPairingRequest = null;
            _pairingPollTimer.Start();
            PairingOfferStatusTextBlock.Text = "Pairing listener is active for 5 minutes. Scan the QR or enter this code on the other device.";
        }
        catch (Exception exception)
        {
            PairingOfferStatusTextBlock.Text = $"Could not start pairing listener: {exception.Message}";
            _currentPairingSetupLink = null;
            SetPairingShortCode(null);
            PairingQrImage.Source = null;
        }
    }

    private void CopyPairingSetupLinkButton_Click(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_currentPairingSetupLink))
        {
            PairingOfferStatusTextBlock.Text = "Start pairing first, then copy the setup link.";
            return;
        }

        System.Windows.Clipboard.SetText(_currentPairingSetupLink);
        PairingOfferStatusTextBlock.Text = "Advanced setup link copied. Prefer QR or the short code for normal pairing.";
    }

    protected override async void OnClosed(EventArgs e)
    {
        _pairingPollTimer.Stop();

        if (_discoveryResponder is not null)
        {
            await _discoveryResponder.DisposeAsync().ConfigureAwait(true);
            _discoveryResponder = null;
        }

        if (_pairingServer is not null)
        {
            await _pairingServer.DisposeAsync().ConfigureAwait(true);
            _pairingServer = null;
        }
        _trayIcon.Visible = false;
        _trayIcon.Dispose();

        base.OnClosed(e);
    }

    private void MainWindow_Closing(object? sender, CancelEventArgs e)
    {
        if (_isExplicitExit || _receiveMode != ReceiveMode.AlwaysOn)
        {
            return;
        }

        e.Cancel = true;
        HideToTray();
    }

    private static (Forms.NotifyIcon TrayIcon, Forms.ToolStripMenuItem ReceiveModeItem) CreateTrayIcon()
    {
        Forms.ContextMenuStrip menu = new();
        Forms.ToolStripMenuItem receiveModeItem = new("Receive mode: Manual");
        receiveModeItem.Enabled = false;

        Forms.NotifyIcon trayIcon = new()
        {
            Text = "NearShare",
            Icon = TryLoadTrayIcon(),
            Visible = false,
            ContextMenuStrip = menu
        };

        Forms.ToolStripMenuItem openItem = new("Open NearShare");
        Forms.ToolStripMenuItem openFolderItem = new("Open receive folder");
        Forms.ToolStripMenuItem exitItem = new("Exit NearShare");

        menu.Items.Add(openItem);
        menu.Items.Add(receiveModeItem);
        menu.Items.Add(openFolderItem);
        menu.Items.Add(new Forms.ToolStripSeparator());
        menu.Items.Add(exitItem);

        return (trayIcon, receiveModeItem);
    }

    private void WireTrayMenuHandlers()
    {
        _trayIcon.DoubleClick += (_, _) => ShowMainWindowFromTray();
        if (_trayIcon.ContextMenuStrip is null)
        {
            return;
        }

        if (_trayIcon.ContextMenuStrip.Items[0] is Forms.ToolStripMenuItem openItem)
        {
            openItem.Click += (_, _) => ShowMainWindowFromTray();
        }

        if (_trayIcon.ContextMenuStrip.Items[2] is Forms.ToolStripMenuItem openFolderItem)
        {
            openFolderItem.Click += (_, _) => OpenReceiveFolderFromTray();
        }

        if (_trayIcon.ContextMenuStrip.Items[4] is Forms.ToolStripMenuItem exitItem)
        {
            exitItem.Click += async (_, _) => await ExitFromTrayAsync().ConfigureAwait(true);
        }
    }

    private void HideToTray(bool showHint = true)
    {
        Hide();
        UpdateTrayIconState();

        if (!showHint || _hasShownTrayHint)
        {
            return;
        }

        _trayIcon.ShowBalloonTip(
            timeout: 3000,
            tipTitle: "NearShare is still running",
            tipText: "Always-on receive stays active in the tray. Use Exit NearShare to stop listening.",
            tipIcon: Forms.ToolTipIcon.Info);
        _hasShownTrayHint = true;
    }

    private void ShowMainWindowFromTray()
    {
        Show();
        if (WindowState == WindowState.Minimized)
        {
            WindowState = WindowState.Normal;
        }

        Activate();
        UpdateTrayIconState();
    }

    private async Task ExitFromTrayAsync()
    {
        _isExplicitExit = true;
        _trayIcon.Visible = false;
        await StopLocalReceiverAsync(statusMessage: null).ConfigureAwait(true);
        Close();
    }

    private void OpenReceiveFolderFromTray()
    {
        try
        {
            Directory.CreateDirectory(_receiveFolderPath);
            Process.Start(new ProcessStartInfo
            {
                FileName = _receiveFolderPath,
                UseShellExecute = true
            });
        }
        catch (Exception exception)
        {
            _trayIcon.ShowBalloonTip(
                timeout: 3000,
                tipTitle: "Could not open receive folder",
                tipText: exception.Message,
                tipIcon: Forms.ToolTipIcon.Error);
        }
    }

    private void UpdateTrayIconState()
    {
        _trayReceiveModeItem.Text = _receiveMode == ReceiveMode.AlwaysOn
            ? "Receive mode: Always on"
            : "Receive mode: Manual";
        _trayIcon.Text = _trayStatusText.Length > 63
            ? _trayStatusText[..63]
            : _trayStatusText;
        _trayIcon.Visible = _receiveMode == ReceiveMode.AlwaysOn || !IsVisible;
    }

    private static System.Drawing.Icon TryLoadTrayIcon()
    {
        try
        {
            string? processPath = Environment.ProcessPath;
            if (!string.IsNullOrWhiteSpace(processPath))
            {
                return System.Drawing.Icon.ExtractAssociatedIcon(processPath) ?? System.Drawing.SystemIcons.Application;
            }
        }
        catch (ArgumentException)
        {
        }
        catch (FileNotFoundException)
        {
        }

        return System.Drawing.SystemIcons.Application;
    }

    private void SetReceiveFolder(string receiveFolderPath)
    {
        _receiveFolderPath = receiveFolderPath;
        DashboardSavePathTextBlock.Text = receiveFolderPath;
        SettingsSavePathTextBlock.Text = receiveFolderPath;
    }

    private void SaveCurrentSettings()
    {
        _settingsStore.Save(new AppSettings
        {
            ReceiveFolderPath = _receiveFolderPath,
            ReceiveMode = _receiveMode,
            StartWithWindows = _startWithWindows,
            TransferSoundsEnabled = _transferSoundsEnabled
        });
    }

    private void UpdateStartWithWindowsButton()
    {
        StartWithWindowsButton.Content = _startWithWindows
            ? "Start with Windows: On"
            : "Start with Windows: Off";
    }

    private void UpdateTransferSoundsButton()
    {
        TransferSoundsButton.Content = _transferSoundsEnabled
            ? "Transfer sounds: On"
            : "Transfer sounds: Off";
    }

    private void TryEnableStartupRegistration(bool showStatusOnFailure)
    {
        try
        {
            _startupRegistration.Enable(GetCurrentExecutablePath());
        }
        catch (Exception exception)
        {
            if (showStatusOnFailure)
            {
                StatusTextBlock.Text = $"Could not refresh Start with Windows registration: {exception.Message}";
            }
        }
    }

    private static string GetCurrentExecutablePath()
    {
        string? processPath = Environment.ProcessPath;
        if (!string.IsNullOrWhiteSpace(processPath))
        {
            return processPath;
        }

        string? modulePath = Process.GetCurrentProcess().MainModule?.FileName;
        if (!string.IsNullOrWhiteSpace(modulePath))
        {
            return modulePath;
        }

        throw new InvalidOperationException("Could not determine the NearShare executable path.");
    }

    private async Task EnsureLocalReceiverAsync(string successMessage)
    {
        if (_pairingServer is not null)
        {
            StatusTextBlock.Text = FormatReceiverStatus(successMessage);
            UpdateTrayIconState();
            return;
        }

        try
        {
            _pairingServer = await StartLocalReceiverAsync().ConfigureAwait(true);
            await StartLocalDiscoveryResponderAsync().ConfigureAwait(true);
            _pairingPollTimer.Start();
            StatusTextBlock.Text = FormatReceiverStatus(successMessage);
            UpdateTrayIconState();
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not start receiver: {exception.Message}";
        }
    }

    private async Task RestartLocalReceiverAsync()
    {
        await StopLocalReceiverAsync(statusMessage: null).ConfigureAwait(true);
        _pairingServer = await StartLocalReceiverAsync().ConfigureAwait(true);
        await StartLocalDiscoveryResponderAsync().ConfigureAwait(true);
        _pairingPollTimer.Start();
        StatusTextBlock.Text = FormatReceiverStatus("Receiver is active.");
        UpdateTrayIconState();
    }

    private async Task StopLocalReceiverAsync(string? statusMessage)
    {
        _pairingPollTimer.Stop();
        PendingPairingRequestPanel.Visibility = Visibility.Collapsed;
        _activePendingPairingRequest = null;

        if (_discoveryResponder is not null)
        {
            await _discoveryResponder.DisposeAsync().ConfigureAwait(true);
            _discoveryResponder = null;
        }

        _discoveryStatusMessage = null;

        if (_pairingServer is not null)
        {
            await _pairingServer.DisposeAsync().ConfigureAwait(true);
            _pairingServer = null;
        }

        if (!string.IsNullOrWhiteSpace(statusMessage))
        {
            StatusTextBlock.Text = statusMessage;
        }

        UpdateTrayIconState();
    }

    private async Task StartLocalDiscoveryResponderAsync()
    {
        if (_pairingServer is null)
        {
            _discoveryStatusMessage = null;
            return;
        }

        if (_discoveryResponder is not null)
        {
            await _discoveryResponder.DisposeAsync().ConfigureAwait(true);
            _discoveryResponder = null;
        }

        try
        {
            _discoveryResponder = await LocalDiscoveryResponder.StartAsync(
                new LocalDiscoveryResponderOptions
                {
                    PcName = Environment.MachineName,
                    TlsCertificateSha256 = _pairingServer.CertificateFingerprint,
                    Endpoints = _pairingServer.Offer.Payload.Endpoints,
                    PairingOfferPayload = _pairingServer.Offer.Payload,
                    PairingOfferUri = _pairingServer.Offer.QrUri,
                    ListenAddress = IPAddress.Any
                }).ConfigureAwait(true);
            _discoveryStatusMessage = " Paired devices can find this PC while NearShare is open.";
        }
        catch (Exception exception)
        {
            _discoveryStatusMessage = $" If another device cannot find this PC, create a private connection. {exception.Message}";
        }
    }

    private Task<LocalPairingServer> StartLocalReceiverAsync()
    {
        return LocalPairingServer.StartAsync(
            new LocalPairingServerOptions
            {
                PcName = Environment.MachineName,
                QrEndpointHosts = [GetPreferredLocalIpv4Address()],
                ListenAddress = IPAddress.Any,
                ListenPort = 0,
                ReceiveFolderPath = _receiveFolderPath,
                TransferProgressChanged = HandleReceiveTransferProgress
            });
    }

    private void HandleReceiveTransferProgress(ReceiveTransferProgressUpdate update)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.Invoke(() => HandleReceiveTransferProgress(update));
            return;
        }

        bool completed = string.Equals(update.Status, "completed", StringComparison.Ordinal);
        int completedFiles = completed
            ? update.FileIndex
            : Math.Max(0, update.FileIndex - 1);
        TransferProgressCard.Visibility = Visibility.Visible;
        TransferProgressBar.Value = CalculateOverallFileProgressPercent(update);
        TransferProgressStatusTextBlock.Text = completed
            ? $"Received file {update.FileIndex} of {update.TotalFiles} from {update.DeviceName}: {update.FileName}."
            : $"Receiving file {update.FileIndex} of {update.TotalFiles} from {update.DeviceName}: {update.FileName}...";
        CompletedCountTextBlock.Text = $"{completedFiles} of {update.TotalFiles} completed";
        CurrentDeviceTextBlock.Text = update.DeviceName;
        CurrentFileTextBlock.Text = $"File {update.FileIndex} of {update.TotalFiles}: {update.FileName} ({FormatBytes(update.ReceivedBytes)} of {FormatBytes(update.TotalBytes)})";
        StatusTextBlock.Text = completed
            ? $"Received file {update.FileIndex} of {update.TotalFiles} from {update.DeviceName}"
            : FormatReceiverStatus($"Receiving file {update.FileIndex} of {update.TotalFiles} from {update.DeviceName}: {update.PercentComplete}%.");
        if (completed && update.FileIndex >= update.TotalFiles)
        {
            TransferSoundPlayer.PlaySuccess(_transferSoundsEnabled);
        }
    }

    private async void PairAnotherDeviceButton_Click(object sender, RoutedEventArgs e)
    {
        string rawPairingCode = PairAnotherDeviceCodeTextBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(rawPairingCode))
        {
            StatusTextBlock.Text = "Enter the pairing code shown on the other device.";
            return;
        }

        try
        {
            PairingPayload payload = await ResolvePairingPayloadAsync(rawPairingCode).ConfigureAwait(true);
            await EnsureLocalReceiverAsync("Receiver is active for pairing.").ConfigureAwait(true);
            if (_pairingServer is null)
            {
                StatusTextBlock.Text = "Could not start this PC's receiver for pairing.";
                return;
            }

            if (string.Equals(payload.TlsCertificateSha256, _pairingServer.CertificateFingerprint, StringComparison.OrdinalIgnoreCase))
            {
                StatusTextBlock.Text = "This is this PC's own pairing code. Use the code from the other device.";
                return;
            }

            WindowsPairingClient client = new();
            StatusTextBlock.Text = $"Requesting pairing with {payload.PcName}...";
            PairingRequestReceipt receipt = await client.SubmitPairingRequestAsync(
                    payload,
                    deviceName: Environment.MachineName,
                    devicePublicKey: _pairingServer.CertificateFingerprint,
                    receiveEndpoints: _pairingServer.Offer.Payload.Endpoints,
                    receiveTlsCertificateSha256: _pairingServer.CertificateFingerprint)
                .ConfigureAwait(true);

            StatusTextBlock.Text = receipt.Message;
            PairingRequestResultResponse result = await PollOutgoingPairingResultAsync(client, payload, receipt.RequestId).ConfigureAwait(true);
            if (string.Equals(result.Status, "rejected", StringComparison.Ordinal))
            {
                StatusTextBlock.Text = result.Message ?? "Pairing was rejected on the other device.";
                return;
            }

            if (!string.Equals(result.Status, "approved", StringComparison.Ordinal)
                || result.DeviceId is null
                || string.IsNullOrWhiteSpace(result.SharedSecret))
            {
                StatusTextBlock.Text = "Pairing result was incomplete. Try pairing again.";
                return;
            }

            DateTimeOffset now = DateTimeOffset.UtcNow;
            PairedDeviceRecord pairedDevice = new()
            {
                DeviceId = result.DeviceId.Value,
                DeviceName = payload.PcName,
                DevicePublicKey = $"nearshare-receiver:{payload.TlsCertificateSha256}",
                SharedSecret = result.SharedSecret,
                PairedAt = now,
                LastSeenAt = now,
                ReceiveEndpoints = payload.Endpoints,
                ReceiveTlsCertificateSha256 = payload.TlsCertificateSha256
            };
            _pairedDeviceStore.AddOrUpdate(pairedDevice);
            RefreshPairedDevices();
            PairAnotherDeviceCodeTextBox.Text = string.Empty;
            StatusTextBlock.Text = $"{pairedDevice.DeviceName} paired. You can send files to this device.";
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not pair this PC: {exception.Message}";
        }
    }

    private static async Task<PairingRequestResultResponse> PollOutgoingPairingResultAsync(
        WindowsPairingClient client,
        PairingPayload payload,
        Guid requestId)
    {
        for (int attempt = 0; attempt < OutgoingPairingPollAttempts; attempt++)
        {
            PairingRequestResultResponse result = await client.GetPairingResultAsync(payload, requestId).ConfigureAwait(true);
            if (string.Equals(result.Status, "approved", StringComparison.Ordinal)
                || string.Equals(result.Status, "rejected", StringComparison.Ordinal))
            {
                return result;
            }

            if (!string.Equals(result.Status, "pending_confirmation", StringComparison.Ordinal))
            {
                throw new InvalidOperationException($"Unexpected pairing status: {result.Status}");
            }

            await Task.Delay(OutgoingPairingPollInterval).ConfigureAwait(true);
        }

        throw new TimeoutException("Timed out waiting for pairing approval on the other device.");
    }

    private static async Task<PairingPayload> ResolvePairingPayloadAsync(string rawPairingCode)
    {
        string trimmed = rawPairingCode.Trim();
        if (trimmed.StartsWith("nearshare://pair", StringComparison.OrdinalIgnoreCase))
        {
            return PairingPayloadCodec.Decode(trimmed);
        }

        PairingOfferDiscoveryClient discoveryClient = new();
        return await discoveryClient.ResolveAsync(trimmed).ConfigureAwait(false);
    }

    private void SetPairingShortCode(string? shortCode)
    {
        string normalized = PairingShortCode.Normalize(shortCode ?? string.Empty);
        if (normalized.Length == 9)
        {
            PairingShortCodePart1TextBlock.Text = normalized[..3];
            PairingShortCodePart2TextBlock.Text = normalized.Substring(3, 3);
            PairingShortCodePart3TextBlock.Text = normalized.Substring(6, 3);
            return;
        }

        PairingShortCodePart1TextBlock.Text = "---";
        PairingShortCodePart2TextBlock.Text = "---";
        PairingShortCodePart3TextBlock.Text = "---";
    }

    private static int CalculateOverallFileProgressPercent(ReceiveTransferProgressUpdate update)
    {
        if (update.TotalFiles <= 0)
        {
            return update.PercentComplete;
        }

        int completedBeforeCurrent = Math.Max(0, update.FileIndex - 1);
        int currentFilePercent = Math.Clamp(update.PercentComplete, 0, 100);
        int overallPercent = ((completedBeforeCurrent * 100) + currentFilePercent) / update.TotalFiles;
        return Math.Clamp(overallPercent, 0, 100);
    }

    private static string NormalizePrivateConnectionSecurityCode(string code)
    {
        return new string(code.Where(char.IsLetterOrDigit).Select(char.ToUpperInvariant).ToArray());
    }

    private static bool IsNineCharacterPrivateConnectionSecurityCode(string code)
    {
        return code.Length == 9 && code.All(character => PrivateConnectionSecurityCodeAlphabet.Contains(character));
    }

    private static string FormatPrivateConnectionSecurityCode(string code)
    {
        return code.Length == 9
            ? $"{code[..3]}-{code.Substring(3, 3)}-{code.Substring(6, 3)}"
            : code;
    }

    private static bool CanRetryAfterPrivateConnection(Exception exception)
    {
        return exception is InvalidOperationException
            && exception.Message.Contains("create a private connection", StringComparison.OrdinalIgnoreCase);
    }

    private string FormatReceiverStatus(string message)
    {
        return message + (_discoveryStatusMessage ?? string.Empty);
    }

    private void RefreshPendingPairingRequest()
    {
        if (_pairingServer is null)
        {
            PendingPairingRequestPanel.Visibility = Visibility.Collapsed;
            _activePendingPairingRequest = null;
            return;
        }

        PendingPairingRequest? pendingRequest = _pairingServer.PendingRequests
            .FirstOrDefault(request => string.Equals(request.Status, "pending_confirmation", StringComparison.Ordinal));

        if (pendingRequest is null)
        {
            PendingPairingRequestPanel.Visibility = Visibility.Collapsed;
            _activePendingPairingRequest = null;
            return;
        }

        _activePendingPairingRequest = pendingRequest;
        PairedDeviceRecord? existingDevice = _pairedDeviceStore.FindByDevicePublicKey(pendingRequest.DevicePublicKey);
        if (existingDevice is null)
        {
            PendingPairingRequestTitleTextBlock.Text = $"{pendingRequest.DeviceName} wants to pair";
            PendingPairingRequestDetailTextBlock.Text = $"Request ID: {pendingRequest.RequestId}. Confirm only if this is your other device.";
            PairingOfferStatusTextBlock.Text = "Pairing request received. Approve or reject it on Windows.";
        }
        else
        {
            PendingPairingRequestTitleTextBlock.Text = $"{pendingRequest.DeviceName} is already paired";
            PendingPairingRequestDetailTextBlock.Text = $"This appears to be the same device as {existingDevice.DeviceName}. Approve only if you want to refresh its trust record; reject if this was unexpected.";
            PairingOfferStatusTextBlock.Text = "Already-paired device wants to refresh pairing. Approve only if you expected this.";
        }

        PendingPairingRequestPanel.Visibility = Visibility.Visible;
    }

    private void RefreshPairedDevices()
    {
        IReadOnlyList<PairedDeviceRecord> devices = _pairedDeviceStore.LoadAll();
        PairedDevicesItemsControl.ItemsSource = devices;
        PairedDevicesEmptyPanel.Visibility = devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        PairedDevicesItemsControl.Visibility = devices.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        RefreshSendDeviceControls(devices);
        RefreshInstalledExplorerMenu(devices);
        UpdateExplorerMenuStatus();
    }

    private void RefreshInstalledExplorerMenu(IReadOnlyList<PairedDeviceRecord> devices)
    {
        try
        {
            if (!_explorerSendMenuRegistrar.IsInstalled())
            {
                return;
            }

            _explorerSendMenuRegistrar.Install(GetCurrentExecutablePath(), devices);
            NearShareLog.Info($"Explorer menu refreshed from paired-device store. deviceCount={devices.Count}");
        }
        catch (Exception exception)
        {
            NearShareLog.Warning("Could not refresh installed Explorer menu from paired-device store.", exception);
        }
    }

    private void RefreshSendDeviceControls(IReadOnlyList<PairedDeviceRecord> devices)
    {
        Guid? selectedDeviceId = (SendDeviceComboBox.SelectedItem as PairedDeviceRecord)?.DeviceId;
        SendDeviceComboBox.ItemsSource = devices;

        PairedDeviceRecord? selectedDevice = selectedDeviceId is null
            ? null
            : devices.FirstOrDefault(device => device.DeviceId == selectedDeviceId.Value);
        selectedDevice ??= devices.FirstOrDefault();
        SendDeviceComboBox.SelectedItem = selectedDevice;
        SendDeviceComboBox.IsEnabled = devices.Count > 0 && !_isPcToAndroidSendRunning;
        UpdateSendFilesButtonState();

        SendDeviceComboBox.ToolTip = devices.Count == 0
            ? "Pair a device before sending files from this PC."
            : "Choose the device that should receive files from this PC.";
    }

    private void UpdateSendFilesButtonState()
    {
        bool hasSelectedDevice = SendDeviceComboBox.SelectedItem is PairedDeviceRecord;
        SendFilesButton.IsEnabled = !_isPcToAndroidSendRunning;
        SendFilesButton.Content = _isPcToAndroidSendRunning
            ? "Sending..."
            : hasSelectedDevice ? "Send files" : "Pair device first";
        SendFilesButton.ToolTip = hasSelectedDevice
            ? "Choose one or more files to send to the selected device."
            : "Pair a device first. Clicking this opens the Devices page.";
    }

    private void UpdateExplorerMenuStatus()
    {
        try
        {
            bool installed = _explorerSendMenuRegistrar.IsInstalled();
            int pairedDeviceCount = _pairedDeviceStore.LoadAll().Count;
            ExplorerMenuStatusTextBlock.Text = installed
                ? $"Explorer menu: Installed for this Windows user. Menu targets are synced from {pairedDeviceCount} paired {(pairedDeviceCount == 1 ? "device" : "devices")}."
                : "Explorer menu: Not installed for this Windows user.";
            InstallExplorerMenuButton.Content = installed ? "Refresh installed menu" : "Install menu";
            InstallExplorerMenuButton.ToolTip = installed
                ? "Regenerate the Explorer submenu from the current paired-device list."
                : "Add Send using NearShare to this Windows user's file right-click menu.";
            UninstallExplorerMenuButton.IsEnabled = installed;
            UninstallExplorerMenuButton.Content = installed ? "Uninstall menu" : "Uninstall menu (not installed)";
            UninstallExplorerMenuButton.ToolTip = installed
                ? "Remove Send using NearShare from this Windows user's file right-click menu."
                : "The Explorer menu is not installed for this Windows user.";
        }
        catch (Exception exception)
        {
            ExplorerMenuStatusTextBlock.Text = $"Explorer menu: Could not check status: {exception.Message}";
            InstallExplorerMenuButton.Content = "Install menu";
            UninstallExplorerMenuButton.IsEnabled = false;
            UninstallExplorerMenuButton.Content = "Uninstall menu";
        }
    }

    private void RemovePairedDeviceButton_Click(object sender, RoutedEventArgs e)
    {
        if (sender is not FrameworkElement { DataContext: PairedDeviceRecord device })
        {
            return;
        }

        bool confirmed = ThemedConfirmationDialog.Confirm(
            this,
            new ThemedConfirmationOptions
            {
                Title = "Remove paired device?",
                Subtitle = device.DeviceName,
                Message = "This removes the trusted pairing record from this PC. You will need to pair this device again before future transfers.",
                ConfirmText = "Remove device",
                CancelText = "Keep device",
                Kind = ThemedConfirmationKind.Danger
            });
        if (!confirmed)
        {
            return;
        }

        try
        {
            bool removed = _pairedDeviceStore.Remove(device.DeviceId);
            RefreshPairedDevices();
            StatusTextBlock.Text = removed
                ? $"{device.DeviceName} removed"
                : $"{device.DeviceName} was not found";
        }
        catch (Exception exception)
        {
            StatusTextBlock.Text = $"Could not remove paired device: {exception.Message}";
        }
    }

    private void ApplyLaunchRequest(AppLaunchRequest launchRequest)
    {
        NearShareLog.Info($"Applying launch request. {DescribeLaunchRequest(launchRequest)}");
        if (launchRequest.HasError)
        {
            NearShareLog.Warning($"Launch request has error: {launchRequest.ErrorMessage}");
            StatusTextBlock.Text = launchRequest.ErrorMessage;
        }

        if (launchRequest.Mode != LaunchMode.Send)
        {
            return;
        }

        if (launchRequest.Paths.Count == 0)
        {
            NearShareLog.Warning("Send launch request had no paths.");
            StatusTextBlock.Text = launchRequest.ErrorMessage ?? "Use Send files or Share with NearShare to choose files.";
            return;
        }

        string itemLabel = launchRequest.Paths.Count == 1 ? "item" : "items";
        if (launchRequest.TargetDeviceId is not null)
        {
            NearShareLog.Info($"Queueing shell send. targetDeviceId={launchRequest.TargetDeviceId:D}, paths={launchRequest.Paths.Count}");
            QueuePcToAndroidShellSend(launchRequest);
            return;
        }

        NearShareLog.Warning($"Send launch request had no target device. paths={launchRequest.Paths.Count}");
        StatusTextBlock.Text = $"{launchRequest.Paths.Count} {itemLabel} received from launch command, but no paired device was specified. Use the dashboard Send files action or the Explorer NearShare device menu.";
    }

    private void QueuePcToAndroidShellSend(AppLaunchRequest launchRequest)
    {
        NearShareLog.Info($"Adding launch request to shell batch. {DescribeLaunchRequest(launchRequest)}");
        if (!_pcToAndroidShellBatchAccumulator.TryAdd(launchRequest))
        {
            NearShareLog.Warning("Shell batch accumulator rejected request before draining pending items.");
            if (_pcToAndroidShellBatchAccumulator.HasPending)
            {
                StartPendingPcToAndroidShellSend();
            }

            if (!_pcToAndroidShellBatchAccumulator.TryAdd(launchRequest))
            {
                NearShareLog.Warning("Shell batch accumulator rejected request after draining; sending immediately.");
                _ = BeginPcToAndroidSendAsync(launchRequest);
                return;
            }
        }

        int pendingCount = _pcToAndroidShellBatchAccumulator.Count;
        NearShareLog.Info($"Shell send queued. pendingCount={pendingCount}, isSendRunning={_isPcToAndroidSendRunning}");
        StatusTextBlock.Text = $"{pendingCount} {(pendingCount == 1 ? "item" : "items")} queued for device send.";
        if (!_isPcToAndroidSendRunning)
        {
            _pcToAndroidShellBatchTimer.Stop();
            _pcToAndroidShellBatchTimer.Start();
        }
    }

    private void PcToAndroidShellBatchTimer_Tick(object? sender, EventArgs e)
    {
        NearShareLog.Info("Shell batch timer fired.");
        _pcToAndroidShellBatchTimer.Stop();
        StartPendingPcToAndroidShellSend();
    }

    private void StartPendingPcToAndroidShellSend()
    {
        if (_isPcToAndroidSendRunning || !_pcToAndroidShellBatchAccumulator.HasPending)
        {
            NearShareLog.Info($"Shell batch start skipped. isSendRunning={_isPcToAndroidSendRunning}, hasPending={_pcToAndroidShellBatchAccumulator.HasPending}");
            return;
        }

        AppLaunchRequest batchedRequest = _pcToAndroidShellBatchAccumulator.Drain();
        NearShareLog.Info($"Starting drained shell batch. {DescribeLaunchRequest(batchedRequest)}");
        _ = BeginPcToAndroidSendAsync(batchedRequest);
    }

    private async Task BeginPcToAndroidSendAsync(AppLaunchRequest launchRequest)
    {
        NearShareLog.Info($"Begin PC-to-Android send. {DescribeLaunchRequest(launchRequest)}");
        if (launchRequest.TargetDeviceId is null || launchRequest.Paths.Count == 0)
        {
            NearShareLog.Warning("PC-to-Android send ignored because target device or paths were missing.");
            return;
        }

        PairedDeviceRecord? device = _pairedDeviceStore.FindByDeviceId(launchRequest.TargetDeviceId.Value);
        if (device is null)
        {
            string message = "The selected device is no longer paired. Pair it again before sending files.";
            NearShareLog.Warning($"PC-to-Android send target is no longer paired. targetDeviceId={launchRequest.TargetDeviceId:D}");
            StatusTextBlock.Text = message;
            return;
        }

        NearShareLog.Info($"Resolved paired target. deviceId={device.DeviceId:D}, deviceName={device.DeviceName}, storedEndpoints={device.ReceiveEndpoints.Count}, hasReceiveCert={!string.IsNullOrWhiteSpace(device.ReceiveTlsCertificateSha256)}, paths={launchRequest.Paths.Count}");

        _isPcToAndroidSendRunning = true;
        SendDeviceComboBox.IsEnabled = false;
        UpdateSendFilesButtonState();

        TransferProgressCard.Visibility = Visibility.Visible;
        TransferProgressBar.Value = 0;
        TransferProgressStatusTextBlock.Text = $"Connecting to {device.DeviceName}...";
        CompletedCountTextBlock.Text = $"0 of {launchRequest.Paths.Count} completed";
        CurrentDeviceTextBlock.Text = device.DeviceName;
        CurrentFileTextBlock.Text = "Preparing connection...";
        _trayStatusText = $"NearShare: finding {device.DeviceName}";
        UpdateTrayIconState();

        try
        {
            AndroidReceiveEndpointResolver resolver = new();
            NearShareLog.Info($"Resolving Android receive endpoint. deviceId={device.DeviceId:D}, deviceName={device.DeviceName}");
            device = await resolver.ResolveAsync(device).ConfigureAwait(true);
            NearShareLog.Info($"Android receive endpoint resolved. deviceId={device.DeviceId:D}, endpoint={FormatEndpoint(device.ReceiveEndpoints.FirstOrDefault())}, hasReceiveCert={!string.IsNullOrWhiteSpace(device.ReceiveTlsCertificateSha256)}");
            _pairedDeviceStore.AddOrUpdate(device);
            RefreshPairedDevices();
            TransferProgressStatusTextBlock.Text = $"Sending {launchRequest.Paths.Count} {(launchRequest.Paths.Count == 1 ? "file" : "files")} to {device.DeviceName}...";
            CurrentDeviceTextBlock.Text = device.DeviceName;
            CurrentFileTextBlock.Text = "Preparing files...";
            _trayStatusText = $"NearShare: sending to {device.DeviceName}";
            UpdateTrayIconState();

            PcToAndroidFileSender sender = new(device);
            NearShareLog.Info($"Starting file upload. deviceId={device.DeviceId:D}, files={launchRequest.Paths.Count}");
            IReadOnlyList<PcToAndroidSendResult> results = await sender.SendFilesAsync(
                device,
                launchRequest.Paths,
                HandlePcToAndroidTransferProgress,
                CancellationToken.None).ConfigureAwait(true);
            NearShareLog.Info($"PC-to-Android send completed. deviceId={device.DeviceId:D}, results={results.Count}");

            TransferProgressBar.Value = 100;
            TransferProgressStatusTextBlock.Text = $"Sent {results.Count} {(results.Count == 1 ? "file" : "files")} to {device.DeviceName}.";
            CompletedCountTextBlock.Text = $"{results.Count} of {results.Count} completed";
            CurrentDeviceTextBlock.Text = device.DeviceName;
            CurrentFileTextBlock.Text = "Transfer complete";
            StatusTextBlock.Text = $"Sent {results.Count} {(results.Count == 1 ? "file" : "files")} to {device.DeviceName}.";
            _trayStatusText = "NearShare: transfer complete";
            UpdateTrayIconState();
            TransferSoundPlayer.PlaySuccess(_transferSoundsEnabled);
        }
        catch (Exception exception)
        {
            string message = exception.Message;
            if (CanRetryAfterPrivateConnection(exception))
            {
                _pendingPrivateConnectionRetryRequest = launchRequest;
                message += " Connect this PC below; NearShare will retry this transfer after the private connection starts.";
            }
            NearShareLog.Error($"PC-to-Android send failed. {DescribeLaunchRequest(launchRequest)}", exception);
            TransferProgressStatusTextBlock.Text = message;
            CurrentFileTextBlock.Text = "Transfer stopped";
            StatusTextBlock.Text = message;
            _trayStatusText = "NearShare: transfer stopped";
            UpdateTrayIconState();
            TransferSoundPlayer.PlayFailure(_transferSoundsEnabled);
        }
        finally
        {
            _isPcToAndroidSendRunning = false;
            NearShareLog.Info($"PC-to-Android send ended. hasPendingShellBatch={_pcToAndroidShellBatchAccumulator.HasPending}");
            SendDeviceComboBox.IsEnabled = _pairedDeviceStore.LoadAll().Count > 0;
            UpdateSendFilesButtonState();
            if (_pcToAndroidShellBatchAccumulator.HasPending)
            {
                _pcToAndroidShellBatchTimer.Stop();
                _pcToAndroidShellBatchTimer.Start();
            }
        }
    }

    private void HandlePcToAndroidTransferProgress(PcToAndroidTransferProgress progress)
    {
        if (!Dispatcher.CheckAccess())
        {
            Dispatcher.Invoke(() => HandlePcToAndroidTransferProgress(progress));
            return;
        }

        bool completed = string.Equals(progress.Status, "completed", StringComparison.Ordinal);
        NearShareLog.Info($"PC-to-Android progress. deviceId={progress.DeviceId:D}, fileIndex={progress.FileIndex}, totalFiles={progress.TotalFiles}, sentBytes={progress.SentBytes}, totalBytes={progress.TotalBytes}, batchPercent={progress.BatchPercent}, status={progress.Status}");
        int completedFiles = completed
            ? progress.FileIndex
            : Math.Max(0, progress.FileIndex - 1);
        TransferProgressCard.Visibility = Visibility.Visible;
        TransferProgressBar.Value = progress.BatchPercent;
        TransferProgressStatusTextBlock.Text = completed
            ? $"Sent file {progress.FileIndex} of {progress.TotalFiles} to {progress.DeviceName}: {progress.FileName}."
            : $"Sending file {progress.FileIndex} of {progress.TotalFiles} to {progress.DeviceName}: {progress.FileName}...";
        CompletedCountTextBlock.Text = $"{completedFiles} of {progress.TotalFiles} completed";
        CurrentDeviceTextBlock.Text = progress.DeviceName;
        CurrentFileTextBlock.Text = $"File {progress.FileIndex} of {progress.TotalFiles}: {progress.FileName} ({FormatBytes(progress.SentBytes)} of {FormatBytes(progress.TotalBytes)})";
        _trayStatusText = $"NearShare: sending {progress.BatchPercent}% to {progress.DeviceName}";
        UpdateTrayIconState();
    }

    private void SetReceiveMode(ReceiveMode receiveMode)
    {
        _receiveMode = receiveMode;

        ReceiveModeBadgeTextBlock.Text = _receiveMode.ToDashboardLabel();
        ManualReceiveModeButton.Content = ReceiveMode.Manual.ToSettingsLabel();
        AlwaysOnReceiveModeButton.Content = ReceiveMode.AlwaysOn.ToSettingsLabel();

        bool isManual = _receiveMode == ReceiveMode.Manual;
        SetModeButtonSelected(ManualReceiveModeButton, isManual);
        SetModeButtonSelected(AlwaysOnReceiveModeButton, !isManual);

        if (isManual)
        {
            ReceiveModeBadgeBorder.Background = new SolidColorBrush(Color.FromRgb(0xFF, 0xFB, 0xEB));
            ReceiveModeBadgeBorder.BorderBrush = new SolidColorBrush(Color.FromRgb(0xFD, 0xE6, 0x8A));
            ReceiveModeBadgeTextBlock.Foreground = new SolidColorBrush(Color.FromRgb(0x92, 0x40, 0x0E));
        }
        else
        {
            ReceiveModeBadgeBorder.Background = new SolidColorBrush(Color.FromRgb(0xEF, 0xF6, 0xFF));
            ReceiveModeBadgeBorder.BorderBrush = new SolidColorBrush(Color.FromRgb(0xBF, 0xDB, 0xFE));
            ReceiveModeBadgeTextBlock.Foreground = new SolidColorBrush(Color.FromRgb(0x1D, 0x4E, 0xD8));
        }

        UpdateTrayIconState();
    }

    private static string DescribeLaunchRequest(AppLaunchRequest request)
    {
        string targetDeviceId = request.TargetDeviceId?.ToString("D") ?? "none";
        return $"mode={request.Mode}, paths={request.Paths.Count}, targetDeviceId={targetDeviceId}, hasError={request.HasError}";
    }

    private static string FormatEndpoint(PairingEndpointCandidate? endpoint)
    {
        return endpoint is null ? "none" : $"{endpoint.Host}:{endpoint.Port}";
    }

    private void SetTransferProgressStatus(TransferStatus status)
    {
        TransferProgressCard.Visibility = status.ShowsDashboardProgressSection()
            ? Visibility.Visible
            : Visibility.Collapsed;
    }

    private static BitmapImage GenerateQrImage(string payload)
    {
        using QRCodeGenerator generator = new();
        using QRCodeData qrCodeData = generator.CreateQrCode(payload, QRCodeGenerator.ECCLevel.Q);
        PngByteQRCode qrCode = new(qrCodeData);
        byte[] qrBytes = qrCode.GetGraphic(12);

        BitmapImage image = new();
        using MemoryStream stream = new(qrBytes);
        image.BeginInit();
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.StreamSource = stream;
        image.EndInit();
        image.Freeze();

        return image;
    }

    private static string GetPreferredLocalIpv4Address()
    {
        try
        {
            IPAddress[] addresses = Dns.GetHostEntry(Dns.GetHostName()).AddressList;
            IPAddress? address = addresses.FirstOrDefault(candidate =>
                candidate.AddressFamily == AddressFamily.InterNetwork
                && !IPAddress.IsLoopback(candidate));

            return address?.ToString() ?? "127.0.0.1";
        }
        catch (SocketException)
        {
            return "127.0.0.1";
        }
    }

    private static string FormatBytes(long bytes)
    {
        if (bytes < 1024L)
        {
            return $"{bytes} B";
        }

        double kib = bytes / 1024.0;
        if (kib < 1024.0)
        {
            return $"{kib:F1} KB";
        }

        double mib = kib / 1024.0;
        if (mib < 1024.0)
        {
            return $"{mib:F1} MB";
        }

        double gib = mib / 1024.0;
        return $"{gib:F1} GB";
    }

    private void NavigateTo(NavigationSection section)
    {
        DashboardView.Visibility = section == NavigationSection.Dashboard ? Visibility.Visible : Visibility.Collapsed;
        DevicesView.Visibility = section == NavigationSection.Devices ? Visibility.Visible : Visibility.Collapsed;
        SettingsView.Visibility = section == NavigationSection.Settings ? Visibility.Visible : Visibility.Collapsed;

        SetNavButtonSelected(DashboardNavButton, section == NavigationSection.Dashboard);
        SetNavButtonSelected(DevicesNavButton, section == NavigationSection.Devices);
        SetNavButtonSelected(SettingsNavButton, section == NavigationSection.Settings);
    }

    private void SetNavButtonSelected(Button button, bool isSelected)
    {
        button.Background = isSelected
            ? new SolidColorBrush(Color.FromRgb(0xEE, 0xF2, 0xFF))
            : Brushes.Transparent;

        button.BorderBrush = isSelected
            ? new SolidColorBrush(Color.FromRgb(0xC7, 0xD2, 0xFE))
            : Brushes.Transparent;

        button.Foreground = isSelected
            ? (Brush)FindResource("PrimaryBrush")
            : (Brush)FindResource("SecondaryTextBrush");
    }

    private void SetModeButtonSelected(Button button, bool isSelected)
    {
        button.Background = isSelected
            ? (Brush)FindResource("ChipSelectedBackgroundBrush")
            : (Brush)FindResource("ChipBackgroundBrush");

        button.Foreground = isSelected
            ? new SolidColorBrush(Color.FromRgb(0x1D, 0x4E, 0xD8))
            : new SolidColorBrush(Color.FromRgb(0x47, 0x55, 0x69));

        button.BorderBrush = isSelected
            ? (Brush)FindResource("ChipSelectedBorderBrush")
            : (Brush)FindResource("ChipBorderBrush");
    }
}
