namespace PCMobileLink.Windows.Core;

public sealed record TransferSessionStatusResponse
{
    public required string Status { get; init; }

    public required Guid SessionId { get; init; }

    public required long OffsetBytes { get; init; }

    public required int ChunkSizeBytes { get; init; }

    public required long FileSizeBytes { get; init; }

    public required string OriginalFileName { get; init; }
}
