using System.Text.Json;
using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class PcToAndroidFileTransferClientTests
{
    [Fact]
    public void TransferSessionUrl_UsesFirstAndroidReceiveEndpointAndPairId()
    {
        PairedDeviceRecord record = PairedDeviceWithReceiveEndpoint();

        string url = PcToAndroidFileTransferClient.TransferSessionUrl(record);

        Assert.Equal(
            "https://192.168.43.1:49321/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfer-sessions",
            url);
    }

    [Fact]
    public void TransferChunkUrl_UsesSessionId()
    {
        PairedDeviceRecord record = PairedDeviceWithReceiveEndpoint();

        string url = PcToAndroidFileTransferClient.TransferChunkUrl(record, Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"));

        Assert.Equal(
            "https://192.168.43.1:49321/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfer-sessions/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/chunks",
            url);
    }

    [Fact]
    public void ReceiveReachabilityUrl_UsesAndroidReceiveEndpointAndPairId()
    {
        PairedDeviceRecord record = PairedDeviceWithReceiveEndpoint();

        string url = PcToAndroidFileTransferClient.ReceiveReachabilityUrl(record);

        Assert.Equal(
            "https://192.168.43.1:49321/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            url);
    }

    [Fact]
    public void TransferSessionRequestBody_IncludesBatchFilePosition()
    {
        byte[] body = PcToAndroidFileTransferClient.TransferSessionRequestBody(
            new PcToAndroidPreparedFile(
                ClientSessionId: "client-session-1",
                FileName: "photo.jpg",
                FileSizeBytes: 123,
                Sha256: "abc123",
                ContentType: "image/jpeg"),
            fileIndex: 3,
            totalFiles: 14);

        using JsonDocument document = JsonDocument.Parse(body);
        JsonElement root = document.RootElement;
        Assert.Equal("client-session-1", root.GetProperty("clientSessionId").GetString());
        Assert.Equal("photo.jpg", root.GetProperty("fileName").GetString());
        Assert.Equal(123, root.GetProperty("fileSizeBytes").GetInt64());
        Assert.Equal("abc123", root.GetProperty("sha256").GetString());
        Assert.Equal("image/jpeg", root.GetProperty("contentType").GetString());
        Assert.Equal(3, root.GetProperty("fileIndex").GetInt32());
        Assert.Equal(14, root.GetProperty("totalFiles").GetInt32());
    }

    [Fact]
    public void SignedChunkHeaders_IncludeAuthAndChunkMetadata()
    {
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        byte[] chunk = "hello "u8.ToArray();

        IReadOnlyDictionary<string, string> headers = PcToAndroidFileTransferClient.SignedChunkHeaders(
            pairId: Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b"),
            sharedSecret: sharedSecret,
            chunkOffsetBytes: 0,
            chunkSizeBytes: chunk.Length,
            method: "PUT",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfer-sessions/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/chunks",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "chunk-nonce-1",
            body: chunk);

        Assert.Equal("0", headers["X-NearShare-Chunk-Offset"]);
        Assert.Equal(chunk.Length.ToString(System.Globalization.CultureInfo.InvariantCulture), headers["X-NearShare-Chunk-Size"]);
        Assert.Equal("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b", headers["X-NearShare-Device-Id"]);
        Assert.Equal("1700000000", headers["X-NearShare-Timestamp"]);
        Assert.Equal("chunk-nonce-1", headers["X-NearShare-Nonce"]);
        Assert.False(string.IsNullOrWhiteSpace(headers["X-NearShare-Signature"]));
    }

    [Fact]
    public void TransferSessionUrl_RequiresAndroidReceiveEndpoint()
    {
        PairedDeviceRecord record = PairedDeviceWithReceiveEndpoint() with
        {
            ReceiveEndpoints = []
        };

        InvalidOperationException exception = Assert.Throws<InvalidOperationException>(() =>
            PcToAndroidFileTransferClient.TransferSessionUrl(record));

        Assert.Equal("The selected Android device is not advertising a NearShare receive endpoint. Open receive mode on the phone and try again.", exception.Message);
    }

    private static PairedDeviceRecord PairedDeviceWithReceiveEndpoint()
    {
        return new PairedDeviceRecord
        {
            DeviceId = Guid.Parse("8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b"),
            DeviceName = "Pixel Test",
            DevicePublicKey = "test-public-key",
            SharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray()),
            PairedAt = DateTimeOffset.FromUnixTimeSeconds(1_700_000_000),
            LastSeenAt = DateTimeOffset.FromUnixTimeSeconds(1_700_000_120),
            ReceiveEndpoints = [new PairingEndpointCandidate("192.168.43.1", 49321)],
            ReceiveTlsCertificateSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        };
    }
}
