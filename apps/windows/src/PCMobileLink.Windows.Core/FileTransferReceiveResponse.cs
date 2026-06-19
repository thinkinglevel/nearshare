namespace PCMobileLink.Windows.Core;

public sealed record FileTransferReceiveResponse
{
    public required string Status { get; init; }

    public required Guid DeviceId { get; init; }

    public required string OriginalFileName { get; init; }

    public required string SavedFileName { get; init; }

    public required long SizeBytes { get; init; }

    public required string Sha256 { get; init; }
}
