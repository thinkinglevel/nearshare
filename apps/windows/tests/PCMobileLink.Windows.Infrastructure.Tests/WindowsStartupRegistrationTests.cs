using PCMobileLink.Windows.Infrastructure.Startup;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class WindowsStartupRegistrationTests
{
    [Fact]
    public void BuildRunCommand_QuotesExecutablePathAndAddsBackgroundArgument()
    {
        string command = WindowsStartupRegistration.BuildRunCommand(@"C:\Program Files\NearShare\NearShare.exe");

        Assert.Equal("\"C:\\Program Files\\NearShare\\NearShare.exe\" --background", command);
    }
}
