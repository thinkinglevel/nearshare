using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed record PendingPairingRequest
{
    public required Guid RequestId { get; init; }

    public required string DeviceName { get; init; }

    public required string DevicePublicKey { get; init; }

    public IReadOnlyList<PairingEndpointCandidate> ReceiveEndpoints { get; init; } = [];

    public string? ReceiveTlsCertificateSha256 { get; init; }

    public required DateTimeOffset ReceivedAt { get; init; }

    public string Status { get; set; } = "pending_confirmation";

    public PairedDeviceRecord? ApprovedDevice { get; set; }
}
