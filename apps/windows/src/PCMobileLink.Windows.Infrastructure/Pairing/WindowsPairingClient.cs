using System.Globalization;
using System.Net;
using System.Net.Http.Json;
using System.Net.Security;
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

        using HttpClient httpClient = CreatePinnedClient(payload.TlsCertificateSha256);
        using HttpResponseMessage response = await httpClient.PostAsJsonAsync(
                PairingRequestsUrl(payload),
                new PairingRequestPayload
                {
                    OfferId = payload.OfferId,
                    PairingToken = payload.PairingToken,
                    DeviceName = deviceName,
                    DevicePublicKey = devicePublicKey,
                    ReceiveEndpoints = receiveEndpoints,
                    ReceiveTlsCertificateSha256 = receiveTlsCertificateSha256
                },
                SerializerOptions,
                cancellationToken)
            .ConfigureAwait(false);

        await EnsureStatusAsync(response, HttpStatusCode.Accepted, cancellationToken).ConfigureAwait(false);
        return await ReadJsonAsync<PairingRequestReceipt>(response, cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidOperationException("Pairing request did not return a receipt.");
    }

    public async Task<PairingRequestResultResponse> GetPairingResultAsync(
        PairingPayload payload,
        Guid requestId,
        CancellationToken cancellationToken = default)
    {
        ArgumentNullException.ThrowIfNull(payload);

        using HttpClient httpClient = CreatePinnedClient(payload.TlsCertificateSha256);
        using HttpResponseMessage response = await httpClient.GetAsync(
                PairingRequestResultUrl(payload, requestId),
                cancellationToken)
            .ConfigureAwait(false);

        await EnsureStatusAsync(response, HttpStatusCode.OK, cancellationToken).ConfigureAwait(false);
        return await ReadJsonAsync<PairingRequestResultResponse>(response, cancellationToken).ConfigureAwait(false)
            ?? throw new InvalidOperationException("Pairing request did not return a result.");
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

    private static string PairingRequestsUrl(PairingPayload payload)
    {
        PairingEndpointCandidate endpoint = FirstEndpoint(payload);
        return $"https://{FormatHost(endpoint.Host)}:{endpoint.Port.ToString(CultureInfo.InvariantCulture)}/nearshare/pairing/requests";
    }

    private static string PairingRequestResultUrl(PairingPayload payload, Guid requestId)
    {
        PairingEndpointCandidate endpoint = FirstEndpoint(payload);
        return $"https://{FormatHost(endpoint.Host)}:{endpoint.Port.ToString(CultureInfo.InvariantCulture)}/nearshare/pairing/requests/{requestId:D}";
    }

    private static PairingEndpointCandidate FirstEndpoint(PairingPayload payload)
    {
        return payload.Endpoints.FirstOrDefault()
            ?? throw new InvalidOperationException("Pairing payload does not include a reachable endpoint.");
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
