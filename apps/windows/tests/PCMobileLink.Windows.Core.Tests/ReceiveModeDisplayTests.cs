using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class ReceiveModeDisplayTests
{
    [Theory]
    [InlineData(ReceiveMode.Manual, "Manual mode")]
    [InlineData(ReceiveMode.AlwaysOn, "Always-on mode")]
    public void ToDashboardLabel_ReturnsUserFacingDashboardLabel(ReceiveMode mode, string expected)
    {
        Assert.Equal(expected, mode.ToDashboardLabel());
    }

    [Theory]
    [InlineData(ReceiveMode.Manual, "Manual receive")]
    [InlineData(ReceiveMode.AlwaysOn, "Always on")]
    public void ToSettingsLabel_ReturnsUserFacingSettingsLabel(ReceiveMode mode, string expected)
    {
        Assert.Equal(expected, mode.ToSettingsLabel());
    }
}
