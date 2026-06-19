namespace PCMobileLink.Windows.Core;

public sealed record TransferSessionCreateRequest
{
    public required string ClientSessionId { get; init; }

    public required string FileName { get; init; }

    public required long FileSizeBytes { get; init; }

    public required string Sha256 { get; init; }

    public required string ContentType { get; init; }

    public int FileIndex { get; init; } = 1;

    public int TotalFiles { get; init; } = 1;
}
