using System.Security.Cryptography.X509Certificates;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed class LocalPairingCertificateStore
{
    private readonly string _certificatePath;

    public LocalPairingCertificateStore(string certificatePath)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(certificatePath);
        _certificatePath = certificatePath;
    }

    public static LocalPairingCertificateStore CreateDefault()
    {
        string appDataPath = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
        string certificatePath = Path.Combine(appDataPath, "NearShare", "pc-pairing-certificate.pfx");
        return new LocalPairingCertificateStore(certificatePath);
    }

    public LocalPairingCertificate LoadOrCreate(string pcName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(pcName);

        if (File.Exists(_certificatePath))
        {
            X509Certificate2 persistedCertificate = new(
                File.ReadAllBytes(_certificatePath),
                (string?)null,
                X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);

            return new LocalPairingCertificate(
                persistedCertificate,
                LocalPairingCertificate.GetSha256Fingerprint(persistedCertificate));
        }

        LocalPairingCertificate certificate = LocalPairingCertificate.Create(pcName);
        string? directory = Path.GetDirectoryName(_certificatePath);
        if (!string.IsNullOrWhiteSpace(directory))
        {
            Directory.CreateDirectory(directory);
        }

        File.WriteAllBytes(_certificatePath, certificate.Certificate.Export(X509ContentType.Pfx));
        return certificate;
    }
}
