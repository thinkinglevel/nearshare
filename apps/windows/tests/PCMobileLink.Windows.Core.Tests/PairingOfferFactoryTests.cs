using PCMobileLink.Windows.Core;

namespace PCMobileLink.Windows.Core.Tests;

public sealed class PairingOfferFactoryTests
{
    [Fact]
    public void Create_GeneratesShortLivedHttpsQrPayload()
    {
        DateTimeOffset now = DateTimeOffset.FromUnixTimeSeconds(1_781_000_000);
        PairingOffer offer = PairingOfferFactory.Create(
            pcName: "AKSH-PC",
            endpoints: [new PairingEndpointCandidate("192.168.1.10", 17654)],
            tlsCertificateSha256: "B25D7C4E4C0E2C7B7780867E7F8B0E95B022A867ED98C341E1D891FE0CC9D4AA",
            now: now);

        Assert.Equal("AKSH-PC", offer.Payload.PcName);
        Assert.Equal("https", offer.Payload.Transport);
        Assert.Equal(1_781_000_000 + 5 * 60, offer.Payload.ExpiresAtUnixTimeSeconds);
        Assert.NotEqual(Guid.Empty, offer.Payload.OfferId);
        Assert.False(string.IsNullOrWhiteSpace(offer.Payload.PairingToken));
        Assert.StartsWith("nearshare://pair?payload=", offer.QrUri);
    }

    [Fact]
    public void Create_RejectsMissingEndpointCandidates()
    {
        ArgumentException exception = Assert.Throws<ArgumentException>(() =>
            PairingOfferFactory.Create(
                pcName: "AKSH-PC",
                endpoints: [],
                tlsCertificateSha256: "B25D7C4E4C0E2C7B7780867E7F8B0E95B022A867ED98C341E1D891FE0CC9D4AA",
                now: DateTimeOffset.UnixEpoch));

        Assert.Equal("At least one endpoint candidate is required. (Parameter 'endpoints')", exception.Message);
    }
}
