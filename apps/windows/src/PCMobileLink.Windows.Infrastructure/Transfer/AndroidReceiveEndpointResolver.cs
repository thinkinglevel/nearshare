using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text.Json;
using PCMobileLink.Windows.Core;
using PCMobileLink.Windows.Infrastructure.Discovery;
using PCMobileLink.Windows.Infrastructure.Pairing;

namespace PCMobileLink.Windows.Infrastructure.Transfer;

public sealed class AndroidReceiveEndpointResolver
{
    private const string DiscoveryRequestType = "nearshare.android-receive.discovery.request.v1";
    private const string DiscoveryResponseType = "nearshare.android-receive.discovery.response.v1";
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);
    private readonly TimeSpan _discoveryTimeout;

    public AndroidReceiveEndpointResolver(TimeSpan? discoveryTimeout = null)
    {
        _discoveryTimeout = discoveryTimeout ?? TimeSpan.FromSeconds(2);
    }

    public async Task<PairedDeviceRecord> ResolveAsync(
        PairedDeviceRecord device,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(device);

        List<AndroidReceiveEndpointCandidate> candidates = [];
        NearShareLog.Info($"Android receive endpoint resolution started. deviceId={device.DeviceId:D}, deviceName={device.DeviceName}, storedEndpoints={device.ReceiveEndpoints.Count}, hasReceiveCert={!string.IsNullOrWhiteSpace(device.ReceiveTlsCertificateSha256)}");
        try
        {
            IReadOnlyList<AndroidReceiveEndpointCandidate> discoveredCandidates = await DiscoverAsync(device, cancellationToken).ConfigureAwait(false);
            NearShareLog.Info($"Android receive discovery completed. deviceId={device.DeviceId:D}, discoveredCandidates={discoveredCandidates.Count}");
            candidates.AddRange(discoveredCandidates);
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            NearShareLog.Warning($"Android receive discovery failed before candidate verification. deviceId={device.DeviceId:D}", exception);
        }

        if (HasStoredReceiveEndpoint(device))
        {
            PairingEndpointCandidate endpoint = device.ReceiveEndpoints.First();
            NearShareLog.Info($"Adding stored Android receive endpoint candidate. deviceId={device.DeviceId:D}, endpoint={FormatEndpoint(endpoint)}");
            candidates.Add(new AndroidReceiveEndpointCandidate(
                endpoint,
                device.ReceiveTlsCertificateSha256!));
        }

        int verifiedCandidateCount = 0;
        foreach (AndroidReceiveEndpointCandidate candidate in DistinctCandidates(candidates))
        {
            verifiedCandidateCount++;
            NearShareLog.Info($"Verifying Android receive endpoint candidate. deviceId={device.DeviceId:D}, candidate={FormatEndpoint(candidate.Endpoint)}, candidateIndex={verifiedCandidateCount}");
            PairedDeviceRecord resolvedDevice = device with
            {
                ReceiveEndpoints = [candidate.Endpoint],
                ReceiveTlsCertificateSha256 = candidate.TlsCertificateSha256,
                LastSeenAt = DateTimeOffset.UtcNow
            };

            if (await VerifyReachabilityAsync(resolvedDevice, cancellationToken).ConfigureAwait(false))
            {
                NearShareLog.Info($"Android receive endpoint candidate verified. deviceId={device.DeviceId:D}, endpoint={FormatEndpoint(candidate.Endpoint)}");
                return resolvedDevice;
            }

            NearShareLog.Warning($"Android receive endpoint candidate was not reachable. deviceId={device.DeviceId:D}, endpoint={FormatEndpoint(candidate.Endpoint)}");
        }

        NearShareLog.Warning($"Android receive endpoint resolution failed. deviceId={device.DeviceId:D}, candidates={verifiedCandidateCount}");
        throw new InvalidOperationException("Could not connect to this device. Open receive mode on the receiver, create a private connection if needed, then try again.");
    }

    private async Task<IReadOnlyList<AndroidReceiveEndpointCandidate>> DiscoverAsync(
        PairedDeviceRecord device,
        CancellationToken cancellationToken)
    {
        using UdpClient udpClient = new(AddressFamily.InterNetwork)
        {
            EnableBroadcast = true
        };

        NearShareLog.Info($"Broadcasting Android receive discovery request. deviceId={device.DeviceId:D}, port={LocalDiscoveryResponderOptions.DefaultDiscoveryPort}, timeoutMs={(int)_discoveryTimeout.TotalMilliseconds}");
        byte[] requestBytes = JsonSerializer.SerializeToUtf8Bytes(
            new AndroidReceiveDiscoveryRequest
            {
                Type = DiscoveryRequestType,
                PairedDeviceId = device.DeviceId.ToString("D")
            },
            SerializerOptions);
        foreach (IPEndPoint endpoint in UdpBroadcastEndpoints.Resolve(LocalDiscoveryResponderOptions.DefaultDiscoveryPort))
        {
            try
            {
                await udpClient.SendAsync(requestBytes, requestBytes.Length, endpoint)
                    .WaitAsync(cancellationToken)
                    .ConfigureAwait(false);
            }
            catch (SocketException)
            {
                NearShareLog.Warning($"Android receive discovery broadcast failed for endpoint={endpoint}.");
            }
        }

        DateTimeOffset deadline = DateTimeOffset.UtcNow.Add(_discoveryTimeout);
        List<AndroidReceiveEndpointCandidate> candidates = [];
        while (DateTimeOffset.UtcNow < deadline)
        {
            TimeSpan remaining = deadline - DateTimeOffset.UtcNow;
            if (remaining <= TimeSpan.Zero)
            {
                break;
            }

            Task<UdpReceiveResult> receiveTask = udpClient.ReceiveAsync(cancellationToken).AsTask();
            Task delayTask = Task.Delay(remaining, cancellationToken);
            Task completedTask = await Task.WhenAny(receiveTask, delayTask).ConfigureAwait(false);
            if (completedTask != receiveTask)
            {
                break;
            }

            AndroidReceiveEndpointCandidate? candidate = TryParseDiscoveryResponse(
                receiveTask.Result.Buffer,
                device.DeviceId);
            if (candidate is not null)
            {
                NearShareLog.Info($"Accepted Android receive discovery response. deviceId={device.DeviceId:D}, endpoint={FormatEndpoint(candidate.Endpoint)}");
                candidates.Add(candidate);
            }
            else
            {
                NearShareLog.Warning($"Ignored Android receive discovery response. deviceId={device.DeviceId:D}, bytes={receiveTask.Result.Buffer.Length}");
            }
        }

        return candidates;
    }

    private async Task<bool> VerifyReachabilityAsync(
        PairedDeviceRecord device,
        CancellationToken cancellationToken)
    {
        if (string.IsNullOrWhiteSpace(device.ReceiveTlsCertificateSha256))
        {
            NearShareLog.Warning($"Android receive reachability skipped because receive certificate is missing. deviceId={device.DeviceId:D}");
            return false;
        }

        try
        {
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
            using HttpClient httpClient = new(handler)
            {
                Timeout = TimeSpan.FromSeconds(5)
            };
            string urlText = PcToAndroidFileTransferClient.ReceiveReachabilityUrl(device);
            Uri url = new(urlText);
            NearShareLog.Info($"Sending Android receive reachability request. deviceId={device.DeviceId:D}, host={url.Host}, port={url.Port}");
            using HttpRequestMessage request = new(HttpMethod.Get, url);
            IReadOnlyDictionary<string, string> headers = PcToAndroidFileTransferClient.SignedRequestHeaders(
                device.DeviceId,
                device.SharedSecret,
                "GET",
                url.PathAndQuery,
                DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
                CreateNonce(),
                body: []);
            foreach (KeyValuePair<string, string> header in headers)
            {
                request.Headers.TryAddWithoutValidation(header.Key, header.Value);
            }

            using HttpResponseMessage response = await httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
            {
                NearShareLog.Warning($"Android receive reachability returned HTTP failure. deviceId={device.DeviceId:D}, statusCode={(int)response.StatusCode}");
                return false;
            }

            await using Stream responseStream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
            AndroidReceiveReachabilityResponse? reachability = await JsonSerializer.DeserializeAsync<AndroidReceiveReachabilityResponse>(
                    responseStream,
                    SerializerOptions,
                    cancellationToken)
                .ConfigureAwait(false);
            bool verified = reachability is not null
                && string.Equals(reachability.Status, "reachable", StringComparison.Ordinal)
                && string.Equals(reachability.DeviceId, device.DeviceId.ToString("D"), StringComparison.OrdinalIgnoreCase);
            NearShareLog.Info($"Android receive reachability parsed. deviceId={device.DeviceId:D}, status={reachability?.Status ?? "null"}, responseDeviceId={reachability?.DeviceId ?? "null"}, verified={verified}");
            return verified;
        }
        catch (HttpRequestException exception)
        {
            NearShareLog.Warning($"Android receive reachability HTTP request failed. deviceId={device.DeviceId:D}", exception);
            return false;
        }
        catch (TaskCanceledException exception)
        {
            NearShareLog.Warning($"Android receive reachability timed out or was canceled. deviceId={device.DeviceId:D}", exception);
            return false;
        }
        catch (JsonException exception)
        {
            NearShareLog.Warning($"Android receive reachability response was not valid JSON. deviceId={device.DeviceId:D}", exception);
            return false;
        }
        catch (InvalidOperationException exception)
        {
            NearShareLog.Warning($"Android receive reachability could not be built. deviceId={device.DeviceId:D}", exception);
            return false;
        }
    }

    private static AndroidReceiveEndpointCandidate? TryParseDiscoveryResponse(byte[] responseBytes, Guid expectedDeviceId)
    {
        try
        {
            AndroidReceiveDiscoveryResponse? response = JsonSerializer.Deserialize<AndroidReceiveDiscoveryResponse>(
                responseBytes,
                SerializerOptions);
            if (response is null
                || !string.Equals(response.Type, DiscoveryResponseType, StringComparison.Ordinal)
                || !string.Equals(response.PairedDeviceId, expectedDeviceId.ToString("D"), StringComparison.OrdinalIgnoreCase)
                || response.Endpoint is null
                || string.IsNullOrWhiteSpace(response.Endpoint.Host)
                || response.Endpoint.Port is < 1 or > 65535
                || string.IsNullOrWhiteSpace(response.ReceiveTlsCertificateSha256))
            {
                return null;
            }

            return new AndroidReceiveEndpointCandidate(
                new PairingEndpointCandidate(response.Endpoint.Host, response.Endpoint.Port),
                response.ReceiveTlsCertificateSha256);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static IEnumerable<AndroidReceiveEndpointCandidate> DistinctCandidates(IEnumerable<AndroidReceiveEndpointCandidate> candidates)
    {
        HashSet<string> seen = new(StringComparer.OrdinalIgnoreCase);
        foreach (AndroidReceiveEndpointCandidate candidate in candidates)
        {
            string key = $"{candidate.Endpoint.Host}:{candidate.Endpoint.Port}:{candidate.TlsCertificateSha256}";
            if (seen.Add(key))
            {
                yield return candidate;
            }
        }
    }

    private static bool HasStoredReceiveEndpoint(PairedDeviceRecord device)
    {
        return device.ReceiveEndpoints.Count > 0
            && !string.IsNullOrWhiteSpace(device.ReceiveTlsCertificateSha256);
    }

    private static string CreateNonce()
    {
        return PairedDeviceRequestSignature.EncodeBase64Url(RandomNumberGenerator.GetBytes(16));
    }

    private static string FormatEndpoint(PairingEndpointCandidate endpoint)
    {
        return $"{endpoint.Host}:{endpoint.Port}";
    }

    private sealed record AndroidReceiveEndpointCandidate(
        PairingEndpointCandidate Endpoint,
        string TlsCertificateSha256);

    private sealed record AndroidReceiveDiscoveryRequest
    {
        public required string Type { get; init; }

        public required string PairedDeviceId { get; init; }
    }

    private sealed record AndroidReceiveDiscoveryResponse
    {
        public string? Type { get; init; }

        public string? PairedDeviceId { get; init; }

        public string? ReceiveTlsCertificateSha256 { get; init; }

        public AndroidReceiveEndpointDto? Endpoint { get; init; }
    }

    private sealed record AndroidReceiveEndpointDto
    {
        public string Host { get; init; } = string.Empty;

        public int Port { get; init; }
    }

    private sealed record AndroidReceiveReachabilityResponse
    {
        public string? Status { get; init; }

        public string? DeviceId { get; init; }
    }
}
