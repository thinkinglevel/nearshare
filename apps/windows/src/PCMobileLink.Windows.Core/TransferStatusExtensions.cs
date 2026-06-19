namespace PCMobileLink.Windows.Core;

public static class TransferStatusExtensions
{
    public static bool ShowsDashboardProgressSection(this TransferStatus status)
    {
        return status is TransferStatus.Connecting
            or TransferStatus.Authenticating
            or TransferStatus.Transferring
            or TransferStatus.Verifying;
    }
}
