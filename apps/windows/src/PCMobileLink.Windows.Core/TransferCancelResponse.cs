namespace PCMobileLink.Windows.Core;

public sealed record TransferCancelResponse
{
    public required string Status { get; init; }

    public required Guid SessionId { get; init; }
}
