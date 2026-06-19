using PCMobileLink.Windows.Core;
using System.IO;
using System.IO.Pipes;
using System.Text.Json;
using System.Threading;
using System.Windows;
using System.Windows.Threading;

namespace PCMobileLink.Windows.App;

public partial class App : System.Windows.Application
{
    private const string InstanceMutexName = "Local\\NearShare.PCMobileLink.Windows.App";
    private const string LaunchPipeName = "NearShare.PCMobileLink.Windows.App.Launch";

    private Mutex? _singleInstanceMutex;
    private CancellationTokenSource? _launchPipeCancellation;
    private DispatcherTimer? _launchInboxTimer;
    private MainWindow? _mainWindow;
    private bool _ownsSingleInstanceMutex;

    private async void Application_Startup(object sender, StartupEventArgs e)
    {
        NearShareLog.Info($"Application startup. {DescribeArgs(e.Args)}");
        _singleInstanceMutex = new Mutex(initiallyOwned: true, InstanceMutexName, out _ownsSingleInstanceMutex);
        if (!_ownsSingleInstanceMutex)
        {
            NearShareLog.Info($"Forwarding launch request to existing instance. {DescribeArgs(e.Args)}");
            await ForwardLaunchRequestAsync(e.Args).ConfigureAwait(true);
            Shutdown();
            return;
        }

        AppLaunchRequest launchRequest = AppLaunchRequestParser.Parse(e.Args);
        NearShareLog.Info($"Primary instance owns mutex. {DescribeLaunchRequest(launchRequest)}");
        _mainWindow = new MainWindow(launchRequest);
        StartLaunchPipeServer(_mainWindow);
        StartLaunchInboxPoller(_mainWindow);
        _mainWindow.Show();
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _launchPipeCancellation?.Cancel();
        _launchPipeCancellation?.Dispose();
        _launchInboxTimer?.Stop();
        if (_ownsSingleInstanceMutex)
        {
            _singleInstanceMutex?.ReleaseMutex();
        }
        _singleInstanceMutex?.Dispose();
        base.OnExit(e);
    }

    private void StartLaunchPipeServer(MainWindow mainWindow)
    {
        _launchPipeCancellation = new CancellationTokenSource();
        NearShareLog.Info("Starting launch pipe server.");
        _ = Task.Run(() => RunLaunchPipeServerAsync(mainWindow, _launchPipeCancellation.Token));
    }

    private void StartLaunchInboxPoller(MainWindow mainWindow)
    {
        _launchInboxTimer = new DispatcherTimer
        {
            Interval = TimeSpan.FromMilliseconds(500)
        };
        _launchInboxTimer.Tick += (_, _) => ProcessQueuedLaunchRequests(mainWindow);
        _launchInboxTimer.Start();
        ProcessQueuedLaunchRequests(mainWindow);
        NearShareLog.Info($"Started launch inbox poller. inbox={GetLaunchInboxDirectory()}");
    }

    private async Task RunLaunchPipeServerAsync(MainWindow mainWindow, CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            try
            {
                await using NamedPipeServerStream server = new(
                    LaunchPipeName,
                    PipeDirection.In,
                    maxNumberOfServerInstances: 1,
                    PipeTransmissionMode.Byte,
                    PipeOptions.Asynchronous);
                await server.WaitForConnectionAsync(cancellationToken).ConfigureAwait(false);
                using StreamReader reader = new(server);
                string? json = await reader.ReadLineAsync().ConfigureAwait(false);
                if (string.IsNullOrWhiteSpace(json))
                {
                    continue;
                }

                string[]? args = JsonSerializer.Deserialize<string[]>(json);
                AppLaunchRequest launchRequest = AppLaunchRequestParser.Parse(args ?? []);
                NearShareLog.Info($"Received forwarded launch request. {DescribeLaunchRequest(launchRequest)}");
                await Dispatcher.InvokeAsync(() => mainWindow.AcceptLaunchRequest(launchRequest));
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (ObjectDisposedException) when (cancellationToken.IsCancellationRequested)
            {
                return;
            }
            catch (Exception exception)
            {
                // Keep the primary app instance alive even if one forwarded launch request is malformed.
                NearShareLog.Warning("Launch pipe request failed.", exception);
            }
        }
    }

