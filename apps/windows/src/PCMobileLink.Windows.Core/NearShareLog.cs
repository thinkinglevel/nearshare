using System.Text;

namespace PCMobileLink.Windows.Core;

public static class NearShareLog
{
    private static readonly object SyncRoot = new();

    public static string LogFilePath { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        "NearShare",
        "logs",
        "nearshare-windows.log");

    public static void Info(string message)
    {
        Write("INFO", message);
    }

    public static void Warning(string message, Exception? exception = null)
    {
        Write("WARN", message, exception);
    }

    public static void Error(string message, Exception? exception = null)
    {
        Write("ERROR", message, exception);
    }

    private static void Write(string level, string message, Exception? exception = null)
    {
        try
        {
            string? directory = Path.GetDirectoryName(LogFilePath);
            if (!string.IsNullOrWhiteSpace(directory))
            {
                Directory.CreateDirectory(directory);
            }

            string timestamp = DateTimeOffset.UtcNow.ToString("O");
            string line = $"{timestamp} [{level}] pid={Environment.ProcessId} {message}";
            lock (SyncRoot)
            {
                using FileStream stream = new(LogFilePath, FileMode.Append, FileAccess.Write, FileShare.ReadWrite);
                using StreamWriter writer = new(stream, Encoding.UTF8);
                writer.WriteLine(line);
                if (exception is not null)
                {
                    writer.WriteLine(exception);
                }
            }
        }
        catch
        {
            // Diagnostics must never break app startup or transfer flow.
        }
    }
}
