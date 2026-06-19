using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Infrastructure.Discovery;

public sealed record LocalDiscoveryResponderOptions
{
    public const int DefaultDiscoveryPort = 53318;

    public required string PcName { get; init; }

    public required string TlsCertificateSha256 { get; init; }

    public required IReadOnlyList<PairingEndpointCandidate> Endpoints { get; init; }

    public PairingPayload? PairingOfferPayload { get; init; }

    public string? PairingOfferUri { get; init; }

    public IPAddress ListenAddress { get; init; } = IPAddress.Any;

    public int ListenPort { get; init; } = DefaultDiscoveryPort;
}

public sealed class LocalDiscoveryResponder : IAsyncDisposable
{
    private const string RequestType = "nearshare.discovery.request.v1";
    private const string ResponseType = "nearshare.discovery.response.v1";
    private const string PairingOfferRequestType = "nearshare.pairing.discovery.request.v1";
    private const string PairingOfferResponseType = "nearshare.pairing.discovery.response.v1";

    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);

    private readonly UdpClient _udpClient;
    private readonly LocalDiscoveryResponderOptions _options;
    private readonly CancellationTokenSource _cancellationTokenSource;
    private readonly Task _receiveLoop;

    private LocalDiscoveryResponder(
        UdpClient udpClient,
        LocalDiscoveryResponderOptions options,
        CancellationTokenSource cancellationTokenSource,
        int listenPort)
    {
        _udpClient = udpClient;
        _options = options;
        _cancellationTokenSource = cancellationTokenSource;
        ListenPort = listenPort;
        _receiveLoop = Task.Run(ReceiveLoopAsync);
    }

    public int ListenPort { get; }

    public static Task<LocalDiscoveryResponder> StartAsync(
        LocalDiscoveryResponderOptions options,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(options);
        ArgumentException.ThrowIfNullOrWhiteSpace(options.PcName);
        ArgumentException.ThrowIfNullOrWhiteSpace(options.TlsCertificateSha256);

        if (options.ListenPort is < 0 or > 65535)
        {
            throw new ArgumentOutOfRangeException(nameof(options.ListenPort), options.ListenPort, "Discovery port must be between 0 and 65535.");
        }

        if (options.Endpoints.Count == 0)
        {
            throw new ArgumentException("At least one endpoint must be advertised.", nameof(options));
        }

        cancellationToken.ThrowIfCancellationRequested();

        UdpClient udpClient = new(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true,
            ExclusiveAddressUse = false
        };
        udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, optionValue: true);
        udpClient.Client.Bind(new IPEndPoint(options.ListenAddress, options.ListenPort));

        int boundPort = ((IPEndPoint)udpClient.Client.LocalEndPoint!).Port;
        CancellationTokenSource cancellationTokenSource = new();
        return Task.FromResult(new LocalDiscoveryResponder(udpClient, options, cancellationTokenSource, boundPort));
    }

    public async ValueTask DisposeAsync()
    {
        _cancellationTokenSource.Cancel();
        _udpClient.Dispose();

        try
        {
            await _receiveLoop.ConfigureAwait(false);
        }
        catch (OperationCanceledException)
        {
        }
        catch (ObjectDisposedException)
        {
        }

        _cancellationTokenSource.Dispose();
    }

    private async Task ReceiveLoopAsync()
    {
        while (!_cancellationTokenSource.IsCancellationRequested)
        {
            UdpReceiveResult result;
            try
            {
                result = await _udpClient.ReceiveAsync(_cancellationTokenSource.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException)
            {
                if (_cancellationTokenSource.IsCancellationRequested)
                {
                    break;
                }

                continue;
            }

            byte[]? responseBytes = TryCreateResponse(result.Buffer);
            if (responseBytes is null)
            {
                continue;
            }

            try
            {
                await _udpClient.SendAsync(responseBytes, responseBytes.Length, result.RemoteEndPoint).ConfigureAwait(false);
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (SocketException)
            {
            }
        }
    }

    private byte[]? TryCreateResponse(byte[] requestBytes)
    {
        try
        {
            DiscoveryEnvelope? envelope = JsonSerializer.Deserialize<DiscoveryEnvelope>(requestBytes, SerializerOptions);
            if (envelope is null)
            {
                return null;
            }

            if (string.Equals(envelope.Type, PairingOfferRequestType, StringComparison.Ordinal))
            {
                return TryCreatePairingOfferResponse(requestBytes);
            }

            if (!string.Equals(envelope.Type, RequestType, StringComparison.Ordinal))
            {
                return null;
            }

            DiscoveryRequest? request = JsonSerializer.Deserialize<DiscoveryRequest>(requestBytes, SerializerOptions);
            if (request is null)
            {
                return null;
            }

            if (!string.IsNullOrWhiteSpace(request.TlsCertificateSha256)
                && !string.Equals(request.TlsCertificateSha256, _options.TlsCertificateSha256, StringComparison.OrdinalIgnoreCase))
            {
                return null;
            }

            DiscoveryResponse response = new()
            {
                Type = ResponseType,
                PcName = _options.PcName,
                TlsCertificateSha256 = _options.TlsCertificateSha256,
                Endpoints = _options.Endpoints,
                ServerTimeUnixSeconds = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
            };
            return JsonSerializer.SerializeToUtf8Bytes(response, SerializerOptions);
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

    private byte[]? TryCreatePairingOfferResponse(byte[] requestBytes)
    {
        PairingOfferDiscoveryRequest? request = JsonSerializer.Deserialize<PairingOfferDiscoveryRequest>(requestBytes, SerializerOptions);
        PairingPayload? offerPayload = _options.PairingOfferPayload;
        string? offerUri = _options.PairingOfferUri;
        string requestedCode = PairingShortCode.Normalize(request?.ShortCode ?? string.Empty);
        string activeCode = PairingShortCode.Normalize(offerPayload?.ShortCode ?? string.Empty);
        if (request is null
            || string.IsNullOrWhiteSpace(offerUri)
            || string.IsNullOrWhiteSpace(activeCode)
            || !string.Equals(requestedCode, activeCode, StringComparison.Ordinal))
        {
            return null;
        }

        PairingOfferDiscoveryResponse response = new()
        {
            Type = PairingOfferResponseType,
            ShortCode = activeCode,
            DeviceName = _options.PcName,
            PairingUri = offerUri,
            ServerTimeUnixSeconds = DateTimeOffset.UtcNow.ToUnixTimeSeconds()
        };
        return JsonSerializer.SerializeToUtf8Bytes(response, SerializerOptions);
    }

    private sealed record DiscoveryEnvelope
    {
        public string? Type { get; init; }
    }

    private sealed record DiscoveryRequest
    {
        public string? Type { get; init; }

        public string? TlsCertificateSha256 { get; init; }
    }

    private sealed record DiscoveryResponse
    {
        public required string Type { get; init; }

        public required string PcName { get; init; }

        public required string TlsCertificateSha256 { get; init; }

        public required IReadOnlyList<PairingEndpointCandidate> Endpoints { get; init; }

        public required long ServerTimeUnixSeconds { get; init; }
    }

    private sealed record PairingOfferDiscoveryRequest
    {
        public string? Type { get; init; }

        public string? ShortCode { get; init; }
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
