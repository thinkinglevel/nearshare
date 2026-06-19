namespace PCMobileLink.Windows.Core;

public sealed class ShellSendBatchAccumulator
{
    private readonly List<string> _paths = [];
    private readonly HashSet<string> _pathSet = new(StringComparer.OrdinalIgnoreCase);
    private Guid? _targetDeviceId;

    public bool HasPending => _targetDeviceId is not null && _paths.Count > 0;

    public Guid? TargetDeviceId => _targetDeviceId;

    public int Count => _paths.Count;

    public bool TryAdd(AppLaunchRequest launchRequest)
    {
        ArgumentNullException.ThrowIfNull(launchRequest);
        if (launchRequest.Mode != LaunchMode.Send
            || launchRequest.TargetDeviceId is null
            || launchRequest.Paths.Count == 0
            || launchRequest.HasError)
        {
            return false;
        }

        if (_targetDeviceId is not null && _targetDeviceId.Value != launchRequest.TargetDeviceId.Value)
        {
            return false;
        }

        _targetDeviceId ??= launchRequest.TargetDeviceId.Value;
        foreach (string path in launchRequest.Paths)
        {
            if (!string.IsNullOrWhiteSpace(path) && _pathSet.Add(path))
            {
                _paths.Add(path);
            }
        }

        return _paths.Count > 0;
    }

    public AppLaunchRequest Drain()
    {
        if (!HasPending || _targetDeviceId is null)
        {
            throw new InvalidOperationException("There is no pending NearShare shell send batch.");
        }

        AppLaunchRequest request = new()
        {
            Mode = LaunchMode.Send,
            TargetDeviceId = _targetDeviceId.Value,
            Paths = _paths.ToArray()
        };
        Clear();
        return request;
    }

    public void Clear()
    {
        _targetDeviceId = null;
        _paths.Clear();
        _pathSet.Clear();
    }
}