    private static async Task ForwardLaunchRequestAsync(string[] args)
    {
        try
        {
            using NamedPipeClientStream client = new(".", LaunchPipeName, PipeDirection.Out, PipeOptions.Asynchronous);
            await client.ConnectAsync(timeout: 2500).ConfigureAwait(false);
            await using StreamWriter writer = new(client) { AutoFlush = true };
            string json = JsonSerializer.Serialize(args);
            await writer.WriteLineAsync(json).ConfigureAwait(false);
            NearShareLog.Info($"Forwarded launch request to primary instance. {DescribeArgs(args)}");
        }
        catch (Exception exception)
        {
            // The original instance may still be starting. Avoid opening duplicate windows; the user can retry.
            NearShareLog.Warning($"Could not forward launch request to primary instance. {DescribeArgs(args)}", exception);
            QueueLaunchRequestForPrimaryInstance(args);
        }
    }

    private void ProcessQueuedLaunchRequests(MainWindow mainWindow)
    {
        string inboxDirectory = GetLaunchInboxDirectory();
        if (!Directory.Exists(inboxDirectory))
        {
            return;
        }

        foreach (string filePath in Directory.EnumerateFiles(inboxDirectory, "*.json").OrderBy(path => path, StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                string json = File.ReadAllText(filePath);
                string[]? args = JsonSerializer.Deserialize<string[]>(json);
                AppLaunchRequest launchRequest = AppLaunchRequestParser.Parse(args ?? []);
                NearShareLog.Info($"Processing queued launch request. file={Path.GetFileName(filePath)}, {DescribeLaunchRequest(launchRequest)}");
                mainWindow.AcceptLaunchRequest(launchRequest);
                File.Delete(filePath);
            }
            catch (Exception exception)
            {
                NearShareLog.Warning($"Queued launch request failed. file={Path.GetFileName(filePath)}", exception);
                TryDeleteFile(filePath);
            }
        }
    }

    private static void QueueLaunchRequestForPrimaryInstance(string[] args)
    {
        try
        {
            string inboxDirectory = GetLaunchInboxDirectory();
            Directory.CreateDirectory(inboxDirectory);
            string fileName = $"{DateTimeOffset.UtcNow:yyyyMMddHHmmssfffffff}-{Guid.NewGuid():N}.json";
            string tempPath = Path.Combine(inboxDirectory, fileName + ".tmp");
            string finalPath = Path.Combine(inboxDirectory, fileName);
            File.WriteAllText(tempPath, JsonSerializer.Serialize(args));
            File.Move(tempPath, finalPath);
            NearShareLog.Info($"Queued launch request through file inbox. file={fileName}, {DescribeArgs(args)}");
        }
        catch (Exception exception)
        {
            NearShareLog.Error($"Could not queue launch request through file inbox. {DescribeArgs(args)}", exception);
        }
    }

    private static string GetLaunchInboxDirectory()
    {
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "NearShare",
            "launch-requests");
    }

    private static void TryDeleteFile(string filePath)
    {
        try
        {
            File.Delete(filePath);
        }
        catch (Exception exception)
        {
            NearShareLog.Warning($"Could not delete queued launch request. file={Path.GetFileName(filePath)}", exception);
        }
    }

    private static string DescribeArgs(IReadOnlyList<string> args)
    {
        try
        {
            return DescribeLaunchRequest(AppLaunchRequestParser.Parse(args));
        }
        catch (Exception exception)
        {
            return $"argsCount={args.Count}, parseError={exception.Message}";
        }
    }

    private static string DescribeLaunchRequest(AppLaunchRequest request)
    {
        string targetDeviceId = request.TargetDeviceId?.ToString("D") ?? "none";
        return $"mode={request.Mode}, paths={request.Paths.Count}, targetDeviceId={targetDeviceId}, hasError={request.HasError}";
    }
}
