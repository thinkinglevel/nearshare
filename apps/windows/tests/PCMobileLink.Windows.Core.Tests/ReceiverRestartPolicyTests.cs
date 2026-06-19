namespace PCMobileLink.Windows.Core.Tests;

public sealed class ReceiverRestartPolicyTests
{
    [Fact]
    public void ShouldRestartAfterReceiveFolderChanged_ReturnsTrue_WhenReceiverActiveAndFolderChanged()
    {
        Assert.True(ReceiverRestartPolicy.ShouldRestartAfterReceiveFolderChanged(
            oldReceiveFolderPath: @"C:\Users\AKSH\Downloads\NearShare",
            newReceiveFolderPath: @"D:\NearShare\Inbox",
            isReceiverRunning: true));
    }

    [Fact]
    public void ShouldRestartAfterReceiveFolderChanged_ReturnsFalse_WhenReceiverNotRunning()
    {
        Assert.False(ReceiverRestartPolicy.ShouldRestartAfterReceiveFolderChanged(
            oldReceiveFolderPath: @"C:\Users\AKSH\Downloads\NearShare",
            newReceiveFolderPath: @"D:\NearShare\Inbox",
            isReceiverRunning: false));
    }

    [Fact]
    public void ShouldRestartAfterReceiveFolderChanged_ReturnsFalse_WhenFolderDidNotChange()
    {
        Assert.False(ReceiverRestartPolicy.ShouldRestartAfterReceiveFolderChanged(
            oldReceiveFolderPath: @"C:\Users\AKSH\Downloads\NearShare",
            newReceiveFolderPath: @"C:\Users\AKSH\Downloads\NearShare\",
            isReceiverRunning: true));
    }
}
