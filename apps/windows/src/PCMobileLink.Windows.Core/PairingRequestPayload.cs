namespace PCMobileLink.Windows.Core;

public sealed record PairingRequestPayload
{
    public required Guid OfferId { get; init; }

    public required string PairingToken { get; init; }

    public required string DeviceName { get; init; }

    public required string DevicePublicKey { get; init; }

    public IReadOnlyList<PairingEndpointCandidate> ReceiveEndpoints { get; init; } = [];

    public string? ReceiveTlsCertificateSha256 { get; init; }
}
