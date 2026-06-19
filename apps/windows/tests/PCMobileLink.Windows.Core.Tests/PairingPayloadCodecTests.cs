using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class PairingPayloadCodecTests
{
    [Fact]
    public void EncodeDecode_RoundTripsMvpQrPayloadFields()
    {
        PairingPayload payload = new()
        {
            Version = 1,
            OfferId = Guid.Parse("9ef3d86d-dc3c-4f2a-a6ea-b5f3f55cf8d1"),
            PcName = "AKSH-PC",
            Endpoints = [new PairingEndpointCandidate("192.168.1.10", 17654)],
            PairingToken = "x6D0yZaXddo4aV3N7KNXkA",
            TlsCertificateSha256 = "B25D7C4E4C0E2C7B7780867E7F8B0E95B022A867ED98C341E1D891FE0CC9D4AA",
            ExpiresAtUnixTimeSeconds = 1_781_006_400,
            Transport = "https"
        };

        string encoded = PairingPayloadCodec.Encode(payload);
        PairingPayload decoded = PairingPayloadCodec.Decode(encoded);

        Assert.StartsWith("nearshare://pair?payload=", encoded);
        Assert.Equal(payload.Version, decoded.Version);
        Assert.Equal(payload.OfferId, decoded.OfferId);
        Assert.Equal(payload.PcName, decoded.PcName);
        Assert.Single(decoded.Endpoints);
        Assert.Equal("192.168.1.10", decoded.Endpoints[0].Host);
        Assert.Equal(17654, decoded.Endpoints[0].Port);
        Assert.Equal(payload.PairingToken, decoded.PairingToken);
        Assert.Equal(payload.TlsCertificateSha256, decoded.TlsCertificateSha256);
        Assert.Equal(payload.ExpiresAtUnixTimeSeconds, decoded.ExpiresAtUnixTimeSeconds);
        Assert.Equal(payload.Transport, decoded.Transport);
    }

    [Fact]
    public void Decode_RejectsWrongScheme()
    {
        FormatException exception = Assert.Throws<FormatException>(() =>
            PairingPayloadCodec.Decode("https://example.test/pair?payload=abc"));

        Assert.Equal("Pairing payload must use the nearshare://pair URI format.", exception.Message);
    }

    [Fact]
    public void Decode_RejectsMissingPayloadQueryValue()
    {
        FormatException exception = Assert.Throws<FormatException>(() =>
            PairingPayloadCodec.Decode("nearshare://pair"));

        Assert.Equal("Pairing payload URI is missing the payload value.", exception.Message);
    }
}
