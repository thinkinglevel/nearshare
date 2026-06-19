using System.Text;
using System.Text.Json;

namespace PCMobileLink.Windows.Core;

public static class PairingPayloadCodec
{
    private const string PairingUriPrefix = "nearshare://pair?payload=";

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static string Encode(PairingPayload payload)
    {
        ArgumentNullException.ThrowIfNull(payload);

        string json = JsonSerializer.Serialize(payload, JsonOptions);
        string encodedPayload = Base64UrlEncode(Encoding.UTF8.GetBytes(json));

        return PairingUriPrefix + encodedPayload;
    }

    public static PairingPayload Decode(string uri)
    {
        if (!Uri.TryCreate(uri, UriKind.Absolute, out Uri? parsed)
            || !string.Equals(parsed.Scheme, "nearshare", StringComparison.OrdinalIgnoreCase)
            || !string.Equals(parsed.Host, "pair", StringComparison.OrdinalIgnoreCase))
        {
            throw new FormatException("Pairing payload must use the nearshare://pair URI format.");
        }

        string? payloadValue = GetQueryValue(parsed.Query, "payload");
        if (string.IsNullOrWhiteSpace(payloadValue))
        {
            throw new FormatException("Pairing payload URI is missing the payload value.");
        }

        byte[] jsonBytes = Base64UrlDecode(payloadValue);
        PairingPayload? payload = JsonSerializer.Deserialize<PairingPayload>(jsonBytes, JsonOptions);

        return payload ?? throw new FormatException("Pairing payload could not be decoded.");
    }

    private static string? GetQueryValue(string query, string key)
    {
        string normalizedQuery = query.TrimStart('?');

        foreach (string part in normalizedQuery.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            string[] pieces = part.Split('=', 2);
            if (pieces.Length == 2 && string.Equals(Uri.UnescapeDataString(pieces[0]), key, StringComparison.Ordinal))
            {
                return Uri.UnescapeDataString(pieces[1]);
            }
        }

        return null;
    }

    private static string Base64UrlEncode(byte[] bytes)
    {
        return Convert.ToBase64String(bytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    private static byte[] Base64UrlDecode(string value)
    {
        string base64 = value
            .Replace('-', '+')
            .Replace('_', '/');

        int padding = (4 - base64.Length % 4) % 4;
        base64 = base64.PadRight(base64.Length + padding, '=');

        return Convert.FromBase64String(base64);
    }
}
