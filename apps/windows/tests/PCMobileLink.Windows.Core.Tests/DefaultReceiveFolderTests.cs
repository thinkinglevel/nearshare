using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class DefaultReceiveFolderTests
{
    [Theory]
    [InlineData("C:\\Users\\AKSH", "C:\\Users\\AKSH\\Downloads")]
    [InlineData("C:\\Users\\Alice\\", "C:\\Users\\Alice\\Downloads")]
    public void FromUserProfilePath_ReturnsDownloadsFolder(string userProfilePath, string expected)
    {
        Assert.Equal(expected, DefaultReceiveFolder.FromUserProfilePath(userProfilePath));
    }

    [Fact]
    public void FromUserProfilePath_RejectsMissingProfilePath()
    {
        Assert.Throws<ArgumentException>(() => DefaultReceiveFolder.FromUserProfilePath(" "));
    }
}
