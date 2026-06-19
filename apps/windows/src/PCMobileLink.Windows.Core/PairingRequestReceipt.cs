namespace PCMobileLink.Windows.Core;

public sealed record PairingRequestReceipt
{
    public required Guid RequestId { get; init; }

    public required string Status { get; init; }

    public required string Message { get; init; }
}
