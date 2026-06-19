using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class TransferStatusTests
{
    [Fact]
    public void TransferStatus_ContainsDocumentedMvpStatesInWorkflowOrder()
    {
        string[] names = Enum.GetNames<TransferStatus>();

        Assert.Equal(
            [
                "Queued",
                "Connecting",
                "Authenticating",
                "Transferring",
                "Verifying",
                "Completed",
                "Failed",
                "Canceled"
            ],
            names);
    }

    [Theory]
    [InlineData(TransferStatus.Queued, false)]
    [InlineData(TransferStatus.Connecting, true)]
    [InlineData(TransferStatus.Authenticating, true)]
    [InlineData(TransferStatus.Transferring, true)]
    [InlineData(TransferStatus.Verifying, true)]
    [InlineData(TransferStatus.Completed, false)]
    [InlineData(TransferStatus.Failed, false)]
    [InlineData(TransferStatus.Canceled, false)]
    public void ShowsDashboardProgressSection_ReturnsTrueOnlyWhileTransferIsActive(
        TransferStatus status,
        bool expected)
    {
        Assert.Equal(expected, status.ShowsDashboardProgressSection());
    }
}
