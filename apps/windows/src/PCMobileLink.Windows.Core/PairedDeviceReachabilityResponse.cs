namespace PCMobileLink.Windows.Core;

public sealed record PairedDeviceReachabilityResponse
{
    public required string Status { get; init; }

    public required Guid DeviceId { get; init; }

    public required long ServerTimeUnixSeconds { get; init; }
}
