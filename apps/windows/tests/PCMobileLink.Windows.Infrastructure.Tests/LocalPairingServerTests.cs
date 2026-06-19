using System.Net;
using System.Net.Http.Json;
using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Pairing;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class LocalPairingCertificateTests
{
    [Fact]
    public void Create_ReturnsCertificateWithSha256Fingerprint()
    {
        LocalPairingCertificate certificate = LocalPairingCertificate.Create("NearShare Test PC");

        Assert.NotNull(certificate.Certificate);
        Assert.Equal(64, certificate.Sha256Fingerprint.Length);
        Assert.All(certificate.Sha256Fingerprint, character => Assert.Contains(character, "0123456789ABCDEF"));
    }
}

public sealed class LocalPairingServerTests
{
    [Fact]
    public async Task StartAsync_CreatesReachableHttpsPairingOffer()
    {
        await using LocalPairingServer server = await LocalPairingServer.StartAsync(
            new LocalPairingServerOptions
            {
                PcName = "NearShare Test PC",
                QrEndpointHosts = ["127.0.0.1"],
                ListenAddress = IPAddress.Loopback,
                ListenPort = 0
            },
            CancellationToken.None);

        PairingPayload qrPayload = PairingPayloadCodec.Decode(server.Offer.QrUri);
        PairingEndpointCandidate endpoint = Assert.Single(qrPayload.Endpoints);

        Assert.Equal("127.0.0.1", endpoint.Host);
        Assert.True(endpoint.Port > 0);
        Assert.Equal(server.CertificateFingerprint, qrPayload.TlsCertificateSha256);

        using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
        PairingOfferStatusResponse? response = await client.GetFromJsonAsync<PairingOfferStatusResponse>(
            $"https://127.0.0.1:{endpoint.Port}/nearshare/pairing/offers/{qrPayload.OfferId}",
            CancellationToken.None);

        Assert.NotNull(response);
        Assert.Equal(qrPayload.OfferId, response.OfferId);
        Assert.Equal("NearShare Test PC", response.PcName);
        Assert.Equal("ready", response.Status);
    }

    [Fact]
    public async Task StartAsync_WithCertificatePath_ReusesPersistedCertificate()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-ServerCertificateTests-" + Guid.NewGuid());
        string certificatePath = Path.Combine(tempDirectory, "pc-pairing-certificate.pfx");

