using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class ShellSendBatchAccumulatorTests
{
    [Fact]
    public void TryAdd_CombinesMultipleExplorerLaunchesForSameDeviceIntoOneBatch()
    {
        Guid deviceId = Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b");
        ShellSendBatchAccumulator accumulator = new();

        Assert.True(accumulator.TryAdd(SendRequest(deviceId, @"C:\One.txt")));
        Assert.True(accumulator.TryAdd(SendRequest(deviceId, @"C:\Two.txt", @"C:\Three.txt")));
        AppLaunchRequest batch = accumulator.Drain();

        Assert.Equal(LaunchMode.Send, batch.Mode);
        Assert.Equal(deviceId, batch.TargetDeviceId);
        Assert.Equal([@"C:\One.txt", @"C:\Two.txt", @"C:\Three.txt"], batch.Paths);
        Assert.False(accumulator.HasPending);
    }

    [Fact]
    public void TryAdd_DeduplicatesRepeatedExplorerPathsIgnoringCase()
    {
        Guid deviceId = Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b");
        ShellSendBatchAccumulator accumulator = new();

        Assert.True(accumulator.TryAdd(SendRequest(deviceId, @"C:\Photo.jpg")));
        Assert.True(accumulator.TryAdd(SendRequest(deviceId, @"c:\photo.jpg", @"C:\Other.jpg")));
        AppLaunchRequest batch = accumulator.Drain();

        Assert.Equal([@"C:\Photo.jpg", @"C:\Other.jpg"], batch.Paths);
    }

    [Fact]
    public void TryAdd_RejectsDifferentDeviceWhileBatchIsPending()
    {
        ShellSendBatchAccumulator accumulator = new();
        Guid firstDeviceId = Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b");
        Guid secondDeviceId = Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        Assert.True(accumulator.TryAdd(SendRequest(firstDeviceId, @"C:\One.txt")));
        Assert.False(accumulator.TryAdd(SendRequest(secondDeviceId, @"C:\Two.txt")));

        AppLaunchRequest batch = accumulator.Drain();
        Assert.Equal(firstDeviceId, batch.TargetDeviceId);
        Assert.Equal([@"C:\One.txt"], batch.Paths);
    }

    [Fact]
    public void TryAdd_IgnoresNonShellSendRequests()
    {
        ShellSendBatchAccumulator accumulator = new();

        Assert.False(accumulator.TryAdd(new AppLaunchRequest { Mode = LaunchMode.Dashboard }));
        Assert.False(accumulator.HasPending);
    }

    private static AppLaunchRequest SendRequest(Guid deviceId, params string[] paths)
    {
        return new AppLaunchRequest
        {
            Mode = LaunchMode.Send,
            TargetDeviceId = deviceId,
            Paths = paths
        };
    }
}
