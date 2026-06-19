namespace PCMobileLink.Windows.Core;

public sealed record ExplorerSendMenuPlan
{
    public const string RootMenuLabel = "Send using NearShare";
    public const string RootMenuKeyName = "NearShare";

    public required string ExecutablePath { get; init; }

    public required IReadOnlyList<ExplorerSendMenuDeviceCommand> DeviceCommands { get; init; }

    public static ExplorerSendMenuPlan Build(string executablePath, IReadOnlyList<PairedDeviceRecord> pairedDevices)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(executablePath);
        ArgumentNullException.ThrowIfNull(pairedDevices);

        IReadOnlyList<ExplorerSendMenuDeviceCommand> commands = pairedDevices.Count == 0
            ?
            [
                new ExplorerSendMenuDeviceCommand
                {
                    KeyName = "NoDevices",
                    Label = "No paired Android devices",
                    DeviceId = null,
                    CommandLine = QuoteForCommandLine(executablePath),
                    IsPlaceholder = true
                }
            ]
            : pairedDevices
                .OrderBy(device => device.DeviceName, StringComparer.CurrentCultureIgnoreCase)
                .Select(device => new ExplorerSendMenuDeviceCommand
                {
                    KeyName = $"Device-{device.DeviceId:D}",
                    Label = device.DeviceName,
                    DeviceId = device.DeviceId,
                    CommandLine = $"{QuoteForCommandLine(executablePath)} send --device {device.DeviceId:D} \"%1\""
                })
                .ToList();

        return new ExplorerSendMenuPlan
        {
            ExecutablePath = executablePath,
            DeviceCommands = commands
        };
    }

    public static string QuoteForCommandLine(string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        return $"\"{value.Replace("\"", "\\\"")}" + "\"";
    }
}

public sealed record ExplorerSendMenuDeviceCommand
{
    public required string KeyName { get; init; }

    public required string Label { get; init; }

    public Guid? DeviceId { get; init; }

    public required string CommandLine { get; init; }

    public bool IsPlaceholder { get; init; }
}
