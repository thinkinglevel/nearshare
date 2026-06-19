namespace PCMobileLink.Windows.Core;

public sealed record TransferChunkResponse
{
    public required string Status { get; init; }

    public required Guid SessionId { get; init; }

    public required long OffsetBytes { get; init; }

    public required long FileSizeBytes { get; init; }

    public string? SavedFileName { get; init; }

    public string? Sha256 { get; init; }
}
