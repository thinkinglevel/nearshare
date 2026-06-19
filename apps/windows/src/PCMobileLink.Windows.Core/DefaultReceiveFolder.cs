namespace PCMobileLink.Windows.Core;

public static class DefaultReceiveFolder
{
    public static string GetCurrentUserDownloadsPath()
    {
        string userProfilePath = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        return FromUserProfilePath(userProfilePath);
    }

    public static string FromUserProfilePath(string userProfilePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(userProfilePath);

        return Path.Combine(userProfilePath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar), "Downloads");
    }
}
