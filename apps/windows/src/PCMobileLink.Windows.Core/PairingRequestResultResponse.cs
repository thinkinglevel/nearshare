namespace PCMobileLink.Windows.Core;

public sealed record PairingRequestResultResponse
{
    public required Guid RequestId { get; init; }

    public required string Status { get; init; }

    public Guid? DeviceId { get; init; }

    public string? DeviceName { get; init; }

    public string? SharedSecret { get; init; }

    public string? Message { get; init; }
}
