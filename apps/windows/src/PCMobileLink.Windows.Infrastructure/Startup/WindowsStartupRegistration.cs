using Microsoft.Win32;
using System.Runtime.Versioning;

namespace PCMobileLink.Windows.Infrastructure.Startup;

public sealed class WindowsStartupRegistration
{
    public const string RunValueName = "NearShare";
    public const string BackgroundArgument = "--background";

    private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

    [SupportedOSPlatform("windows")]
    public bool IsEnabled()
    {
        using RegistryKey? runKey = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: false);
        return runKey?.GetValue(RunValueName) is string value && !string.IsNullOrWhiteSpace(value);
    }

    [SupportedOSPlatform("windows")]
    public void Enable(string executablePath)
    {
        using RegistryKey runKey = Registry.CurrentUser.CreateSubKey(RunKeyPath, writable: true)
            ?? throw new InvalidOperationException("Could not open the current-user Windows startup registry key.");
        runKey.SetValue(RunValueName, BuildRunCommand(executablePath), RegistryValueKind.String);
    }

    [SupportedOSPlatform("windows")]
    public void Disable()
    {
        using RegistryKey? runKey = Registry.CurrentUser.OpenSubKey(RunKeyPath, writable: true);
        runKey?.DeleteValue(RunValueName, throwOnMissingValue: false);
    }

    public static string BuildRunCommand(string executablePath)
    {
        if (string.IsNullOrWhiteSpace(executablePath))
        {
            throw new ArgumentException("Executable path is required.", nameof(executablePath));
        }

        string escapedPath = executablePath.Replace("\"", "\\\"", StringComparison.Ordinal);
        return $"\"{escapedPath}\" {BackgroundArgument}";
    }
}
