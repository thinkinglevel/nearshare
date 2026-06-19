namespace PCMobileLink.Windows.Core;

public sealed record PairingOfferStatusResponse
{
    public required Guid OfferId { get; init; }

    public required string PcName { get; init; }

    public required string Status { get; init; }

    public required long ExpiresAtUnixTimeSeconds { get; init; }
}
