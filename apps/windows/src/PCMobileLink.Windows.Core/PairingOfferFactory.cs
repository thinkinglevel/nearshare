using System.Security.Cryptography;

namespace PCMobileLink.Windows.Core;

public static class PairingOfferFactory
{
    private const int PairingTokenByteLength = 32;
    private static readonly TimeSpan DefaultLifetime = TimeSpan.FromMinutes(5);

    public static PairingOffer Create(
        string pcName,
        IReadOnlyList<PairingEndpointCandidate> endpoints,
        string tlsCertificateSha256,
        DateTimeOffset now)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(pcName);
        ArgumentNullException.ThrowIfNull(endpoints);
        ArgumentException.ThrowIfNullOrWhiteSpace(tlsCertificateSha256);

        if (endpoints.Count == 0)
        {
            throw new ArgumentException("At least one endpoint candidate is required.", nameof(endpoints));
        }

        PairingPayload payload = new()
        {
            Version = 1,
            OfferId = Guid.NewGuid(),
            PcName = pcName,
            Endpoints = endpoints,
            PairingToken = GeneratePairingToken(),
            ShortCode = PairingShortCode.Generate(),
            TlsCertificateSha256 = tlsCertificateSha256,
            ExpiresAtUnixTimeSeconds = now.Add(DefaultLifetime).ToUnixTimeSeconds(),
            Transport = "https"
        };

        return new PairingOffer(payload, PairingPayloadCodec.Encode(payload));
    }

    private static string GeneratePairingToken()
    {
        byte[] tokenBytes = RandomNumberGenerator.GetBytes(PairingTokenByteLength);
        return Base64UrlEncode(tokenBytes);
    }

    private static string Base64UrlEncode(byte[] bytes)
    {
        return Convert.ToBase64String(bytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }
}
