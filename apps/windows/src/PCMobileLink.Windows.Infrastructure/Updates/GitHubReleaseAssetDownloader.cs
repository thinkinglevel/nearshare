using System.Security.Cryptography;
using System.Text.RegularExpressions;

namespace PCMobileLink.Windows.Infrastructure.Updates;

public sealed class GitHubReleaseAssetDownloader
{
    private static readonly Regex Sha256Pattern = new(@"\b[0-9a-fA-F]{64}\b", RegexOptions.Compiled);
    private readonly HttpClient _httpClient;

    public GitHubReleaseAssetDownloader()
        : this(CreateDefaultHttpClient())
    {
    }

    public GitHubReleaseAssetDownloader(HttpClient httpClient)
    {
        _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));
    }

    public async Task<ReleaseAssetDownloadResult> DownloadAsync(
        ReleaseUpdateCheckResult.Checked release,
        string downloadDirectory,
        IProgress<ReleaseAssetDownloadProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(release);
        ArgumentException.ThrowIfNullOrWhiteSpace(downloadDirectory);

        if (string.IsNullOrWhiteSpace(release.AssetUrl))
        {
            throw new InvalidOperationException("The latest release does not include a downloadable Windows package.");
        }

        Directory.CreateDirectory(downloadDirectory);
        string fileName = SanitizeFileName(release.AssetName ?? $"nearshare-{release.LatestVersion}.exe");
        string filePath = Path.Combine(downloadDirectory, fileName);

        await DownloadFileAsync(release.AssetUrl, filePath, progress, cancellationToken).ConfigureAwait(false);
        ReleaseAssetChecksumStatus checksumStatus = await VerifyChecksumAsync(
            filePath,
            fileName,
            release.ChecksumAssetUrl,
            cancellationToken).ConfigureAwait(false);

        return new ReleaseAssetDownloadResult(
            FilePath: filePath,
            FolderPath: downloadDirectory,
            ChecksumStatus: checksumStatus);
    }

    private async Task DownloadFileAsync(
        string assetUrl,
        string filePath,
        IProgress<ReleaseAssetDownloadProgress>? progress,
        CancellationToken cancellationToken)
    {
        using HttpRequestMessage request = new(HttpMethod.Get, assetUrl);
        request.Headers.Accept.ParseAdd("application/octet-stream");
        using HttpResponseMessage response = await _httpClient.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken).ConfigureAwait(false);
        response.EnsureSuccessStatusCode();

        long? totalBytes = response.Content.Headers.ContentLength;
        await using Stream source = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        await using FileStream destination = new(
            filePath,
            FileMode.Create,
            FileAccess.Write,
            FileShare.None,
            bufferSize: 64 * 1024,
            useAsync: true);

        byte[] buffer = new byte[64 * 1024];
        long downloadedBytes = 0;
        while (true)
        {
            int read = await source.ReadAsync(buffer.AsMemory(0, buffer.Length), cancellationToken).ConfigureAwait(false);
            if (read == 0)
            {
                break;
            }

            await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken).ConfigureAwait(false);
            downloadedBytes += read;
            progress?.Report(new ReleaseAssetDownloadProgress(
                Message: $"Downloaded {FormatBytes(downloadedBytes)}{(totalBytes is > 0 ? $" of {FormatBytes(totalBytes.Value)}" : string.Empty)}",
                Percent: totalBytes is > 0 ? downloadedBytes * 100d / totalBytes.Value : null));
        }
    }

    private async Task<ReleaseAssetChecksumStatus> VerifyChecksumAsync(
        string filePath,
        string fileName,
        string? checksumAssetUrl,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(checksumAssetUrl))
        {
            return new ReleaseAssetChecksumStatus.NotAvailable("No checksum file was published with this release.");
        }

        try
        {
            string checksumText = await _httpClient.GetStringAsync(checksumAssetUrl, cancellationToken).ConfigureAwait(false);
            string? expectedHash = FindExpectedSha256(checksumText, fileName);
            if (expectedHash is null)
            {
                return new ReleaseAssetChecksumStatus.NotAvailable($"The checksum file did not include {fileName}.");
            }

            string actualHash = await ComputeSha256Async(filePath, cancellationToken).ConfigureAwait(false);
            return string.Equals(actualHash, expectedHash, StringComparison.OrdinalIgnoreCase)
                ? new ReleaseAssetChecksumStatus.Verified()
                : new ReleaseAssetChecksumStatus.Failed("Checksum mismatch. Download the update from the release page instead.");
        }
        catch (Exception exception) when (exception is HttpRequestException or IOException or OperationCanceledException)
        {
            return new ReleaseAssetChecksumStatus.NotAvailable(exception.Message);
        }
    }

    private static string? FindExpectedSha256(string checksumText, string fileName)
    {
        foreach (string line in checksumText.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (line.Contains(fileName, StringComparison.OrdinalIgnoreCase))
            {
                Match directMatch = Sha256Pattern.Match(line);
                if (directMatch.Success)
                {
                    return directMatch.Value;
                }
            }
        }

        MatchCollection matches = Sha256Pattern.Matches(checksumText);
        return matches.Count == 1 ? matches[0].Value : null;
    }

    private static async Task<string> ComputeSha256Async(string filePath, CancellationToken cancellationToken)
    {
        await using FileStream stream = new(filePath, FileMode.Open, FileAccess.Read, FileShare.Read, bufferSize: 64 * 1024, useAsync: true);
        byte[] hash = await SHA256.HashDataAsync(stream, cancellationToken).ConfigureAwait(false);
        return Convert.ToHexString(hash).ToLowerInvariant();
    }

    private static string SanitizeFileName(string fileName)
    {
        char[] invalidChars = Path.GetInvalidFileNameChars();
        string sanitized = new(fileName.Select(character => invalidChars.Contains(character) ? '_' : character).ToArray());
        return string.IsNullOrWhiteSpace(sanitized) ? "nearshare-update.exe" : sanitized;
    }

    private static string FormatBytes(long bytes)
    {
        string[] units = new[] { "B", "KB", "MB", "GB" };
        double value = bytes;
        int unitIndex = 0;
        while (value >= 1024 && unitIndex < units.Length - 1)
        {
            value /= 1024;
            unitIndex++;
        }

        return $"{value:0.#} {units[unitIndex]}";
    }

    private static HttpClient CreateDefaultHttpClient()
    {
        HttpClient httpClient = new()
        {
            Timeout = TimeSpan.FromMinutes(30)
        };
        httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("NearShare-Windows");
        return httpClient;
    }
}

public sealed record ReleaseAssetDownloadProgress(
    string Message,
    double? Percent);

public sealed record ReleaseAssetDownloadResult(
    string FilePath,
    string FolderPath,
    ReleaseAssetChecksumStatus ChecksumStatus);

public abstract record ReleaseAssetChecksumStatus
{
    public sealed record Verified : ReleaseAssetChecksumStatus;

    public sealed record NotAvailable(string Reason) : ReleaseAssetChecksumStatus;

    public sealed record Failed(string Reason) : ReleaseAssetChecksumStatus;
}
