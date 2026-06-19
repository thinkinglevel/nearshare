using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class ExplorerSendMenuPlanTests
{
    [Fact]
    public void Build_CreatesCascadingDeviceCommandsWithTargetDeviceLaunchArguments()
    {
        Guid deviceId = Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b");
        PairedDeviceRecord device = new()
        {
            DeviceId = deviceId,
            DeviceName = "Pixel Test",
            DevicePublicKey = "public-key",
            SharedSecret = "shared-secret",
            PairedAt = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000),
            LastSeenAt = DateTimeOffset.FromUnixTimeSeconds(1_700_000_120)
        };

        ExplorerSendMenuPlan plan = ExplorerSendMenuPlan.Build("C:\\Program Files\\NearShare\\NearShare.exe", [device]);

        ExplorerSendMenuDeviceCommand command = Assert.Single(plan.DeviceCommands);
        Assert.Equal("Send using NearShare", ExplorerSendMenuPlan.RootMenuLabel);
        Assert.Equal("Pixel Test", command.Label);
        Assert.Equal(deviceId, command.DeviceId);
        Assert.Equal("\"C:\\Program Files\\NearShare\\NearShare.exe\" send --device 8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b \"%1\"", command.CommandLine);
    }

    [Fact]
    public void Build_UsesEmptyStateCommandWhenNoDevicesArePaired()
    {
        ExplorerSendMenuPlan plan = ExplorerSendMenuPlan.Build("C:\\NearShare\\NearShare.exe", []);

        ExplorerSendMenuDeviceCommand command = Assert.Single(plan.DeviceCommands);
        Assert.Equal("No paired Android devices", command.Label);
        Assert.Equal("\"C:\\NearShare\\NearShare.exe\"", command.CommandLine);
        Assert.True(command.IsPlaceholder);
    }
}
