using System.Text.Json;

namespace PCMobileLink.Windows.Infrastructure.Settings;

public sealed class AppSettingsStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _settingsFilePath;

    public AppSettingsStore(string settingsFilePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(settingsFilePath);
        _settingsFilePath = settingsFilePath;
    }

    public static AppSettingsStore CreateDefault()
    {
        string appDataPath = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        string settingsFilePath = Path.Combine(appDataPath, "NearShare", "settings.json");
        return new AppSettingsStore(settingsFilePath);
    }

    public AppSettings LoadOrDefault(string defaultReceiveFolderPath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(defaultReceiveFolderPath);

        if (!File.Exists(_settingsFilePath))
        {
            return new AppSettings { ReceiveFolderPath = defaultReceiveFolderPath };
        }

        try
        {
            string json = File.ReadAllText(_settingsFilePath);
            AppSettings? settings = JsonSerializer.Deserialize<AppSettings>(json, SerializerOptions);

            if (settings is null || string.IsNullOrWhiteSpace(settings.ReceiveFolderPath))
            {
                return new AppSettings { ReceiveFolderPath = defaultReceiveFolderPath };
            }

            return settings;
        }
        catch (JsonException)
        {
            return new AppSettings { ReceiveFolderPath = defaultReceiveFolderPath };
        }
        catch (IOException)
        {
            return new AppSettings { ReceiveFolderPath = defaultReceiveFolderPath };
        }
        catch (UnauthorizedAccessException)
        {
            return new AppSettings { ReceiveFolderPath = defaultReceiveFolderPath };
        }
    }

    public void Save(AppSettings settings)
    {
        ArgumentNullException.ThrowIfNull(settings);
        ArgumentException.ThrowIfNullOrWhiteSpace(settings.ReceiveFolderPath);

        string? settingsDirectory = Path.GetDirectoryName(_settingsFilePath);
        if (!string.IsNullOrWhiteSpace(settingsDirectory))
        {
            Directory.CreateDirectory(settingsDirectory);
        }

        string json = JsonSerializer.Serialize(settings, SerializerOptions);
        File.WriteAllText(_settingsFilePath, json);
    }
}
