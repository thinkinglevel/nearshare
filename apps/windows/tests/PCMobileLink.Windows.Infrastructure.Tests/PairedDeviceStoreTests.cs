using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Pairing;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class PairedDeviceStoreTests : IDisposable
{
    private readonly string _tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-PairedDeviceStoreTests-" + Guid.NewGuid());

    [Fact]
    public void LoadAll_WhenFileDoesNotExist_ReturnsEmptyList()
    {
        PairedDeviceStore store = new(Path.Combine(_tempDirectory, "paired-devices.json"));

        IReadOnlyList<PairedDeviceRecord> devices = store.LoadAll();

        Assert.Empty(devices);
    }

    [Fact]
    public void AddOrUpdate_ThenLoadAll_PersistsPairedDevice()
    {
        PairedDeviceStore store = new(Path.Combine(_tempDirectory, "paired-devices.json"));
        PairedDeviceRecord device = new()
        {
            DeviceId = Guid.NewGuid(),
            DeviceName = "Pixel Test",
            DevicePublicKey = "test-public-key",
            SharedSecret = "test-shared-secret",
            PairedAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z")
        };

        store.AddOrUpdate(device);
        IReadOnlyList<PairedDeviceRecord> devices = store.LoadAll();

        PairedDeviceRecord loaded = Assert.Single(devices);
        Assert.Equal(device.DeviceId, loaded.DeviceId);
        Assert.Equal("Pixel Test", loaded.DeviceName);
        Assert.Equal("test-public-key", loaded.DevicePublicKey);
        Assert.Equal("test-shared-secret", loaded.SharedSecret);
    }

    [Fact]
    public void AddOrUpdate_WithExistingDevicePublicKey_ReplacesExistingDevice()
    {
        PairedDeviceStore store = new(Path.Combine(_tempDirectory, "paired-devices.json"));

        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = Guid.Parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
            DeviceName = "Old Pixel",
            DevicePublicKey = "same-phone-public-key",
            SharedSecret = "old-secret",
            PairedAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z")
        });

        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = Guid.Parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
            DeviceName = "Pixel Repaired",
            DevicePublicKey = "same-phone-public-key",
            SharedSecret = "new-secret",
            PairedAt = DateTimeOffset.Parse("2026-06-10T13:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-10T13:00:00Z")
        });

        PairedDeviceRecord loaded = Assert.Single(store.LoadAll());
        Assert.Equal(Guid.Parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"), loaded.DeviceId);
        Assert.Equal("Pixel Repaired", loaded.DeviceName);
        Assert.Equal("same-phone-public-key", loaded.DevicePublicKey);
        Assert.Equal("new-secret", loaded.SharedSecret);
    }

    [Fact]
    public void Remove_WhenDeviceExists_RemovesOnlyThatDevice()
    {
        PairedDeviceStore store = new(Path.Combine(_tempDirectory, "paired-devices.json"));
        Guid removedDeviceId = Guid.Parse("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Guid remainingDeviceId = Guid.Parse("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = removedDeviceId,
            DeviceName = "Pixel A",
            DevicePublicKey = "public-key-a",
            SharedSecret = "secret-a",
            PairedAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z")
        });
        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = remainingDeviceId,
            DeviceName = "Pixel B",
            DevicePublicKey = "public-key-b",
            SharedSecret = "secret-b",
            PairedAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z")
        });

        bool removed = store.Remove(removedDeviceId);

        PairedDeviceRecord loaded = Assert.Single(store.LoadAll());
        Assert.True(removed);
        Assert.Equal(remainingDeviceId, loaded.DeviceId);
        Assert.Equal("Pixel B", loaded.DeviceName);
    }

    [Fact]
    public void Remove_WhenDeviceDoesNotExist_ReturnsFalse()
    {
        PairedDeviceStore store = new(Path.Combine(_tempDirectory, "paired-devices.json"));

        bool removed = store.Remove(Guid.NewGuid());

        Assert.False(removed);
        Assert.Empty(store.LoadAll());
    }

    [Fact]
    public void FindByDevicePublicKey_WhenDeviceExists_ReturnsMatch()
    {
        PairedDeviceStore store = new(Path.Combine(_tempDirectory, "paired-devices.json"));
        PairedDeviceRecord device = new()
        {
            DeviceId = Guid.NewGuid(),
            DeviceName = "Pixel Test",
            DevicePublicKey = "test-public-key",
            SharedSecret = "test-secret",
            PairedAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z")
        };
        store.AddOrUpdate(device);

        PairedDeviceRecord? loaded = store.FindByDevicePublicKey("test-public-key");

        Assert.NotNull(loaded);
        Assert.Equal(device.DeviceId, loaded.DeviceId);
    }

    [Fact]
    public void AddOrUpdate_WithExistingDeviceId_ReplacesExistingDevice()
    {
        PairedDeviceStore store = new(Path.Combine(_tempDirectory, "paired-devices.json"));
        Guid deviceId = Guid.NewGuid();

        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = deviceId,
            DeviceName = "Old Pixel",
            DevicePublicKey = "old-public-key",
            SharedSecret = "old-secret",
            PairedAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-09T12:00:00Z")
        });

        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = deviceId,
            DeviceName = "New Pixel",
            DevicePublicKey = "new-public-key",
            SharedSecret = "new-secret",
            PairedAt = DateTimeOffset.Parse("2026-06-09T13:00:00Z"),
            LastSeenAt = DateTimeOffset.Parse("2026-06-09T13:00:00Z")
        });

        PairedDeviceRecord loaded = Assert.Single(store.LoadAll());
        Assert.Equal("New Pixel", loaded.DeviceName);
        Assert.Equal("new-public-key", loaded.DevicePublicKey);
        Assert.Equal("new-secret", loaded.SharedSecret);
    }

    public void Dispose()
    {
        if (Directory.Exists(_tempDirectory))
        {
            Directory.Delete(_tempDirectory, recursive: true);
        }
    }
}
