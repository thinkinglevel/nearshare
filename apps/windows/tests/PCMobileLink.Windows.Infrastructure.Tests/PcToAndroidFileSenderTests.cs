using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Transfer;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class PcToAndroidFileSenderTests
{
    [Fact]
    public async Task SendFilesAsync_CreatesSessionAndUploadsChunkWithSignedRequests()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-PcToAndroidSenderTests-" + Guid.NewGuid());
        Directory.CreateDirectory(tempDirectory);
        string filePath = Path.Combine(tempDirectory, "hello.txt");
        await File.WriteAllTextAsync(filePath, "hello from pc");
        Guid sessionId = Guid.Parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        List<HttpRequestMessage> requests = [];
        List<PcToAndroidTransferProgress> progressUpdates = [];
        using HttpClient httpClient = new(new RecordingHandler(requests, sessionId, "hello from pc"u8.ToArray()))
        {
            BaseAddress = new Uri("https://192.168.43.1:49321")
        };
        PcToAndroidFileSender sender = new(httpClient);
        PairedDeviceRecord device = PairedDeviceWithReceiveEndpoint();

        IReadOnlyList<PcToAndroidSendResult> results = await sender.SendFilesAsync(
            device,
            [filePath],
            progress => progressUpdates.Add(progress),
            CancellationToken.None);

        Assert.Equal(2, requests.Count);
        Assert.Equal(HttpMethod.Post, requests[0].Method);
        Assert.Equal($"/nearshare/paired-devices/{device.DeviceId:D}/transfer-sessions", requests[0].RequestUri!.PathAndQuery);
        Assert.Equal(device.DeviceId.ToString("D"), requests[0].Headers.GetValues("X-NearShare-Device-Id").Single());
        Assert.Equal(HttpMethod.Put, requests[1].Method);
        Assert.Equal($"/nearshare/paired-devices/{device.DeviceId:D}/transfer-sessions/{sessionId:D}/chunks", requests[1].RequestUri!.PathAndQuery);
        Assert.Equal("0", requests[1].Headers.GetValues("X-NearShare-Chunk-Offset").Single());
        Assert.Equal("13", requests[1].Headers.GetValues("X-NearShare-Chunk-Size").Single());
        PcToAndroidSendResult result = Assert.Single(results);
        Assert.Equal("completed", result.Status);
        Assert.Equal("hello.txt", result.OriginalFileName);
        Assert.Equal(13, result.SizeBytes);
        Assert.Single(progressUpdates);
        Assert.Equal(100, progressUpdates.Single().BatchPercent);
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

    private sealed class RecordingHandler(
        List<HttpRequestMessage> requests,
        Guid sessionId,
        byte[] expectedChunk) : HttpMessageHandler
    {
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            requests.Add(CloneRequestWithoutBody(request));
            byte[] body = request.Content is null
                ? []
                : await request.Content.ReadAsByteArrayAsync(cancellationToken);

            if (request.Method == HttpMethod.Post)
            {
                using JsonDocument document = JsonDocument.Parse(body);
                Assert.Equal("hello.txt", document.RootElement.GetProperty("fileName").GetString());
                Assert.Equal(1, document.RootElement.GetProperty("fileIndex").GetInt32());
                Assert.Equal(1, document.RootElement.GetProperty("totalFiles").GetInt32());

                return JsonResponse(new TransferSessionStatusResponse
                {
                    Status = "ready",
                    SessionId = sessionId,
                    OffsetBytes = 0,
                    ChunkSizeBytes = 1024,
                    FileSizeBytes = expectedChunk.Length,
                    OriginalFileName = "hello.txt"
                });
            }

            if (request.Method == HttpMethod.Put)
            {
                Assert.Equal(expectedChunk, body);
                return JsonResponse(new TransferChunkResponse
                {
                    Status = "completed",
                    SessionId = sessionId,
                    OffsetBytes = expectedChunk.Length,
                    FileSizeBytes = expectedChunk.Length,
                    SavedFileName = "hello.txt",
                    Sha256 = PairedDeviceRequestSignature.CreateBodyHash(expectedChunk)
                });
            }

            return new HttpResponseMessage(HttpStatusCode.NotFound);
        }

        private static HttpRequestMessage CloneRequestWithoutBody(HttpRequestMessage request)
        {
            HttpRequestMessage clone = new(request.Method, request.RequestUri);
            foreach (KeyValuePair<string, IEnumerable<string>> header in request.Headers)
            {
                clone.Headers.TryAddWithoutValidation(header.Key, header.Value);
            }

            return clone;
        }

        private static HttpResponseMessage JsonResponse<T>(T body)
        {
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = JsonContent.Create(body, options: new JsonSerializerOptions(JsonSerializerDefaults.Web))
            };
        }
    }
}
