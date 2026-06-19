using System.Collections.Generic;
using System.IO;
using System.Text;

namespace PCMobileLink.Windows.Core;

public static class SafeFileName
{
    private const string FallbackName = "unnamed";

    private static readonly HashSet<string> ReservedDeviceNames = new(StringComparer.OrdinalIgnoreCase)
    {
        "CON",
        "PRN",
        "AUX",
        "NUL",
        "COM1",
        "COM2",
        "COM3",
        "COM4",
        "COM5",
        "COM6",
        "COM7",
        "COM8",
        "COM9",
        "LPT1",
        "LPT2",
        "LPT3",
        "LPT4",
        "LPT5",
        "LPT6",
        "LPT7",
        "LPT8",
        "LPT9"
    };

    public static string SanitizeForWindowsReceiveFolder(string? originalName)
    {
        if (string.IsNullOrWhiteSpace(originalName))
        {
            return FallbackName;
        }

        string trimmed = originalName.Trim();
        var builder = new StringBuilder(trimmed.Length);

        foreach (char character in trimmed)
        {
            builder.Append(IsUnsafeWindowsFileNameCharacter(character) ? '_' : character);
        }

        string sanitized = builder.ToString().Trim().TrimEnd('.', ' ');
        if (string.IsNullOrWhiteSpace(sanitized))
        {
            return FallbackName;
        }

        string baseName = Path.GetFileNameWithoutExtension(sanitized);
        if (ReservedDeviceNames.Contains(baseName))
        {
            sanitized = $"_{sanitized}";
        }

        return sanitized;
    }

    private static bool IsUnsafeWindowsFileNameCharacter(char character)
    {
        return character < 32
            || character is '<' or '>' or ':' or '"' or '/' or '\\' or '|' or '?' or '*';
    }
}
