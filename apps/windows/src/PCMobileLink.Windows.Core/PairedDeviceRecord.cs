namespace PCMobileLink.Windows.Core;

public sealed record PairedDeviceRecord
{
    public required Guid DeviceId { get; init; }

    public required string DeviceName { get; init; }

    public required string DevicePublicKey { get; init; }

    public required string SharedSecret { get; init; }

    public required DateTimeOffset PairedAt { get; init; }

    public required DateTimeOffset LastSeenAt { get; init; }

    public IReadOnlyList<PairingEndpointCandidate> ReceiveEndpoints { get; init; } = [];

    public string? ReceiveTlsCertificateSha256 { get; init; }
}
