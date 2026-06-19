using System.Net;

namespace PCMobileLink.Windows.Infrastructure.Pairing;

public sealed record LocalPairingServerOptions
{
    public required string PcName { get; init; }

    public IReadOnlyList<string> QrEndpointHosts { get; init; } = [];

    public IPAddress ListenAddress { get; init; } = IPAddress.Any;

    public int ListenPort { get; init; } = 0;

    public string? CertificatePath { get; init; }

    public string? PairedDevicesPath { get; init; }

    public string? ReceiveFolderPath { get; init; }

    public string? TransferTempFolderPath { get; init; }

    public Action<ReceiveTransferProgressUpdate>? TransferProgressChanged { get; init; }
}
