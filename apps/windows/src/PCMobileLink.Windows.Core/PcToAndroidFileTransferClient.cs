using System.Globalization;
using System.Text.Json;

namespace PCMobileLink.Windows.Core;

public static class PcToAndroidFileTransferClient
{
    public static string TransferSessionUrl(PairedDeviceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        PairingEndpointCandidate endpoint = FirstReceiveEndpoint(record);
        return $"https://{FormatHost(endpoint.Host)}:{endpoint.Port.ToString(CultureInfo.InvariantCulture)}/nearshare/paired-devices/{record.DeviceId:D}/transfer-sessions";
    }

    public static string ReceiveReachabilityUrl(PairedDeviceRecord record)
    {
        ArgumentNullException.ThrowIfNull(record);
        PairingEndpointCandidate endpoint = FirstReceiveEndpoint(record);
        return $"https://{FormatHost(endpoint.Host)}:{endpoint.Port.ToString(CultureInfo.InvariantCulture)}/nearshare/paired-devices/{record.DeviceId:D}/reachability";
    }

    public static string TransferSessionStatusUrl(PairedDeviceRecord record, Guid sessionId)
    {
        ArgumentNullException.ThrowIfNull(record);
        PairingEndpointCandidate endpoint = FirstReceiveEndpoint(record);
        return $"https://{FormatHost(endpoint.Host)}:{endpoint.Port.ToString(CultureInfo.InvariantCulture)}/nearshare/paired-devices/{record.DeviceId:D}/transfer-sessions/{sessionId:D}";
    }

    public static string TransferChunkUrl(PairedDeviceRecord record, Guid sessionId)
    {
        return $"{TransferSessionStatusUrl(record, sessionId)}/chunks";
    }

    public static byte[] TransferSessionRequestBody(
        PcToAndroidPreparedFile preparedFile,
        int fileIndex,
        int totalFiles)
    {
        ArgumentNullException.ThrowIfNull(preparedFile);
        if (fileIndex < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(fileIndex), fileIndex, "File index must be at least 1.");
        }

        if (totalFiles < 1)
        {
            throw new ArgumentOutOfRangeException(nameof(totalFiles), totalFiles, "Total files must be at least 1.");
        }

        if (fileIndex > totalFiles)
        {
            throw new ArgumentOutOfRangeException(nameof(fileIndex), fileIndex, "File index cannot be greater than total files.");
        }

        return JsonSerializer.SerializeToUtf8Bytes(
            new TransferSessionCreateRequest
            {
                ClientSessionId = preparedFile.ClientSessionId,
                FileName = preparedFile.FileName,
                FileSizeBytes = preparedFile.FileSizeBytes,
                Sha256 = preparedFile.Sha256,
                ContentType = string.IsNullOrWhiteSpace(preparedFile.ContentType) ? "application/octet-stream" : preparedFile.ContentType,
                FileIndex = fileIndex,
                TotalFiles = totalFiles
            },
            new JsonSerializerOptions(JsonSerializerDefaults.Web));
    }

    public static IReadOnlyDictionary<string, string> SignedRequestHeaders(
        Guid pairId,
        string sharedSecret,
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        byte[] body)
    {
        return SignedRequestHeadersForBodyHash(
            pairId,
            sharedSecret,
            method,
            pathAndQuery,
            timestampUnixTimeSeconds,
            nonce,
            PairedDeviceRequestSignature.CreateBodyHash(body));
    }

    public static IReadOnlyDictionary<string, string> SignedRequestHeadersForBodyHash(
        Guid pairId,
        string sharedSecret,
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        string bodySha256Base64Url)
    {
        string signature = PairedDeviceRequestSignature.SignBodyHash(
            sharedSecret,
            method,
            pathAndQuery,
            timestampUnixTimeSeconds,
            nonce,
            bodySha256Base64Url);

        return new Dictionary<string, string>
        {
            ["X-NearShare-Device-Id"] = pairId.ToString("D"),
            ["X-NearShare-Timestamp"] = timestampUnixTimeSeconds.ToString(CultureInfo.InvariantCulture),
            ["X-NearShare-Nonce"] = nonce,
            ["X-NearShare-Signature"] = signature
        };
    }

    public static IReadOnlyDictionary<string, string> SignedChunkHeaders(
        Guid pairId,
        string sharedSecret,
        long chunkOffsetBytes,
        long chunkSizeBytes,
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        byte[] body)
    {
        if (chunkOffsetBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(chunkOffsetBytes), chunkOffsetBytes, "Chunk offset cannot be negative.");
        }

        if (chunkSizeBytes < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(chunkSizeBytes), chunkSizeBytes, "Chunk size cannot be negative.");
        }

        Dictionary<string, string> headers = new(SignedRequestHeaders(
            pairId,
            sharedSecret,
            method,
            pathAndQuery,
            timestampUnixTimeSeconds,
            nonce,
            body))
        {
            ["X-NearShare-Chunk-Offset"] = chunkOffsetBytes.ToString(CultureInfo.InvariantCulture),
            ["X-NearShare-Chunk-Size"] = chunkSizeBytes.ToString(CultureInfo.InvariantCulture)
        };
        return headers;
    }

    private static PairingEndpointCandidate FirstReceiveEndpoint(PairedDeviceRecord record)
    {
        return record.ReceiveEndpoints.FirstOrDefault()
            ?? throw new InvalidOperationException("The selected Android device is not advertising a NearShare receive endpoint. Open receive mode on the phone and try again.");
    }

    private static string FormatHost(string host)
    {
        string trimmed = host.Trim();
        ArgumentException.ThrowIfNullOrWhiteSpace(trimmed);
        return trimmed.Contains(':') && !trimmed.StartsWith('[')
            ? $"[{trimmed}]"
            : trimmed;
    }
}

public sealed record PcToAndroidPreparedFile(
    string ClientSessionId,
    string FileName,
    long FileSizeBytes,
    string Sha256,
    string ContentType);
