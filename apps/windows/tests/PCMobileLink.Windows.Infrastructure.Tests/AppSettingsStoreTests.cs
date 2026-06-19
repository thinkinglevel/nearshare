using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Settings;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class AppSettingsStoreTests : IDisposable
{
    private readonly string _tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-SettingsTests-" + Guid.NewGuid());

    [Fact]
    public void LoadOrDefault_WhenFileDoesNotExist_ReturnsDefaultReceiveFolder()
    {
        AppSettingsStore store = new(Path.Combine(_tempDirectory, "settings.json"));

        AppSettings settings = store.LoadOrDefault("C:\\Users\\AKSH\\Downloads");

        Assert.Equal("C:\\Users\\AKSH\\Downloads", settings.ReceiveFolderPath);
        Assert.Equal(ReceiveMode.Manual, settings.ReceiveMode);
        Assert.False(settings.StartWithWindows);
        Assert.True(settings.TransferSoundsEnabled);
    }

    [Fact]
    public void Save_ThenLoadOrDefault_PersistsReceiveFolder()
    {
        AppSettingsStore store = new(Path.Combine(_tempDirectory, "settings.json"));
        AppSettings saved = new()
        {
            ReceiveFolderPath = "D:\\NearShare\\Inbox",
            ReceiveMode = ReceiveMode.AlwaysOn,
            StartWithWindows = true,
            TransferSoundsEnabled = false
        };

        store.Save(saved);
        AppSettings loaded = store.LoadOrDefault("C:\\Users\\AKSH\\Downloads");

        Assert.Equal("D:\\NearShare\\Inbox", loaded.ReceiveFolderPath);
        Assert.Equal(ReceiveMode.AlwaysOn, loaded.ReceiveMode);
        Assert.True(loaded.StartWithWindows);
        Assert.False(loaded.TransferSoundsEnabled);
    }

    public void Dispose()
    {
        if (Directory.Exists(_tempDirectory))
        {
            Directory.Delete(_tempDirectory, recursive: true);
        }
    }
}
