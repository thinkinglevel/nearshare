namespace PCMobileLink.Windows.Core;

public static class ReceiverRestartPolicy
{
    public static bool ShouldRestartAfterReceiveFolderChanged(
        string oldReceiveFolderPath,
        string newReceiveFolderPath,
        bool isReceiverRunning)
    {
        if (!isReceiverRunning)
        {
            return false;
        }

        if (string.IsNullOrWhiteSpace(oldReceiveFolderPath) || string.IsNullOrWhiteSpace(newReceiveFolderPath))
        {
            return false;
        }

        string oldFullPath = NormalizeFolderPath(oldReceiveFolderPath);
        string newFullPath = NormalizeFolderPath(newReceiveFolderPath);
        return !string.Equals(oldFullPath, newFullPath, StringComparison.OrdinalIgnoreCase);
    }

    private static string NormalizeFolderPath(string folderPath)
    {
        return Path.GetFullPath(folderPath).TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
    }
}
