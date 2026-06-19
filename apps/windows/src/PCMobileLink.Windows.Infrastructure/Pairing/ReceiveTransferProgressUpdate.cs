namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed record ReceiveTransferProgressUpdate
{
    public required Guid DeviceId { get; init; }

    public required string DeviceName { get; init; }

    public required Guid SessionId { get; init; }

    public required string FileName { get; init; }

    public required int FileIndex { get; init; }

    public required int TotalFiles { get; init; }

    public required long ReceivedBytes { get; init; }

    public required long TotalBytes { get; init; }

    public required int PercentComplete { get; init; }

    public required string Status { get; init; }
}
