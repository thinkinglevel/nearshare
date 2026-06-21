using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Discovery;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed class PairingOfferDiscoveryClient
{
    private const string RequestType = "nearshare.pairing.discovery.request.v1";
    private const string ResponseType = "nearshare.pairing.discovery.response.v1";
    private static readonly TimeSpan BroadcastInterval = TimeSpan.FromMilliseconds(750);
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);
    private readonly TimeSpan _timeout;

    public PairingOfferDiscoveryClient(TimeSpan? timeout = null)
    {
        _timeout = timeout ?? TimeSpan.FromSeconds(15);
    }

    public async Task<PairingPayload> ResolveAsync(string shortCode, CancellationToken cancellationToken = default)
    {
        string normalized = PairingShortCode.Normalize(shortCode);
        if (!PairingShortCode.IsValid(normalized))
        {
            throw new FormatException("Enter the 9-character pairing code shown on the other device.");
        }

        using UdpClient udpClient = new(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };
        udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, 0));

        byte[] requestBytes = JsonSerializer.SerializeToUtf8Bytes(
            new PairingOfferDiscoveryRequest
            {
                Type = RequestType,
                ShortCode = normalized
            },
            SerializerOptions);

        DateTimeOffset deadline = DateTimeOffset.UtcNow.Add(_timeout);
        while (DateTimeOffset.UtcNow < deadline)
        {
            cancellationToken.ThrowIfCancellationRequested();
            await BroadcastRequestAsync(udpClient, requestBytes).ConfigureAwait(false);

            TimeSpan remaining = deadline - DateTimeOffset.UtcNow;
            if (remaining <= TimeSpan.Zero)
            {
                break;
            }

            PairingPayload? payload = await TryReceivePayloadAsync(
                    udpClient,
                    normalized,
                    remaining < BroadcastInterval ? remaining : BroadcastInterval,
                    cancellationToken)
                .ConfigureAwait(false);
            if (payload is not null)
            {
                return payload;
            }
        }

        throw new TimeoutException("Could not find that pairing code. Join the same Wi-Fi, or create and join a private connection first.");
    }

    private static async Task BroadcastRequestAsync(UdpClient udpClient, byte[] requestBytes)
    {
        foreach (IPEndPoint endpoint in UdpBroadcastEndpoints.Resolve(LocalDiscoveryResponderOptions.DefaultDiscoveryPort))
        {
            try
            {
                await udpClient.SendAsync(requestBytes, requestBytes.Length, endpoint).ConfigureAwait(false);
            }
            catch (SocketException)
            {
                // Some adapters reject directed broadcasts; keep trying the remaining local interfaces.
            }
        }
    }

    private static async Task<PairingPayload?> TryReceivePayloadAsync(
        UdpClient udpClient,
        string normalized,
        TimeSpan receiveWindow,
        CancellationToken cancellationToken)
    {
        using CancellationTokenSource timeoutSource = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutSource.CancelAfter(receiveWindow);

        while (!timeoutSource.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await udpClient.ReceiveAsync(timeoutSource.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
            {
                break;
            }

            PairingOfferDiscoveryResponse? response = TryParseResponse(result.Buffer, normalized);
            if (response is null)
            {
                continue;
            }

            PairingPayload payload = PairingPayloadCodec.Decode(response.PairingUri);
            if (!string.Equals(PairingShortCode.Normalize(payload.ShortCode ?? string.Empty), normalized, StringComparison.Ordinal))
            {
                continue;
            }

            return PreferResponderEndpoint(payload, result.RemoteEndPoint.Address);
        }

        return null;
    }

    private static PairingOfferDiscoveryResponse? TryParseResponse(byte[] responseBytes, string expectedShortCode)
    {
        try
        {
            PairingOfferDiscoveryResponse? response = JsonSerializer.Deserialize<PairingOfferDiscoveryResponse>(
                Encoding.UTF8.GetString(responseBytes),
                SerializerOptions);
            if (response is null
                || !string.Equals(response.Type, ResponseType, StringComparison.Ordinal)
                || !string.Equals(PairingShortCode.Normalize(response.ShortCode), expectedShortCode, StringComparison.Ordinal)
                || string.IsNullOrWhiteSpace(response.PairingUri))
            {
                return null;
            }

            return response;
        }
        catch (JsonException)
        {
            return null;
        }
        catch (ArgumentException)
        {
            return null;
        }
    }

    private static PairingPayload PreferResponderEndpoint(PairingPayload payload, IPAddress responderAddress)
    {
        PairingEndpointCandidate? primaryEndpoint = payload.Endpoints.FirstOrDefault();
        if (primaryEndpoint is null
            || IPAddress.Any.Equals(responderAddress)
            || IPAddress.IPv6Any.Equals(responderAddress)
            || IPAddress.None.Equals(responderAddress))
        {
            return payload;
        }

        string responderHost = responderAddress.ToString();
        PairingEndpointCandidate responderEndpoint = new(responderHost, primaryEndpoint.Port);
        List<PairingEndpointCandidate> endpoints = [responderEndpoint];
        endpoints.AddRange(payload.Endpoints.Where(endpoint =>
            endpoint.Port != responderEndpoint.Port
            || !string.Equals(endpoint.Host, responderEndpoint.Host, StringComparison.OrdinalIgnoreCase)));

        return payload with { Endpoints = endpoints };
    }

    private sealed record PairingOfferDiscoveryRequest
    {
        public required string Type { get; init; }

        public required string ShortCode { get; init; }
    }

    private sealed record PairingOfferDiscoveryResponse
    {
        public required string Type { get; init; }

        public required string ShortCode { get; init; }

        public required string DeviceName { get; init; }

        public required string PairingUri { get; init; }

        public required long ServerTimeUnixSeconds { get; init; }
    }
}
