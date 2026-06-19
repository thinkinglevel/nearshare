using PCMobileLink.Windows.Infrastructure.Pairing;

namespace PCMobileLink.Windows.Infrastructure.Tests;

public sealed class LocalPairingCertificateStoreTests : IDisposable
{
    private readonly string _tempDirectory = Path.Combine(Path.GetTempPath(), "NearShare-CertificateStoreTests-" + Guid.NewGuid());

    [Fact]
    public void LoadOrCreate_ReusesPersistedCertificateFingerprint()
    {
        string certificatePath = Path.Combine(_tempDirectory, "pc-pairing-certificate.pfx");
        LocalPairingCertificateStore store = new(certificatePath);

        using LocalPairingCertificate first = store.LoadOrCreate("NearShare Test PC");
        string firstFingerprint = first.Sha256Fingerprint;

        using LocalPairingCertificate second = store.LoadOrCreate("NearShare Test PC");

        Assert.Equal(firstFingerprint, second.Sha256Fingerprint);
        Assert.True(File.Exists(certificatePath));
    }

    public void Dispose()
    {
        if (Directory.Exists(_tempDirectory))
        {
            Directory.Delete(_tempDirectory, recursive: true);
        }
    }
}
