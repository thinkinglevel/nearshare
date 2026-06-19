using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed record LocalPairingCertificate(X509Certificate2 Certificate, string Sha256Fingerprint) : IDisposable
{
    public static LocalPairingCertificate Create(string pcName)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(pcName);

        using RSA key = RSA.Create(2048);
        CertificateRequest request = new(
            $"CN=NearShare Pairing - {pcName}",
            key,
            HashAlgorithmName.SHA256,
            RSASignaturePadding.Pkcs1);

        request.CertificateExtensions.Add(new X509BasicConstraintsExtension(false, false, 0, false));
        request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, true));
        request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(
            [new Oid("1.3.6.1.5.5.7.3.1")],
            true));

        SubjectAlternativeNameBuilder subjectAlternativeNames = new();
        subjectAlternativeNames.AddDnsName("localhost");
        subjectAlternativeNames.AddIpAddress(System.Net.IPAddress.Loopback);
        subjectAlternativeNames.AddIpAddress(System.Net.IPAddress.IPv6Loopback);
        request.CertificateExtensions.Add(subjectAlternativeNames.Build());

        using X509Certificate2 temporaryCertificate = request.CreateSelfSigned(
            DateTimeOffset.UtcNow.AddMinutes(-5),
            DateTimeOffset.UtcNow.AddDays(30));

        X509Certificate2 certificate = new(
            temporaryCertificate.Export(X509ContentType.Pfx),
            (string?)null,
            X509KeyStorageFlags.UserKeySet | X509KeyStorageFlags.Exportable);

        return new LocalPairingCertificate(certificate, GetSha256Fingerprint(certificate));
    }

    public static string GetSha256Fingerprint(System.Security.Cryptography.X509Certificates.X509Certificate certificate)
    {
        ArgumentNullException.ThrowIfNull(certificate);

        byte[] rawCertificate = certificate.Export(X509ContentType.Cert);
        byte[] hash = SHA256.HashData(rawCertificate);
        return Convert.ToHexString(hash);
    }

    public void Dispose()
    {
        Certificate.Dispose();
    }
}
