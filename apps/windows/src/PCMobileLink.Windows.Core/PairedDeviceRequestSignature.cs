using System.Security.Cryptography;
using System.Text;

namespace PCMobileLink.Windows.Core;

public static class PairedDeviceRequestSignature
{
    public static string CreateSignatureInput(
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        byte[] body)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(method);
        ArgumentException.ThrowIfNullOrWhiteSpace(pathAndQuery);
        ArgumentException.ThrowIfNullOrWhiteSpace(nonce);
        ArgumentNullException.ThrowIfNull(body);

        string bodyHash = CreateBodyHash(body);
        return CreateSignatureInputFromBodyHash(
            method,
            pathAndQuery,
            timestampUnixTimeSeconds,
            nonce,
            bodyHash);
    }

    public static string Sign(
        string sharedSecret,
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        byte[] body)
    {
        string bodyHash = CreateBodyHash(body);
        return SignBodyHash(sharedSecret, method, pathAndQuery, timestampUnixTimeSeconds, nonce, bodyHash);
    }

    public static string CreateBodyHash(byte[] body)
    {
        ArgumentNullException.ThrowIfNull(body);
        return EncodeBase64Url(SHA256.HashData(body));
    }

    public static string CreateSignatureInputFromBodyHash(
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        string bodySha256Base64Url)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(method);
        ArgumentException.ThrowIfNullOrWhiteSpace(pathAndQuery);
        ArgumentException.ThrowIfNullOrWhiteSpace(nonce);
        ArgumentException.ThrowIfNullOrWhiteSpace(bodySha256Base64Url);
        return string.Join(
            "\n",
            method.ToUpperInvariant(),
            pathAndQuery,
            timestampUnixTimeSeconds.ToString(System.Globalization.CultureInfo.InvariantCulture),
            nonce,
            bodySha256Base64Url);
    }

    public static string SignBodyHash(
        string sharedSecret,
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        string bodySha256Base64Url)
    {
        byte[] key = DecodeBase64Url(sharedSecret);
        string signatureInput = CreateSignatureInputFromBodyHash(method, pathAndQuery, timestampUnixTimeSeconds, nonce, bodySha256Base64Url);
        byte[] signature = HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(signatureInput));
        return EncodeBase64Url(signature);
    }

    public static bool Verify(
        string sharedSecret,
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        byte[] body,
        string signature,
        DateTimeOffset now,
        TimeSpan allowedClockSkew)
    {
        string bodyHash = CreateBodyHash(body);
        return VerifyBodyHash(
            sharedSecret,
            method,
            pathAndQuery,
            timestampUnixTimeSeconds,
            nonce,
            bodyHash,
            signature,
            now,
            allowedClockSkew);
    }

    public static bool VerifyBodyHash(
        string sharedSecret,
        string method,
        string pathAndQuery,
        long timestampUnixTimeSeconds,
        string nonce,
        string bodySha256Base64Url,
        string signature,
        DateTimeOffset now,
        TimeSpan allowedClockSkew)
    {
        if (string.IsNullOrWhiteSpace(signature) || allowedClockSkew < TimeSpan.Zero)
        {
            return false;
        }

        DateTimeOffset signedAt;
        try
        {
            signedAt = DateTimeOffset.FromUnixTimeSeconds(timestampUnixTimeSeconds);
        }
        catch (ArgumentOutOfRangeException)
        {
            return false;
        }

        if (signedAt < now.Subtract(allowedClockSkew) || signedAt > now.Add(allowedClockSkew))
        {
            return false;
        }

        try
        {
            string expectedSignature = SignBodyHash(sharedSecret, method, pathAndQuery, timestampUnixTimeSeconds, nonce, bodySha256Base64Url);
            byte[] expectedBytes = Encoding.ASCII.GetBytes(expectedSignature);
            byte[] actualBytes = Encoding.ASCII.GetBytes(signature);
            return expectedBytes.Length == actualBytes.Length
                && CryptographicOperations.FixedTimeEquals(expectedBytes, actualBytes);
        }
        catch (FormatException)
        {
            return false;
        }
        catch (ArgumentException)
        {
            return false;
        }
    }

    public static string EncodeBase64Url(byte[] bytes)
    {
        ArgumentNullException.ThrowIfNull(bytes);
        return Convert.ToBase64String(bytes)
            .TrimEnd('=')
            .Replace('+', '-')
            .Replace('/', '_');
    }

    private static byte[] DecodeBase64Url(string value)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(value);
        string base64 = value.Replace('-', '+').Replace('_', '/');
        int padding = (4 - base64.Length % 4) % 4;
        base64 = base64.PadRight(base64.Length + padding, '=');
        return Convert.FromBase64String(base64);
    }
}
