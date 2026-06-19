using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Discovery;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class LocalDiscoveryResponderTests
{
    [Fact]
    public async Task Responder_ReturnsCurrentDynamicEndpointForMatchingCertificateFingerprint()
    {
        await using LocalDiscoveryResponder responder = await LocalDiscoveryResponder.StartAsync(
            new LocalDiscoveryResponderOptions
            {
                PcName = "NearShare Test PC",
                TlsCertificateSha256 = "A".PadLeft(64, 'A'),
                Endpoints = [new PairingEndpointCandidate("127.0.0.1", 49231)],
                ListenAddress = IPAddress.Loopback,
                ListenPort = 0
            },
            CancellationToken.None);

        using UdpClient client = new(new IPEndPoint(IPAddress.Loopback, 0));
        byte[] request = Encoding.UTF8.GetBytes(
            "{\"type\":\"nearshare.discovery.request.v1\",\"tlsCertificateSha256\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}");
        await client.SendAsync(request, request.Length, new IPEndPoint(IPAddress.Loopback, responder.ListenPort));

        UdpReceiveResult result = await ReceiveWithTimeoutAsync(client);
        using JsonDocument document = JsonDocument.Parse(Encoding.UTF8.GetString(result.Buffer));
        JsonElement root = document.RootElement;
        JsonElement endpoint = root.GetProperty("endpoints")[0];

        Assert.Equal("nearshare.discovery.response.v1", root.GetProperty("type").GetString());
        Assert.Equal("NearShare Test PC", root.GetProperty("pcName").GetString());
        Assert.Equal("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", root.GetProperty("tlsCertificateSha256").GetString());
        Assert.Equal("127.0.0.1", endpoint.GetProperty("host").GetString());
        Assert.Equal(49231, endpoint.GetProperty("port").GetInt32());
        Assert.True(root.GetProperty("serverTimeUnixSeconds").GetInt64() > 0);
    }

    private static async Task<UdpReceiveResult> ReceiveWithTimeoutAsync(UdpClient client)
    {
        Task<UdpReceiveResult> receiveTask = client.ReceiveAsync();
        Task timeoutTask = Task.Delay(TimeSpan.FromSeconds(3));
        Task completed = await Task.WhenAny(receiveTask, timeoutTask);
        if (completed == timeoutTask)
        {
            throw new TimeoutException("Timed out waiting for discovery response.");
        }

        return await receiveTask;
    }
}
