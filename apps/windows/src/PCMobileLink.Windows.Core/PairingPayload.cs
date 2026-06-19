namespace PCMobileLink.Windows.Core;

public sealed record PairingPayload
{
    public int Version { get; init; } = 1;

    public required Guid OfferId { get; init; }

    public required string PcName { get; init; }

    public required IReadOnlyList<PairingEndpointCandidate> Endpoints { get; init; }

    public required string PairingToken { get; init; }

    public string? ShortCode { get; init; }

    public required string TlsCertificateSha256 { get; init; }

    public required long ExpiresAtUnixTimeSeconds { get; init; }

    public string Transport { get; init; } = "https";
}
