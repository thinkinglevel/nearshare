namespace PCMobileLink.Windows.Core;

public enum TransferStatus
{
    Queued,
    Connecting,
    Authenticating,
    Transferring,
    Verifying,
    Completed,
    Failed,
    Canceled
}
