using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class PairedDeviceRequestSignatureTests
{
    [Fact]
    public void CreateSignatureInput_IncludesMethodPathTimestampNonceAndBodyHash()
    {
        string input = PairedDeviceRequestSignature.CreateSignatureInput(
            method: "get",
            pathAndQuery: "/nearshare/paired-devices/device-1/reachability?check=1",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "nonce-1",
            body: "hello"u8.ToArray());

        Assert.Equal(
            "GET\n/nearshare/paired-devices/device-1/reachability?check=1\n1700000000\nnonce-1\nLPJNul-wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ",
            input);
    }

    [Fact]
    public void Sign_UsesBase64UrlDecodedSharedSecretAsHmacKey()
    {
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());

        string signature = PairedDeviceRequestSignature.Sign(
            sharedSecret: sharedSecret,
            method: "GET",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "nonce-1",
            body: []);

        Assert.Equal("CyiCbWJOc0bM2CgCztT5xT0RNvpAqWbMC2IAQfGzZx4", signature);
    }

    [Fact]
    public void Verify_ReturnsTrueOnlyForMatchingSignatureWithinClockSkew()
    {
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        string signature = PairedDeviceRequestSignature.Sign(
            sharedSecret: sharedSecret,
            method: "GET",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "nonce-1",
            body: []);

        Assert.True(PairedDeviceRequestSignature.Verify(
            sharedSecret: sharedSecret,
            method: "GET",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "nonce-1",
            body: [],
            signature: signature,
            now: DateTimeOffset.FromUnixTimeSeconds(1_700_000_120),
            allowedClockSkew: TimeSpan.FromMinutes(5)));

        Assert.False(PairedDeviceRequestSignature.Verify(
            sharedSecret: sharedSecret,
            method: "GET",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "nonce-1",
            body: [],
            signature: "wrong-signature",
            now: DateTimeOffset.FromUnixTimeSeconds(1_700_000_120),
            allowedClockSkew: TimeSpan.FromMinutes(5)));

        Assert.False(PairedDeviceRequestSignature.Verify(
            sharedSecret: sharedSecret,
            method: "GET",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/reachability",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "nonce-1",
            body: [],
            signature: signature,
            now: DateTimeOffset.FromUnixTimeSeconds(1_700_001_000),
            allowedClockSkew: TimeSpan.FromMinutes(5)));
    }

    [Fact]
    public void SignBodyHash_MatchesSigningTheOriginalBody()
    {
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        byte[] body = "hello from android"u8.ToArray();
        string bodyHash = PairedDeviceRequestSignature.CreateBodyHash(body);

        string fromBody = PairedDeviceRequestSignature.Sign(
            sharedSecret: sharedSecret,
            method: "POST",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "upload-nonce-1",
            body: body);
        string fromBodyHash = PairedDeviceRequestSignature.SignBodyHash(
            sharedSecret: sharedSecret,
            method: "POST",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "upload-nonce-1",
            bodySha256Base64Url: bodyHash);

        Assert.Equal("XQnr5TdqYiCssDf5TLUWmk1BFZ4vh47UjxSMe2NYtcs", bodyHash);
        Assert.Equal(fromBody, fromBodyHash);
        Assert.Equal("FZs6Pi29GHau1RQ2MVNnN2hgQMZfD146DM2HBxoVlBQ", fromBodyHash);
    }

    [Fact]
    public void VerifyBodyHash_ReturnsTrueForMatchingSignatureWithinClockSkew()
    {
        string sharedSecret = PairedDeviceRequestSignature.EncodeBase64Url("shared-secret-key-32-bytes-here!!"u8.ToArray());
        string bodyHash = PairedDeviceRequestSignature.CreateBodyHash("hello from android"u8.ToArray());
        string signature = PairedDeviceRequestSignature.SignBodyHash(
            sharedSecret: sharedSecret,
            method: "POST",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "upload-nonce-1",
            bodySha256Base64Url: bodyHash);

        Assert.True(PairedDeviceRequestSignature.VerifyBodyHash(
            sharedSecret: sharedSecret,
            method: "POST",
            pathAndQuery: "/nearshare/paired-devices/8d4ad7be-21ad-4bb5-b575-2f2d418c3c8b/transfers/files",
            timestampUnixTimeSeconds: 1_700_000_000,
            nonce: "upload-nonce-1",
            bodySha256Base64Url: bodyHash,
            signature: signature,
            now: DateTimeOffset.FromUnixTimeSeconds(1_700_000_120),
            allowedClockSkew: TimeSpan.FromMinutes(5)));
    }
}
