using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class AppNavigationTests
{
    [Fact]
    public void NavigationSection_DefaultsToDashboard()
    {
        Assert.Equal(NavigationSection.Dashboard, default);
    }

    [Fact]
    public void NavigationSection_ListsSidebarItemsInExpectedOrder()
    {
        NavigationSection[] sections = Enum.GetValues<NavigationSection>();

        Assert.Equal(
            [NavigationSection.Dashboard, NavigationSection.Devices, NavigationSection.Settings],
            sections);
    }

    [Fact]
    public void ReceiveMode_ContainsManualAndAlwaysOnOptions()
    {
        ReceiveMode[] modes = Enum.GetValues<ReceiveMode>();

        Assert.Equal([ReceiveMode.Manual, ReceiveMode.AlwaysOn], modes);
    }
}
