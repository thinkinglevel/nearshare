namespace PCMobileLink.Windows.Core;

public sealed record AppLaunchRequest
{
    public required LaunchMode Mode { get; init; }

    public IReadOnlyList<string> Paths { get; init; } = [];

    public Guid? TargetDeviceId { get; init; }

    public string? ErrorMessage { get; init; }

    public bool HasError => !string.IsNullOrWhiteSpace(ErrorMessage);
}
