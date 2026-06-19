using Microsoft.Win32;
using PCMobileLink.Windows.Core;
using System.Runtime.Versioning;

namespace PCMobileLink.Windows.Infrastructure.Shell;

[SupportedOSPlatform("windows")]
public sealed class ExplorerSendMenuRegistrar
{
    private const string RootRegistryPath = @"Software\Classes\*\shell\NearShare";

    public void Install(string executablePath, IReadOnlyList<PairedDeviceRecord> pairedDevices)
    {
        ExplorerSendMenuPlan plan = ExplorerSendMenuPlan.Build(executablePath, pairedDevices);
        using RegistryKey root = Registry.CurrentUser.CreateSubKey(RootRegistryPath, writable: true)
            ?? throw new InvalidOperationException("Could not create the NearShare Explorer menu registry key.");
        root.SetValue("MUIVerb", ExplorerSendMenuPlan.RootMenuLabel, RegistryValueKind.String);
        root.SetValue("Icon", plan.ExecutablePath, RegistryValueKind.String);
        root.SetValue("SubCommands", string.Empty, RegistryValueKind.String);
        root.SetValue("MultiSelectModel", "Player", RegistryValueKind.String);
        root.DeleteSubKeyTree("shell", throwOnMissingSubKey: false);

        using RegistryKey shell = root.CreateSubKey("shell", writable: true)
            ?? throw new InvalidOperationException("Could not create the NearShare Explorer submenu registry key.");
        foreach (ExplorerSendMenuDeviceCommand deviceCommand in plan.DeviceCommands)
        {
            using RegistryKey deviceKey = shell.CreateSubKey(deviceCommand.KeyName, writable: true)
                ?? throw new InvalidOperationException($"Could not create menu entry for {deviceCommand.Label}.");
            deviceKey.SetValue("MUIVerb", deviceCommand.Label, RegistryValueKind.String);
            deviceKey.SetValue("Icon", plan.ExecutablePath, RegistryValueKind.String);
            if (deviceCommand.IsPlaceholder)
            {
                deviceKey.SetValue("CommandFlags", 0x20, RegistryValueKind.DWord);
            }

            using RegistryKey commandKey = deviceKey.CreateSubKey("command", writable: true)
                ?? throw new InvalidOperationException($"Could not create command entry for {deviceCommand.Label}.");
            commandKey.SetValue(string.Empty, deviceCommand.CommandLine, RegistryValueKind.String);
        }
    }

    public void Uninstall()
    {
        Registry.CurrentUser.DeleteSubKeyTree(RootRegistryPath, throwOnMissingSubKey: false);
    }

    public bool IsInstalled()
    {
        using RegistryKey? root = Registry.CurrentUser.OpenSubKey(RootRegistryPath, writable: false);
        return root is not null;
    }
}