        try
        {
            string firstFingerprint;
            await using (LocalPairingServer first = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    CertificatePath = certificatePath
                },
                CancellationToken.None))
            {
                firstFingerprint = first.CertificateFingerprint;
            }

            await using LocalPairingServer second = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    CertificatePath = certificatePath
                },
                CancellationToken.None);

            Assert.Equal(firstFingerprint, second.CertificateFingerprint);
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task PostPairingRequest_WithValidToken_ReturnsPendingConfirmation()
    {
        await using LocalPairingServer server = await LocalPairingServer.StartAsync(
            new LocalPairingServerOptions
            {
                PcName = "NearShare Test PC",
                QrEndpointHosts = ["127.0.0.1"],
                ListenAddress = IPAddress.Loopback,
                ListenPort = 0
            },
            CancellationToken.None);

        PairingPayload qrPayload = PairingPayloadCodec.Decode(server.Offer.QrUri);
        PairingEndpointCandidate endpoint = Assert.Single(qrPayload.Endpoints);
        using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);

        HttpResponseMessage response = await client.PostAsJsonAsync(
            $"https://127.0.0.1:{endpoint.Port}/nearshare/pairing/requests",
            new PairingRequestPayload
            {
                OfferId = qrPayload.OfferId,
                PairingToken = qrPayload.PairingToken,
                DeviceName = "Pixel Test",
                DevicePublicKey = "test-public-key"
            },
            CancellationToken.None);

        PairingRequestReceipt? receipt = await response.Content.ReadFromJsonAsync<PairingRequestReceipt>(
            cancellationToken: CancellationToken.None);

        Assert.Equal(HttpStatusCode.Accepted, response.StatusCode);
        Assert.NotNull(receipt);
        Assert.NotEqual(Guid.Empty, receipt.RequestId);
        Assert.Equal("pending_confirmation", receipt.Status);
        Assert.Single(server.PendingRequests);
        Assert.Equal("Pixel Test", server.PendingRequests[0].DeviceName);
    }

    [Fact]
    public async Task ApprovePairingRequest_AllowsAndroidToReadApprovedResult()
    {
        await using LocalPairingServer server = await LocalPairingServer.StartAsync(
            new LocalPairingServerOptions
            {
                PcName = "NearShare Test PC",
                QrEndpointHosts = ["127.0.0.1"],
                ListenAddress = IPAddress.Loopback,
                ListenPort = 0
            },
            CancellationToken.None);

        PairingPayload qrPayload = PairingPayloadCodec.Decode(server.Offer.QrUri);
        PairingEndpointCandidate endpoint = Assert.Single(qrPayload.Endpoints);
        using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);

        HttpResponseMessage postResponse = await client.PostAsJsonAsync(
            $"https://127.0.0.1:{endpoint.Port}/nearshare/pairing/requests",
            new PairingRequestPayload
            {
                OfferId = qrPayload.OfferId,
                PairingToken = qrPayload.PairingToken,
                DeviceName = "Pixel Test",
                DevicePublicKey = "test-public-key"
            },
            CancellationToken.None);
        PairingRequestReceipt receipt = (await postResponse.Content.ReadFromJsonAsync<PairingRequestReceipt>(
            cancellationToken: CancellationToken.None))!;

        PairedDeviceRecord pairedDevice = server.ApproveRequest(receipt.RequestId);

        PairingRequestResultResponse? result = await client.GetFromJsonAsync<PairingRequestResultResponse>(
            $"https://127.0.0.1:{endpoint.Port}/nearshare/pairing/requests/{receipt.RequestId}",
            CancellationToken.None);

        Assert.Equal("approved", result?.Status);
        Assert.Equal(receipt.RequestId, result?.RequestId);
        Assert.Equal(pairedDevice.DeviceId, result?.DeviceId);
        Assert.Equal("Pixel Test", result?.DeviceName);
        Assert.False(string.IsNullOrWhiteSpace(result?.SharedSecret));
        Assert.Equal(pairedDevice.SharedSecret, result?.SharedSecret);
    }

    [Fact]
    public async Task RejectPairingRequest_AllowsAndroidToReadRejectedResult()
    {
        await using LocalPairingServer server = await LocalPairingServer.StartAsync(
            new LocalPairingServerOptions
            {
                PcName = "NearShare Test PC",
                QrEndpointHosts = ["127.0.0.1"],
                ListenAddress = IPAddress.Loopback,
                ListenPort = 0
            },
            CancellationToken.None);

        PairingPayload qrPayload = PairingPayloadCodec.Decode(server.Offer.QrUri);
        PairingEndpointCandidate endpoint = Assert.Single(qrPayload.Endpoints);
        using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);

        HttpResponseMessage postResponse = await client.PostAsJsonAsync(
            $"https://127.0.0.1:{endpoint.Port}/nearshare/pairing/requests",
            new PairingRequestPayload
            {
                OfferId = qrPayload.OfferId,
                PairingToken = qrPayload.PairingToken,
                DeviceName = "Pixel Test",
                DevicePublicKey = "test-public-key"
            },
            CancellationToken.None);
        PairingRequestReceipt receipt = (await postResponse.Content.ReadFromJsonAsync<PairingRequestReceipt>(
            cancellationToken: CancellationToken.None))!;

        Assert.True(server.RejectRequest(receipt.RequestId));

        PairingRequestResultResponse? result = await client.GetFromJsonAsync<PairingRequestResultResponse>(
            $"https://127.0.0.1:{endpoint.Port}/nearshare/pairing/requests/{receipt.RequestId}",
            CancellationToken.None);

        Assert.Equal("rejected", result?.Status);
        Assert.Equal(receipt.RequestId, result?.RequestId);
        Assert.Null(result?.DeviceId);
        Assert.Null(result?.SharedSecret);
    }

    [Fact]
    public async Task PostPairingRequest_WithWrongToken_ReturnsUnauthorized()
    {
        await using LocalPairingServer server = await LocalPairingServer.StartAsync(
            new LocalPairingServerOptions
            {
                PcName = "NearShare Test PC",
                QrEndpointHosts = ["127.0.0.1"],
                ListenAddress = IPAddress.Loopback,
                ListenPort = 0
            },
            CancellationToken.None);

        PairingPayload qrPayload = PairingPayloadCodec.Decode(server.Offer.QrUri);
        PairingEndpointCandidate endpoint = Assert.Single(qrPayload.Endpoints);
        using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);

        HttpResponseMessage response = await client.PostAsJsonAsync(
            $"https://127.0.0.1:{endpoint.Port}/nearshare/pairing/requests",
            new PairingRequestPayload
            {
                OfferId = qrPayload.OfferId,
                PairingToken = "wrong-token",
                DeviceName = "Pixel Test",
                DevicePublicKey = "test-public-key"
            },
            CancellationToken.None);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Empty(server.PendingRequests);
    }

    [Fact]
    public async Task Reachability_WithValidPairedDeviceSignature_ReturnsReachable()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-ReachabilityTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        DateTimeOffset pairedAt = DateTimeOffset.UtcNow;
        PairedDeviceStore store = new(pairedDevicesPath);
        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = deviceId,
            DeviceName = "Pixel Test",
            DevicePublicKey = "test-public-key",
            SharedSecret = sharedSecret,
            PairedAt = pairedAt,
            LastSeenAt = pairedAt
        });

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            string path = $"/nearshare/paired-devices/{deviceId}/reachability";
            using HttpRequestMessage request = CreateSignedRequest(
                HttpMethod.Get,
                path,
                deviceId,
                sharedSecret,
                timestampUnixTimeSeconds: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                nonce: "nonce-1");
            request.RequestUri = new Uri($"https://127.0.0.1:{endpoint.Port}{path}");

            HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);
            PairedDeviceReachabilityResponse? body = await response.Content.ReadFromJsonAsync<PairedDeviceReachabilityResponse>(
                cancellationToken: CancellationToken.None);

            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            Assert.NotNull(body);
            Assert.Equal("reachable", body.Status);
            Assert.Equal(deviceId, body.DeviceId);
            Assert.True(body.ServerTimeUnixSeconds > 0);
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task Reachability_WithBadSignature_ReturnsUnauthorized()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-ReachabilityTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        DateTimeOffset pairedAt = DateTimeOffset.UtcNow;
        PairedDeviceStore store = new(pairedDevicesPath);
        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = deviceId,
            DeviceName = "Pixel Test",
            DevicePublicKey = "test-public-key",
            SharedSecret = sharedSecret,
            PairedAt = pairedAt,
            LastSeenAt = pairedAt
        });

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            string path = $"/nearshare/paired-devices/{deviceId}/reachability";
            using HttpRequestMessage request = CreateSignedRequest(
                HttpMethod.Get,
                path,
                deviceId,
                sharedSecret,
                timestampUnixTimeSeconds: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                nonce: "nonce-1");
            request.Headers.Remove("X-NearShare-Signature");
            request.Headers.Add("X-NearShare-Signature", "bad-signature");
            request.RequestUri = new Uri($"https://127.0.0.1:{endpoint.Port}{path}");

            HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);

            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task Reachability_WithUnknownPairedDevice_ReturnsNotFound()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-ReachabilityTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            string path = $"/nearshare/paired-devices/{deviceId}/reachability";
            using HttpRequestMessage request = CreateSignedRequest(
                HttpMethod.Get,
                path,
                deviceId,
                sharedSecret,
                timestampUnixTimeSeconds: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                nonce: "nonce-1");
            request.RequestUri = new Uri($"https://127.0.0.1:{endpoint.Port}{path}");

            HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);

            Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    private static HttpRequestMessage CreateSignedRequest(
        HttpMethod method,
        string pathAndQuery,
        Guid deviceId,
        string sharedSecret,
        long timestampUnixTimeSeconds,
        string nonce,
        byte[]? body = null)
    {
        byte[] requestBody = body ?? [];
        string signature = PairedDeviceRequestSignature.Sign(
            sharedSecret,
            method.Method,
            pathAndQuery,
            timestampUnixTimeSeconds,
            nonce,
            requestBody);
        HttpRequestMessage request = new(method, pathAndQuery);
        request.Headers.Add("X-NearShare-Device-Id", deviceId.ToString());
        request.Headers.Add("X-NearShare-Timestamp", timestampUnixTimeSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture));
        request.Headers.Add("X-NearShare-Nonce", nonce);
        request.Headers.Add("X-NearShare-Signature", signature);
        return request;
    }

    [Fact]
    public async Task FileUpload_WithValidPairedDeviceSignature_WritesSanitizedFileToReceiveFolder()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-UploadTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        string receiveFolderPath = Path.Combine(tempDirectory, "Received");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        byte[] fileBytes = "hello from android"u8.ToArray();
        AddPairedDevice(pairedDevicesPath, deviceId, sharedSecret);

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath,
                    ReceiveFolderPath = receiveFolderPath
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            string path = $"/nearshare/paired-devices/{deviceId}/transfers/files";
            using HttpRequestMessage request = CreateSignedRequest(
                HttpMethod.Post,
                path,
                deviceId,
                sharedSecret,
                timestampUnixTimeSeconds: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                nonce: "upload-nonce-1",
                body: fileBytes);
            request.RequestUri = new Uri($"https://127.0.0.1:{endpoint.Port}{path}");
            request.Headers.Add("X-NearShare-File-Name", "..\\CON.txt");
            request.Headers.Add("X-NearShare-File-Size", fileBytes.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
            request.Content = new ByteArrayContent(fileBytes);
            request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("text/plain");

            HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);
            FileTransferReceiveResponse? body = await response.Content.ReadFromJsonAsync<FileTransferReceiveResponse>(
                cancellationToken: CancellationToken.None);

            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            Assert.NotNull(body);
            Assert.Equal("received", body.Status);
            Assert.Equal(deviceId, body.DeviceId);
            Assert.Equal("..\\CON.txt", body.OriginalFileName);
            Assert.Equal(".._CON.txt", body.SavedFileName);
            Assert.Equal(fileBytes.Length, body.SizeBytes);
            Assert.Equal(PairedDeviceRequestSignature.EncodeBase64Url(System.Security.Cryptography.SHA256.HashData(fileBytes)), body.Sha256);
            Assert.Equal(fileBytes, await File.ReadAllBytesAsync(Path.Combine(receiveFolderPath, ".._CON.txt"), CancellationToken.None));
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task FileUpload_WhenSanitizedNameExists_WritesCollisionSafeName()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-UploadTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        string receiveFolderPath = Path.Combine(tempDirectory, "Received");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        byte[] fileBytes = "new note"u8.ToArray();
        AddPairedDevice(pairedDevicesPath, deviceId, sharedSecret);
        Directory.CreateDirectory(receiveFolderPath);
        await File.WriteAllTextAsync(Path.Combine(receiveFolderPath, "note.txt"), "existing", CancellationToken.None);

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath,
                    ReceiveFolderPath = receiveFolderPath
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            string path = $"/nearshare/paired-devices/{deviceId}/transfers/files";
            using HttpRequestMessage request = CreateSignedRequest(
                HttpMethod.Post,
                path,
                deviceId,
                sharedSecret,
                timestampUnixTimeSeconds: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                nonce: "upload-nonce-2",
                body: fileBytes);
            request.RequestUri = new Uri($"https://127.0.0.1:{endpoint.Port}{path}");
            request.Headers.Add("X-NearShare-File-Name", "note.txt");
            request.Headers.Add("X-NearShare-File-Size", fileBytes.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
            request.Content = new ByteArrayContent(fileBytes);
            request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("text/plain");

            HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);
            FileTransferReceiveResponse? body = await response.Content.ReadFromJsonAsync<FileTransferReceiveResponse>(
                cancellationToken: CancellationToken.None);

            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            Assert.Equal("note (1).txt", body?.SavedFileName);
            Assert.Equal("existing", await File.ReadAllTextAsync(Path.Combine(receiveFolderPath, "note.txt"), CancellationToken.None));
            Assert.Equal(fileBytes, await File.ReadAllBytesAsync(Path.Combine(receiveFolderPath, "note (1).txt"), CancellationToken.None));
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task FileUpload_WithBadSignature_ReturnsUnauthorizedAndDoesNotWriteFile()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-UploadTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        string receiveFolderPath = Path.Combine(tempDirectory, "Received");
        string transferTempFolderPath = Path.Combine(tempDirectory, "TempUploads");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        byte[] fileBytes = "do not save"u8.ToArray();
        AddPairedDevice(pairedDevicesPath, deviceId, sharedSecret);

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath,
                    ReceiveFolderPath = receiveFolderPath,
                    TransferTempFolderPath = transferTempFolderPath
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            string path = $"/nearshare/paired-devices/{deviceId}/transfers/files";
            using HttpRequestMessage request = CreateSignedRequest(
                HttpMethod.Post,
                path,
                deviceId,
                sharedSecret,
                timestampUnixTimeSeconds: DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                nonce: "upload-nonce-3",
                body: fileBytes);
            request.Headers.Remove("X-NearShare-Signature");
            request.Headers.Add("X-NearShare-Signature", "bad-signature");
            request.RequestUri = new Uri($"https://127.0.0.1:{endpoint.Port}{path}");
            request.Headers.Add("X-NearShare-File-Name", "blocked.txt");
            request.Headers.Add("X-NearShare-File-Size", fileBytes.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
            request.Content = new ByteArrayContent(fileBytes);

            HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);

            Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
            Assert.False(File.Exists(Path.Combine(receiveFolderPath, "blocked.txt")));
            Assert.False(Directory.Exists(transferTempFolderPath)
                && Directory.EnumerateFiles(transferTempFolderPath, "*", SearchOption.AllDirectories).Any());
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task TransferSession_UploadChunks_ResumesAndCompletesFile()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-SessionTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        string receiveFolderPath = Path.Combine(tempDirectory, "Received");
        string transferTempFolderPath = Path.Combine(tempDirectory, "TempUploads");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        byte[] fileBytes = "hello resumable transfer"u8.ToArray();
        string fileSha256 = PairedDeviceRequestSignature.CreateBodyHash(fileBytes);
        List<ReceiveTransferProgressUpdate> progressUpdates = [];
        AddPairedDevice(pairedDevicesPath, deviceId, sharedSecret);

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath,
                    ReceiveFolderPath = receiveFolderPath,
                    TransferTempFolderPath = transferTempFolderPath,
                    TransferProgressChanged = progressUpdates.Add
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            Uri baseUri = new($"https://127.0.0.1:{endpoint.Port}");
            TransferSessionStatusResponse session = await CreateTransferSessionAsync(
                client,
                baseUri,
                deviceId,
                sharedSecret,
                clientSessionId: "android-session-1",
                fileName: "resume.txt",
                fileSizeBytes: fileBytes.LongLength,
                sha256: fileSha256,
                fileIndex: 3,
                totalFiles: 14);

            Assert.Equal("ready", session.Status);
            Assert.Equal(0, session.OffsetBytes);
            Assert.True(session.ChunkSizeBytes > 0);

            byte[] firstChunk = fileBytes[..6];
            byte[] secondChunk = fileBytes[6..];
            TransferChunkResponse firstResponse = await UploadChunkAsync(
                client,
                baseUri,
                deviceId,
                sharedSecret,
                session.SessionId,
                offsetBytes: 0,
                chunk: firstChunk,
                nonce: "chunk-1");
            Assert.Equal("in_progress", firstResponse.Status);
            Assert.Equal(firstChunk.Length, firstResponse.OffsetBytes);
            ReceiveTransferProgressUpdate firstProgress = Assert.Single(progressUpdates, update => update.Status == "in_progress");
            Assert.Equal(deviceId, firstProgress.DeviceId);
            Assert.Equal("Pixel Test", firstProgress.DeviceName);
            Assert.Equal("resume.txt", firstProgress.FileName);
            Assert.Equal(3, firstProgress.FileIndex);
            Assert.Equal(14, firstProgress.TotalFiles);
            Assert.Equal(firstChunk.Length, firstProgress.ReceivedBytes);
            Assert.Equal(fileBytes.LongLength, firstProgress.TotalBytes);
            Assert.Equal(25, firstProgress.PercentComplete);

            TransferSessionStatusResponse resumed = await CreateTransferSessionAsync(
                client,
                baseUri,
                deviceId,
                sharedSecret,
                clientSessionId: "android-session-1",
                fileName: "resume.txt",
                fileSizeBytes: fileBytes.LongLength,
                sha256: fileSha256,
                fileIndex: 3,
                totalFiles: 14);
            Assert.Equal(session.SessionId, resumed.SessionId);
            Assert.Equal(firstChunk.Length, resumed.OffsetBytes);

            TransferChunkResponse completed = await UploadChunkAsync(
                client,
                baseUri,
                deviceId,
                sharedSecret,
                session.SessionId,
                offsetBytes: firstChunk.Length,
                chunk: secondChunk,
                nonce: "chunk-2");

            Assert.Equal("completed", completed.Status);
            Assert.Equal(fileBytes.LongLength, completed.OffsetBytes);
            Assert.Equal("resume.txt", completed.SavedFileName);
            Assert.Equal(fileSha256, completed.Sha256);
            ReceiveTransferProgressUpdate completedProgress = Assert.Single(progressUpdates, update => update.Status == "completed");
            Assert.Equal(3, completedProgress.FileIndex);
            Assert.Equal(14, completedProgress.TotalFiles);
            Assert.Equal(fileBytes.LongLength, completedProgress.ReceivedBytes);
            Assert.Equal(100, completedProgress.PercentComplete);
            Assert.Equal(fileBytes, await File.ReadAllBytesAsync(Path.Combine(receiveFolderPath, "resume.txt"), CancellationToken.None));
            Assert.False(Directory.Exists(transferTempFolderPath)
                && Directory.EnumerateFiles(transferTempFolderPath, "*", SearchOption.AllDirectories).Any());
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    [Fact]
    public async Task TransferSession_Cancel_DeletesIncompleteTempFiles()
    {
        string tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-SessionTests-" + Guid.NewGuid());
        string pairedDevicesPath = Path.Combine(tempDirectory, "paired-devices.json");
        string receiveFolderPath = Path.Combine(tempDirectory, "Received");
        string transferTempFolderPath = Path.Combine(tempDirectory, "TempUploads");
        Guid deviceId = Guid.NewGuid();
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        byte[] fileBytes = "cancel this resumable transfer"u8.ToArray();
        AddPairedDevice(pairedDevicesPath, deviceId, sharedSecret);

        try
        {
            await using LocalPairingServer server = await LocalPairingServer.StartAsync(
                new LocalPairingServerOptions
                {
                    PcName = "NearShare Test PC",
                    QrEndpointHosts = ["127.0.0.1"],
                    ListenAddress = IPAddress.Loopback,
                    ListenPort = 0,
                    PairedDevicesPath = pairedDevicesPath,
                    ReceiveFolderPath = receiveFolderPath,
                    TransferTempFolderPath = transferTempFolderPath
                },
                CancellationToken.None);

            PairingEndpointCandidate endpoint = Assert.Single(PairingPayloadCodec.Decode(server.Offer.QrUri).Endpoints);
            using HttpClient client = CreatePinnedClient(server.CertificateFingerprint);
            Uri baseUri = new($"https://127.0.0.1:{endpoint.Port}");
            TransferSessionStatusResponse session = await CreateTransferSessionAsync(
                client,
                baseUri,
                deviceId,
                sharedSecret,
                clientSessionId: "android-session-cancel",
                fileName: "cancel.txt",
                fileSizeBytes: fileBytes.LongLength,
                sha256: PairedDeviceRequestSignature.CreateBodyHash(fileBytes));

            _ = await UploadChunkAsync(
                client,
                baseUri,
                deviceId,
                sharedSecret,
                session.SessionId,
                offsetBytes: 0,
                chunk: fileBytes[..8],
                nonce: "cancel-chunk");

            string cancelPath = $"/nearshare/paired-devices/{deviceId}/transfer-sessions/{session.SessionId}";
            using HttpRequestMessage cancel = CreateSignedRequest(
                HttpMethod.Delete,
                cancelPath,
                deviceId,
                sharedSecret,
                DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                "cancel-nonce");
            cancel.RequestUri = new Uri(baseUri, cancelPath);

            HttpResponseMessage response = await client.SendAsync(cancel, CancellationToken.None);
            TransferCancelResponse? cancelBody = await response.Content.ReadFromJsonAsync<TransferCancelResponse>(
                cancellationToken: CancellationToken.None);

            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
            Assert.Equal("cancelled", cancelBody?.Status);
            Assert.False(File.Exists(Path.Combine(receiveFolderPath, "cancel.txt")));
            Assert.False(Directory.Exists(transferTempFolderPath)
                && Directory.EnumerateFiles(transferTempFolderPath, "*", SearchOption.AllDirectories).Any());
        }
        finally
        {
            if (Directory.Exists(tempDirectory))
            {
                Directory.Delete(tempDirectory, recursive: true);
            }
        }
    }

    private static async Task<TransferSessionStatusResponse> CreateTransferSessionAsync(
        HttpClient client,
        Uri baseUri,
        Guid deviceId,
        string sharedSecret,
        string clientSessionId,
        string fileName,
        long fileSizeBytes,
        string sha256,
        int fileIndex = 1,
        int totalFiles = 1)
    {
        string path = $"/nearshare/paired-devices/{deviceId}/transfer-sessions";
        TransferSessionCreateRequest payload = new()
        {
            ClientSessionId = clientSessionId,
            FileName = fileName,
            FileSizeBytes = fileSizeBytes,
            Sha256 = sha256,
            ContentType = "text/plain",
            FileIndex = fileIndex,
            TotalFiles = totalFiles
        };
        byte[] body = System.Text.Json.JsonSerializer.SerializeToUtf8Bytes(
            payload,
            new System.Text.Json.JsonSerializerOptions(System.Text.Json.JsonSerializerDefaults.Web));
        using HttpRequestMessage request = CreateSignedRequest(
            HttpMethod.Post,
            path,
            deviceId,
            sharedSecret,
            DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            $"create-{clientSessionId}",
            body);
        request.RequestUri = new Uri(baseUri, path);
        request.Content = new ByteArrayContent(body);
        request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/json");

        HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);
        TransferSessionStatusResponse? responseBody = await response.Content.ReadFromJsonAsync<TransferSessionStatusResponse>(
            cancellationToken: CancellationToken.None);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(responseBody);
        return responseBody;
    }

    private static async Task<TransferChunkResponse> UploadChunkAsync(
        HttpClient client,
        Uri baseUri,
        Guid deviceId,
        string sharedSecret,
        Guid sessionId,
        long offsetBytes,
        byte[] chunk,
        string nonce)
    {
        string path = $"/nearshare/paired-devices/{deviceId}/transfer-sessions/{sessionId}/chunks";
        using HttpRequestMessage request = CreateSignedRequest(
            HttpMethod.Put,
            path,
            deviceId,
            sharedSecret,
            DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            nonce,
            chunk);
        request.RequestUri = new Uri(baseUri, path);
        request.Headers.Add("X-NearShare-Chunk-Offset", offsetBytes.ToString(System.Globalization.CultureInfo.InvariantCulture));
        request.Headers.Add("X-NearShare-Chunk-Size", chunk.Length.ToString(System.Globalization.CultureInfo.InvariantCulture));
        request.Content = new ByteArrayContent(chunk);
        request.Content.Headers.ContentType = new System.Net.Http.Headers.MediaTypeHeaderValue("application/octet-stream");

        HttpResponseMessage response = await client.SendAsync(request, CancellationToken.None);
        TransferChunkResponse? responseBody = await response.Content.ReadFromJsonAsync<TransferChunkResponse>(
            cancellationToken: CancellationToken.None);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.NotNull(responseBody);
        return responseBody;
    }

    private static void AddPairedDevice(string pairedDevicesPath, Guid deviceId, string sharedSecret)
    {
        DateTimeOffset pairedAt = DateTimeOffset.UtcNow;
        PairedDeviceStore store = new(pairedDevicesPath);
        store.AddOrUpdate(new PairedDeviceRecord
        {
            DeviceId = deviceId,
            DeviceName = "Pixel Test",
            DevicePublicKey = "test-public-key",
            SharedSecret = sharedSecret,
            PairedAt = pairedAt,
            LastSeenAt = pairedAt
        });
    }

    private static HttpClient CreatePinnedClient(string expectedFingerprint)
    {
        SocketsHttpHandler handler = new()
        {
            SslOptions =
            {
                RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                    string.Equals(
                        LocalPairingCertificate.GetSha256Fingerprint(certificate!),
                        expectedFingerprint,
                        StringComparison.Ordinal)
            }
        };

        return new HttpClient(handler);
    }
}
