using System.Net;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PCMobileLink.Windows.Infrastructure.Updates;

public sealed class GitHubReleaseUpdateChecker
{
    private const string ReleaseOwner = "thinkinglevel";
    private const string ReleaseRepo = "nearshare";
    private static readonly Uri LatestReleaseUri = new($"https://api.github.com/repos/{ReleaseOwner}/{ReleaseRepo}/releases/latest");
    private static readonly Regex VersionPattern = new(@"\d+(?:\.\d+){0,3}", RegexOptions.Compiled);
    private readonly HttpClient _httpClient;

    public GitHubReleaseUpdateChecker()
        : this(CreateDefaultHttpClient())
    {
    }

    public GitHubReleaseUpdateChecker(HttpClient httpClient)
    {
        _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));
    }

    public async Task<ReleaseUpdateCheckResult> CheckLatestAsync(string currentVersion, CancellationToken cancellationToken = default)
    {
        using HttpRequestMessage request = new(HttpMethod.Get, LatestReleaseUri);
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        request.Headers.Add("X-GitHub-Api-Version", "2026-03-10");

        try
        {
            using HttpResponseMessage response = await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
            if (response.StatusCode == HttpStatusCode.NotFound)
            {
                return new ReleaseUpdateCheckResult.Unavailable(
                    "No eligible GitHub release is available yet.");
            }

            if (!response.IsSuccessStatusCode)
            {
                return new ReleaseUpdateCheckResult.Unavailable($"GitHub returned HTTP {(int)response.StatusCode} while checking for updates.");
            }

            await using Stream responseStream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            using JsonDocument document = await JsonDocument.ParseAsync(responseStream, cancellationToken: cancellationToken).ConfigureAwait(false);
            return ParseRelease(document.RootElement, currentVersion);
        }
        catch (Exception exception) when (exception is HttpRequestException or JsonException or TaskCanceledException)
        {
            return new ReleaseUpdateCheckResult.Unavailable(exception.Message);
        }
    }

    private static ReleaseUpdateCheckResult ParseRelease(JsonElement root, string currentVersion)
    {
        string tagName = GetString(root, "tag_name");
        if (string.IsNullOrWhiteSpace(tagName))
        {
            return new ReleaseUpdateCheckResult.Unavailable("The latest release did not include a version tag.");
        }

        Version? latestVersion = ParseVersion(tagName);
        if (latestVersion is null)
        {
            return new ReleaseUpdateCheckResult.Unavailable($"The latest release tag is not a supported version: {tagName}");
        }

        Version current = ParseVersion(currentVersion) ?? new Version(0, 0, 0, 0);
        ReleaseAsset? asset = FindWindowsAsset(root);
        ReleaseAsset? checksumAsset = FindChecksumAsset(root);
        string releaseUrl = GetString(root, "html_url");
        if (string.IsNullOrWhiteSpace(releaseUrl))
        {
            releaseUrl = $"https://github.com/{ReleaseOwner}/{ReleaseRepo}/releases/latest";
        }

        return new ReleaseUpdateCheckResult.Checked(
            CurrentVersion: currentVersion,
            LatestVersion: tagName,
            ReleaseUrl: releaseUrl,
            AssetName: asset?.Name,
            AssetUrl: asset?.DownloadUrl,
            ChecksumAssetName: checksumAsset?.Name,
            ChecksumAssetUrl: checksumAsset?.DownloadUrl,
            UpdateAvailable: latestVersion.CompareTo(current) > 0);
    }

    private static ReleaseAsset? FindWindowsAsset(JsonElement root)
    {
        if (!root.TryGetProperty("assets", out JsonElement assets) || assets.ValueKind != JsonValueKind.Array)
        {
            return null;
        }

        foreach (JsonElement asset in assets.EnumerateArray())
        {
            string name = GetString(asset, "name");
            if (name.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
                name.EndsWith(".msix", StringComparison.OrdinalIgnoreCase) ||
                name.EndsWith(".msi", StringComparison.OrdinalIgnoreCase))
            {
                return new ReleaseAsset(name, GetString(asset, "browser_download_url"));
            }
        }

        return null;
    }

    private static ReleaseAsset? FindChecksumAsset(JsonElement root)
    {
        if (!root.TryGetProperty("assets", out JsonElement assets) || assets.ValueKind != JsonValueKind.Array)
        {
            return null;
        }

        foreach (JsonElement asset in assets.EnumerateArray())
        {
            string name = GetString(asset, "name");
            if (name.Contains("checksum", StringComparison.OrdinalIgnoreCase) &&
                (name.EndsWith(".txt", StringComparison.OrdinalIgnoreCase) ||
                 name.EndsWith(".sha256", StringComparison.OrdinalIgnoreCase)))
            {
                return new ReleaseAsset(name, GetString(asset, "browser_download_url"));
            }
        }

        return null;
    }

    private static Version? ParseVersion(string value)
    {
        Match match = VersionPattern.Match(value.Trim());
        if (!match.Success)
        {
            return null;
        }

        string[] rawParts = match.Value.Split('.');
        int[] parts = new[] { 0, 0, 0, 0 };
        for (int index = 0; index < rawParts.Length && index < parts.Length; index++)
        {
            if (!int.TryParse(rawParts[index], out parts[index]))
            {
                return null;
            }
        }

        return new Version(parts[0], parts[1], parts[2], parts[3]);
    }

    private static string GetString(JsonElement element, string propertyName)
    {
        return element.TryGetProperty(propertyName, out JsonElement value) && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;
    }

    private static HttpClient CreateDefaultHttpClient()
    {
        HttpClient httpClient = new()
        {
            Timeout = TimeSpan.FromSeconds(8)
        };
        httpClient.DefaultRequestHeaders.UserAgent.ParseAdd("NearShare-Windows");
        return httpClient;
    }

    private sealed record ReleaseAsset(string Name, string DownloadUrl);
}

public abstract record ReleaseUpdateCheckResult
{
    public sealed record Checked(
        string CurrentVersion,
        string LatestVersion,
        string ReleaseUrl,
        string? AssetName,
        string? AssetUrl,
        string? ChecksumAssetName,
        string? ChecksumAssetUrl,
        bool UpdateAvailable) : ReleaseUpdateCheckResult;

    public sealed record Unavailable(string Message) : ReleaseUpdateCheckResult;
}
