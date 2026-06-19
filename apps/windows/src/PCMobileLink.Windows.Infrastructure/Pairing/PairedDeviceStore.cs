using System.Text.Json;
using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed class PairedDeviceStore
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web)
    {
        WriteIndented = true
    };

    private readonly string _pairedDevicesFilePath;

    public PairedDeviceStore(string pairedDevicesFilePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(pairedDevicesFilePath);
        _pairedDevicesFilePath = pairedDevicesFilePath;
    }

    public static PairedDeviceStore CreateDefault()
    {
        string appDataPath = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        string pairedDevicesFilePath = Path.Combine(appDataPath, "NearShare", "paired-devices.json");
        return new PairedDeviceStore(pairedDevicesFilePath);
    }

    public IReadOnlyList<PairedDeviceRecord> LoadAll()
    {
        if (!File.Exists(_pairedDevicesFilePath))
        {
            return [];
        }

        try
        {
            string json = File.ReadAllText(_pairedDevicesFilePath);
            List<PairedDeviceRecord>? devices = JsonSerializer.Deserialize<List<PairedDeviceRecord>>(json, SerializerOptions);
            return devices ?? [];
        }
        catch (JsonException)
        {
            return [];
        }
        catch (IOException)
        {
            return [];
        }
        catch (UnauthorizedAccessException)
        {
            return [];
        }
    }

    public void AddOrUpdate(PairedDeviceRecord device)
    {
        ArgumentNullException.ThrowIfNull(device);
        ArgumentException.ThrowIfNullOrWhiteSpace(device.DeviceName);
        ArgumentException.ThrowIfNullOrWhiteSpace(device.DevicePublicKey);
        ArgumentException.ThrowIfNullOrWhiteSpace(device.SharedSecret);

        List<PairedDeviceRecord> devices = LoadAll().ToList();
        int existingIndex = devices.FindIndex(existing =>
            existing.DeviceId == device.DeviceId
            || string.Equals(existing.DevicePublicKey, device.DevicePublicKey, StringComparison.Ordinal));

        if (existingIndex >= 0)
        {
            devices[existingIndex] = device;
        }
        else
        {
            devices.Add(device);
        }

        SaveAll(devices);
    }

    public bool Remove(Guid deviceId)
    {
        List<PairedDeviceRecord> devices = LoadAll().ToList();
        int existingIndex = devices.FindIndex(existing => existing.DeviceId == deviceId);
        if (existingIndex < 0)
        {
            return false;
        }

        devices.RemoveAt(existingIndex);
        SaveAll(devices);
        return true;
    }

    public PairedDeviceRecord? FindByDevicePublicKey(string devicePublicKey)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(devicePublicKey);
        return LoadAll().FirstOrDefault(existing =>
            string.Equals(existing.DevicePublicKey, devicePublicKey, StringComparison.Ordinal));
    }

    public PairedDeviceRecord? FindByDeviceId(Guid deviceId)
    {
        return LoadAll().FirstOrDefault(existing => existing.DeviceId == deviceId);
    }

    private void SaveAll(IReadOnlyList<PairedDeviceRecord> devices)
    {
        string? directory = Path.GetDirectoryName(_pairedDevicesFilePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        string json = JsonSerializer.Serialize(devices, SerializerOptions);
        File.WriteAllText(_pairedDevicesFilePath, json);
    }
}
