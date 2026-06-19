using System.Net;
using System.Net.Http.Json;
using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Pairing;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class PairingRequestReceiveEndpointTests
{
    [Fact]
    public async Task ApprovePairingRequest_PreservesAndroidReceiveEndpointMetadata()
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
                DevicePublicKey = "test-public-key",
                ReceiveEndpoints = [new PairingEndpointCandidate("192.168.43.1", 49321)],
                ReceiveTlsCertificateSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            },
            CancellationToken.None);
        PairingRequestReceipt receipt = (await postResponse.Content.ReadFromJsonAsync<PairingRequestReceipt>(
            cancellationToken: CancellationToken.None))!;

        PairedDeviceRecord pairedDevice = server.ApproveRequest(receipt.RequestId);

        PairingEndpointCandidate receiveEndpoint = Assert.Single(pairedDevice.ReceiveEndpoints);
        Assert.Equal("192.168.43.1", receiveEndpoint.Host);
        Assert.Equal(49321, receiveEndpoint.Port);
        Assert.Equal("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", pairedDevice.ReceiveTlsCertificateSha256);
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
