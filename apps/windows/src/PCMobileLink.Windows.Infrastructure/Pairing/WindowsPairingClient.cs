using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Text;
using System.Text.Json;
using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed class WindowsPairingClient
{
    private static readonly JsonSerializerOptions SerializerOptions = new(JsonSerializerDefaults.Web);
    private readonly TimeSpan _timeout;

    public WindowsPairingClient(TimeSpan? timeout = null)
    {
        _timeout = timeout ?? TimeSpan.FromSeconds(10);
    }

    public async Task<PairingRequestReceipt> SubmitPairingRequestAsync(
        PairingPayload payload,
        string deviceName,
        string devicePublicKey,
        IReadOnlyList<PairingEndpointCandidate> receiveEndpoints,
        string? receiveTlsCertificateSha256,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(payload);
        ArgumentException.ThrowIfNullOrWhiteSpace(deviceName);
        ArgumentException.ThrowIfNullOrWhiteSpace(devicePublicKey);

        PairingRequestPayload requestPayload = new()
        {
            OfferId = payload.OfferId,
            PairingToken = payload.PairingToken,
            DeviceName = deviceName,
            DevicePublicKey = devicePublicKey,
            ReceiveEndpoints = receiveEndpoints,
            ReceiveTlsCertificateSha256 = receiveTlsCertificateSha256
        };

        Exception? lastEndpointException = null;
        foreach (PairingEndpointCandidate endpoint in Endpoints(payload))
        {
            using HttpClient httpClient = CreatePinnedClient(payload.TlsCertificateSha256);
            string requestJson = JsonSerializer.Serialize(requestPayload, SerializerOptions);
            using StringContent requestContent = new(requestJson, Encoding.UTF8, "application/json");
            try
            {
                using HttpResponseMessage response = await httpClient.PostAsync(
                        PairingRequestsUrl(endpoint),
                        requestContent,
                        cancellationToken)
                    .ConfigureAwait(false);

                await EnsureStatusAsync(response, HttpStatusCode.Accepted, cancellationToken).ConfigureAwait(false);
                return await ReadJsonAsync<PairingRequestReceipt>(response, cancellationToken).ConfigureAwait(false)
                    ?? throw new InvalidOperationException("Pairing request did not return a receipt.");
            }
            catch (Exception exception) when (IsEndpointReachabilityFailure(exception, cancellationToken))
            {
                lastEndpointException = exception;
            }
        }

        throw new InvalidOperationException("Could not reach any pairing endpoint from the pairing code.", lastEndpointException);
    }

    public async Task<PairingRequestResultResponse> GetPairingResultAsync(
        PairingPayload payload,
        Guid requestId,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(payload);

        Exception? lastEndpointException = null;
        foreach (PairingEndpointCandidate endpoint in Endpoints(payload))
        {
            using HttpClient httpClient = CreatePinnedClient(payload.TlsCertificateSha256);
            try
            {
                using HttpResponseMessage response = await httpClient.GetAsync(
                        PairingRequestResultUrl(endpoint, requestId),
                        cancellationToken)
                    .ConfigureAwait(false);

                await EnsureStatusAsync(response, HttpStatusCode.OK, cancellationToken).ConfigureAwait(false);
                return await ReadJsonAsync<PairingRequestResultResponse>(response, cancellationToken).ConfigureAwait(false)
                    ?? throw new InvalidOperationException("Pairing request did not return a result.");
            }
            catch (Exception exception) when (IsEndpointReachabilityFailure(exception, cancellationToken))
            {
                lastEndpointException = exception;
            }
        }

        throw new InvalidOperationException("Could not reach any pairing endpoint while waiting for approval.", lastEndpointException);
    }

    private HttpClient CreatePinnedClient(string expectedTlsCertificateSha256)
    {
        SocketsHttpHandler handler = new()
        {
            SslOptions =
            {
                RemoteCertificateValidationCallback = (_, certificate, _, _) =>
                    certificate is not null
                    && string.Equals(
                        LocalPairingCertificate.GetSha256Fingerprint(certificate),
                        expectedTlsCertificateSha256,
                        StringComparison.OrdinalIgnoreCase)
            }
        };
        return new HttpClient(handler)
        {
            Timeout = _timeout
        };
    }

    private static async Task<T?> ReadJsonAsync<T>(
        HttpResponseMessage response,
        CancellationToken cancellationToken)
    {
        await using Stream responseStream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        return await JsonSerializer.DeserializeAsync<T>(
                responseStream,
                SerializerOptions,
                cancellationToken)
            .ConfigureAwait(false);
    }

    private static async Task EnsureStatusAsync(
        HttpResponseMessage response,
        HttpStatusCode expectedStatus,
        CancellationToken cancellationToken)
    {
        if (response.StatusCode == expectedStatus)
        {
            return;
        }

        string body = await response.Content.ReadAsStringAsync(cancellationToken).ConfigureAwait(false);
        throw new InvalidOperationException($"Pairing request failed with HTTP {(int)response.StatusCode}: {body}");
    }

    private static string PairingRequestsUrl(PairingEndpointCandidate endpoint)
    {
        return $"https://{FormatHost(endpoint.Host)}:{endpoint.Port.ToString(CultureInfo.InvariantCulture)}/nearshare/pairing/requests";
    }

    private static string PairingRequestResultUrl(PairingEndpointCandidate endpoint, Guid requestId)
    {
        return $"https://{FormatHost(endpoint.Host)}:{endpoint.Port.ToString(CultureInfo.InvariantCulture)}/nearshare/pairing/requests/{requestId:D}";
    }

    private static IReadOnlyList<PairingEndpointCandidate> Endpoints(PairingPayload payload)
    {
        return payload.Endpoints.Count > 0
            ? payload.Endpoints
            : throw new InvalidOperationException("Pairing payload does not include a reachable endpoint.");
    }

    private static string FormatHost(string host)
    {
        string trimmed = host.Trim();
        ArgumentException.ThrowIfNullOrWhiteSpace(trimmed);
        return trimmed.Contains(':') && !trimmed.StartsWith('[')
            ? $"[{trimmed}]"
            : trimmed;
    }

    private static bool IsEndpointReachabilityFailure(Exception exception, CancellationToken cancellationToken)
    {
        return !cancellationToken.IsCancellationRequested
            && exception is HttpRequestException or TaskCanceledException;
    }
}
