using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Infrastructure.Settings;

public sealed class AppSettings
{
    public string ReceiveFolderPath { get; init; } = string.Empty;

    public ReceiveMode ReceiveMode { get; init; } = ReceiveMode.Manual;

    public bool StartWithWindows { get; init; }

    public bool TransferSoundsEnabled { get; init; } = true;
}
