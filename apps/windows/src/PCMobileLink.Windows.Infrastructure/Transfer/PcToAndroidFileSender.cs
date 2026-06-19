using System.Net.Security;
using System.Security.Cryptography;
using System.Text.Json;
using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Pairing;

namespace PCMobileLink.Windows.Infrastructure.Transfer;

public sealed class PcToAndroidFileSender
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);
    private readonly HttpClient _httpClient;


    public PcToAndroidFileSender(HttpClient httpClient)
    {
        _httpClient = httpClient ?? throw new ArgumentNullException(nameof(httpClient));
    }

    public PcToAndroidFileSender(PairedDeviceRecord device)
    {
        ArgumentNullException.ThrowIfNull(device);
        NearShareLog.Info($"Creating PC-to-Android sender. deviceId={device.DeviceId:D}, deviceName={device.DeviceName}, endpoints={device.ReceiveEndpoints.Count}, hasReceiveCert={!string.IsNullOrWhiteSpace(device.ReceiveTlsCertificateSha256)}");
        if (string.IsNullOrWhiteSpace(device.ReceiveTlsCertificateSha256))
        {
            throw new InvalidOperationException("The selected device has not shared a NearShare receive certificate yet. Open receive mode on that device and refresh pairing.");
        }

        SocketsHttpHandler handler = new()
        {
            SslOptions =
            {
                RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                    certificate is not null
                    && string.Equals(
                        LocalPairingCertificate.GetSha256Fingerprint(certificate),
                        device.ReceiveTlsCertificateSha256,
                        StringComparison.OrdinalIgnoreCase)
            }
        };
        _httpClient = new HttpClient(handler);
    }

    public async Task<IReadOnlyList<PcToAndroidSendResult>> SendFilesAsync(
        PairedDeviceRecord device,
        IReadOnlyList<string> paths,
        Action<PcToAndroidTransferProgress>? progressChanged = null,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(device);
        ArgumentNullException.ThrowIfNull(paths);
        if (paths.Count == 0)
        {
            throw new ArgumentException("At least one file is required.", nameof(paths));
        }

        NearShareLog.Info($"Preparing PC-to-Android files. deviceId={device.DeviceId:D}, fileCount={paths.Count}");
        List<PreparedLocalFile> preparedFiles = [];
        foreach (string path in paths)
        {
            preparedFiles.Add(await PrepareFileAsync(path, cancellationToken).ConfigureAwait(false));
        }
        NearShareLog.Info($"Prepared PC-to-Android files. deviceId={device.DeviceId:D}, fileCount={preparedFiles.Count}, totalBytes={preparedFiles.Sum(file => file.SizeBytes)}");

        List<PcToAndroidSendResult> results = [];
        long completedBatchBytes = 0;
        long batchTotalBytes = preparedFiles.Sum(file => file.SizeBytes);
        for (int index = 0; index < preparedFiles.Count; index++)
        {
            PreparedLocalFile file = preparedFiles[index];
            PcToAndroidSendResult result = await SendPreparedFileAsync(
                device,
                file,
                fileIndex: index + 1,
                totalFiles: preparedFiles.Count,
                completedBatchBytes,
                batchTotalBytes,
                progressChanged,
                cancellationToken).ConfigureAwait(false);
            completedBatchBytes += file.SizeBytes;
            results.Add(result);
        }

        return results;
    }

    private async Task<PcToAndroidSendResult> SendPreparedFileAsync(
        PairedDeviceRecord device,
        PreparedLocalFile file,
        int fileIndex,
        int totalFiles,
        long completedBatchBytes,
        long batchTotalBytes,
        Action<PcToAndroidTransferProgress>? progressChanged,
        CancellationToken cancellationToken)
    {
        TransferSessionStatusResponse session = await CreateSessionAsync(
            device,
            file,
            fileIndex,
            totalFiles,
            cancellationToken).ConfigureAwait(false);
        NearShareLog.Info($"Android transfer session ready. deviceId={device.DeviceId:D}, fileIndex={fileIndex}, totalFiles={totalFiles}, fileName={file.FileName}, sessionId={session.SessionId}, offsetBytes={session.OffsetBytes}, chunkSizeBytes={session.ChunkSizeBytes}");
        long offsetBytes = session.OffsetBytes;

        await using FileStream input = new(file.Path, FileMode.Open, FileAccess.Read, FileShare.Read, bufferSize: 1024 * 128, useAsync: true);
        if (offsetBytes > 0)
        {
            input.Seek(offsetBytes, SeekOrigin.Begin);
        }

        int chunkSize = Math.Max(1, session.ChunkSizeBytes);
        byte[] buffer = new byte[chunkSize];
        while (offsetBytes < file.SizeBytes)
        {
            int read = await input.ReadAsync(buffer.AsMemory(0, (int)Math.Min(buffer.Length, file.SizeBytes - offsetBytes)), cancellationToken)
                .ConfigureAwait(false);
            if (read == 0)
            {
                throw new IOException("Unexpected end of local file while sending to the selected device.");
            }

            byte[] chunk = buffer.AsSpan(0, read).ToArray();
            TransferChunkResponse chunkResponse = await UploadChunkAsync(
                device,
                session.SessionId,
                offsetBytes,
                chunk,
                cancellationToken).ConfigureAwait(false);
            offsetBytes = chunkResponse.OffsetBytes;
            progressChanged?.Invoke(new PcToAndroidTransferProgress(
                DeviceId: device.DeviceId,
                DeviceName: device.DeviceName,
                FileName: file.FileName,
                FileIndex: fileIndex,
                TotalFiles: totalFiles,
                SentBytes: offsetBytes,
                TotalBytes: file.SizeBytes,
                BatchSentBytes: completedBatchBytes + offsetBytes,
                BatchTotalBytes: batchTotalBytes,
                Status: chunkResponse.Status));

            if (string.Equals(chunkResponse.Status, "completed", StringComparison.Ordinal))
            {
                NearShareLog.Info($"Android transfer file completed. deviceId={device.DeviceId:D}, fileIndex={fileIndex}, totalFiles={totalFiles}, fileName={file.FileName}, savedFileName={chunkResponse.SavedFileName}, fileSizeBytes={chunkResponse.FileSizeBytes}");
                return new PcToAndroidSendResult(
                    Status: chunkResponse.Status,
                    OriginalFileName: file.FileName,
                    SavedFileName: string.IsNullOrWhiteSpace(chunkResponse.SavedFileName) ? file.FileName : chunkResponse.SavedFileName,
                    SizeBytes: chunkResponse.FileSizeBytes,
                    Sha256: string.IsNullOrWhiteSpace(chunkResponse.Sha256) ? file.Sha256 : chunkResponse.Sha256);
            }
        }

        throw new InvalidOperationException("Android transfer session ended without completion.");
    }

    private async Task<TransferSessionStatusResponse> CreateSessionAsync(
        PairedDeviceRecord device,
        PreparedLocalFile file,
        int fileIndex,
        int totalFiles,
        CancellationToken cancellationToken)
    {
        string urlText = PcToAndroidFileTransferClient.TransferSessionUrl(device);
        Uri url = new(urlText);
        NearShareLog.Info($"Creating Android transfer session. deviceId={device.DeviceId:D}, host={url.Host}, port={url.Port}, fileIndex={fileIndex}, totalFiles={totalFiles}, fileName={file.FileName}, sizeBytes={file.SizeBytes}");
        byte[] body = PcToAndroidFileTransferClient.TransferSessionRequestBody(
            new PcToAndroidPreparedFile(
                ClientSessionId: file.ClientSessionId,
                FileName: file.FileName,
                FileSizeBytes: file.SizeBytes,
                Sha256: file.Sha256,
                ContentType: file.ContentType),
            fileIndex,
            totalFiles);
        using HttpRequestMessage request = new(HttpMethod.Post, url)
        {
            Content = new ByteArrayContent(body)
        };
        request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");
        AddHeaders(
            request,
            PcToAndroidFileTransferClient.SignedRequestHeaders(
                device.DeviceId,
                device.SharedSecret,
                "POST",
                url.PathAndQuery,
                DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                CreateNonce(),
                body));

        using HttpResponseMessage response = await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
        NearShareLog.Info($"Android transfer session response received. deviceId={device.DeviceId:D}, statusCode={(int)response.StatusCode}, fileName={file.FileName}");
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
        TransferSessionStatusResponse? status = await JsonSerializer.DeserializeAsync<TransferSessionStatusResponse>(
            await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false),
            SerializerOptions,
            cancellationToken).ConfigureAwait(false);
        return status ?? throw new InvalidOperationException("The selected device did not return transfer session status.");
    }

    private async Task<TransferChunkResponse> UploadChunkAsync(
        PairedDeviceRecord device,
        Guid sessionId,
        long offsetBytes,
        byte[] chunk,
        CancellationToken cancellationToken)
    {
        string urlText = PcToAndroidFileTransferClient.TransferChunkUrl(device, sessionId);
        Uri url = new(urlText);
        NearShareLog.Info($"Uploading Android transfer chunk. deviceId={device.DeviceId:D}, sessionId={sessionId}, offsetBytes={offsetBytes}, chunkBytes={chunk.LongLength}, host={url.Host}, port={url.Port}");
        using HttpRequestMessage request = new(HttpMethod.Put, url)
        {
            Content = new ByteArrayContent(chunk)
        };
        request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");
        AddHeaders(
            request,
            PcToAndroidFileTransferClient.SignedChunkHeaders(
                device.DeviceId,
                device.SharedSecret,
                offsetBytes,
                chunk.LongLength,
                "PUT",
                url.PathAndQuery,
                DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                CreateNonce(),
                chunk));

        using HttpResponseMessage response = await _httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
        NearShareLog.Info($"Android transfer chunk response received. deviceId={device.DeviceId:D}, sessionId={sessionId}, statusCode={(int)response.StatusCode}, offsetBytes={offsetBytes}, chunkBytes={chunk.LongLength}");
        await EnsureSuccessAsync(response, cancellationToken).ConfigureAwait(false);
        TransferChunkResponse? status = await JsonSerializer.DeserializeAsync<TransferChunkResponse>(
            await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false),
            SerializerOptions,
            cancellationToken).ConfigureAwait(false);
        return status ?? throw new InvalidOperationException("The selected device did not return transfer chunk status.");
    }

    private static async Task<PreparedLocalFile> PrepareFileAsync(string path, CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            throw new ArgumentException("File path cannot be empty.", nameof(path));
        }

        FileInfo fileInfo = new(path);
        if (!fileInfo.Exists)
        {
            NearShareLog.Warning($"PC-to-Android file was not found while preparing. fileName={fileInfo.Name}");
            throw new FileNotFoundException("The selected file was not found.", path);
        }

        string sha256 = await ComputeSha256Async(fileInfo.FullName, cancellationToken).ConfigureAwait(false);
        NearShareLog.Info($"Prepared PC-to-Android file. fileName={fileInfo.Name}, sizeBytes={fileInfo.Length}");
        return new PreparedLocalFile(
            Path: fileInfo.FullName,
            ClientSessionId: Guid.NewGuid().ToString("D"),
            FileName: fileInfo.Name,
            SizeBytes: fileInfo.Length,
            Sha256: sha256,
            ContentType: "application/octet-stream");
    }

    private static async Task<string> ComputeSha256Async(string path, CancellationToken cancellationToken)
    {
        await using FileStream stream = new(path, FileMode.Open, FileAccess.Read, FileShare.Read, 1024 * 128, useAsync: true);
        using SHA256 sha256 = SHA256.Create();
        byte[] hash = await sha256.ComputeHashAsync(stream, cancellationToken).ConfigureAwait(false);
        return PairedDeviceRequestSignature.EncodeBase64Url(hash);
    }

    private static void AddHeaders(HttpRequestMessage request, IReadOnlyDictionary<string, string> headers)
    {
        foreach (KeyValuePair<string, string> header in headers)
        {
            request.Headers.TryAddWithoutValidation(header.Key, header.Value);
        }
    }

    private static async Task EnsureSuccessAsync(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.IsSuccessStatusCode)
        {
            return;
        }

        string body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        NearShareLog.Warning($"Android transfer HTTP failure. statusCode={(int)response.StatusCode}, body={body}");
        throw new InvalidOperationException($"Device transfer failed with HTTP {(int)response.StatusCode}: {body}");
    }

    private static string CreateNonce()
    {
        return PairedDeviceRequestSignature.EncodeBase64Url(RandomNumberGenerator.GetBytes(16));
    }

    private sealed record PreparedLocalFile(
        string Path,
        string ClientSessionId,
        string FileName,
        long SizeBytes,
        string Sha256,
        string ContentType);
}

public sealed record PcToAndroidTransferProgress(
    Guid DeviceId,
    string DeviceName,
    string FileName,
    int FileIndex,
    int TotalFiles,
    long SentBytes,
    long TotalBytes,
    long BatchSentBytes,
    long BatchTotalBytes,
    string Status)
{
    public int CurrentFilePercent => Percent(SentBytes, TotalBytes);

    public int BatchPercent => Percent(BatchSentBytes, BatchTotalBytes);

    private static int Percent(long value, long total)
    {
        if (total <= 0)
        {
            return 100;
        }

        return (int)Math.Clamp((value * 100L) / total, 0L, 100L);
    }
}

public sealed record PcToAndroidSendResult(
    string Status,
    string OriginalFileName,
    string? SavedFileName,
    long SizeBytes,
    string Sha256);
