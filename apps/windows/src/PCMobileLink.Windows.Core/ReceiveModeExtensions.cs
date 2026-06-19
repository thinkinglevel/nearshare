namespace PCMobileLink.Windows.Core;

public static class ReceiveModeExtensions
{
    public static string ToDashboardLabel(this ReceiveMode mode)
    {
        return mode switch
        {
            ReceiveMode.Manual => "Manual mode",
            ReceiveMode.AlwaysOn => "Always-on mode",
            _ => throw new ArgumentOutOfRangeException(nameof(mode), mode, "Unknown receive mode.")
        };
    }

    public static string ToSettingsLabel(this ReceiveMode mode)
    {
        return mode switch
        {
            ReceiveMode.Manual => "Manual receive",
            ReceiveMode.AlwaysOn => "Always on",
            _ => throw new ArgumentOutOfRangeException(nameof(mode), mode, "Unknown receive mode.")
        };
    }
}
