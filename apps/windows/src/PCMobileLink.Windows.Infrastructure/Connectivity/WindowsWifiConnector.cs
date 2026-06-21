using System.ComponentModel;
using System.Net;
using System.Net.NetworkInformation;
using System.Runtime.InteropServices;
using System.Security;

namespace PCMobileLink.Windows.Infrastructure.Connectivity;

public sealed class WindowsWifiConnector
{
    private static readonly TimeSpan ConnectionTimeout = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan ConnectionPollInterval = TimeSpan.FromMilliseconds(500);
    private static readonly TimeSpan NetworkScanRefreshTimeout = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan NetworkScanPollInterval = TimeSpan.FromMilliseconds(750);

    public IReadOnlyList<VisibleWifiNetwork> ListVisibleNetworks()
    {
        int result = WlanOpenHandle(2, IntPtr.Zero, out _, out IntPtr clientHandle);
        ThrowIfFailed(result, "Could not open Windows Wi-Fi control.");

        try
        {
            Dictionary<string, VisibleWifiNetwork> networks = new(StringComparer.Ordinal);
            foreach (WlanInterfaceInfo wlanInterface in EnumerateInterfaces(clientHandle))
            {
                TryRefreshNetworkList(clientHandle, wlanInterface.InterfaceGuid);
                DateTimeOffset deadline = DateTimeOffset.UtcNow.Add(NetworkScanRefreshTimeout);
                while (true)
                {
                    foreach (VisibleWifiNetwork network in EnumerateVisibleNetworks(clientHandle, wlanInterface.InterfaceGuid))
                    {
                        AddOrUpdateVisibleNetwork(networks, network);
                    }

                    if (DateTimeOffset.UtcNow >= deadline)
                    {
                        break;
                    }

                    Thread.Sleep(NetworkScanPollInterval);
                }
            }

            return networks.Values
                .OrderByDescending(network => network.SignalQuality)
                .ThenBy(network => network.Ssid, StringComparer.OrdinalIgnoreCase)
                .ToArray();
        }
        finally
        {
            WlanCloseHandle(clientHandle, IntPtr.Zero);
        }
    }

    private static void AddOrUpdateVisibleNetwork(
        Dictionary<string, VisibleWifiNetwork> networks,
        VisibleWifiNetwork network)
    {
        if (!networks.TryGetValue(network.Ssid, out VisibleWifiNetwork? existing)
            || network.SignalQuality > existing.SignalQuality)
        {
            networks[network.Ssid] = network;
        }
    }

    public void Connect(string connectionName, string password)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(connectionName);
        string normalizedName = connectionName.Trim();
        string normalizedPassword = password.Trim();
        ValidatePassword(normalizedPassword);

        int result = WlanOpenHandle(2, IntPtr.Zero, out _, out IntPtr clientHandle);
        ThrowIfFailed(result, "Could not open Windows Wi-Fi control.");

        try
        {
            IReadOnlyList<WlanInterfaceInfo> interfaces = EnumerateInterfaces(clientHandle);
            if (interfaces.Count == 0)
            {
                throw new InvalidOperationException("No Wi-Fi adapter is available.");
            }

            Exception? lastException = null;
            foreach (WlanInterfaceInfo wlanInterface in interfaces)
            {
                try
                {
                    ConnectInterface(clientHandle, wlanInterface.InterfaceGuid, normalizedName, normalizedPassword);
                    return;
                }
                catch (Exception exception) when (exception is Win32Exception or InvalidOperationException)
                {
                    lastException = exception;
                }
            }

            throw new InvalidOperationException("Windows could not connect to the private connection.", lastException);
        }
        finally
        {
            WlanCloseHandle(clientHandle, IntPtr.Zero);
        }
    }

    private static void ConnectInterface(IntPtr clientHandle, Guid interfaceGuid, string connectionName, string password)
    {
        string profileXml = CreateProfileXml(connectionName, password);
        uint reasonCode;
        int result = WlanSetProfile(
            clientHandle,
            ref interfaceGuid,
            0,
            profileXml,
            null,
            true,
            IntPtr.Zero,
            out reasonCode);
        if (result != 0)
        {
            throw new InvalidOperationException($"Windows could not save the private connection profile. Reason: {reasonCode}", new Win32Exception(result));
        }

        WlanConnectionParameters parameters = new()
        {
            ConnectionMode = WlanConnectionMode.Profile,
            Profile = connectionName,
            Dot11BssType = Dot11BssType.Infrastructure,
            Flags = 0
        };
        result = WlanConnect(clientHandle, ref interfaceGuid, ref parameters, IntPtr.Zero);
        ThrowIfFailed(result, "Windows could not connect to the private connection.");
        WaitForConnectedProfile(clientHandle, interfaceGuid, connectionName);
    }

    private static IReadOnlyList<WlanInterfaceInfo> EnumerateInterfaces(IntPtr clientHandle)
    {
        int result = WlanEnumInterfaces(clientHandle, IntPtr.Zero, out IntPtr interfaceListPointer);
        ThrowIfFailed(result, "Could not list Windows Wi-Fi adapters.");

        try
        {
            int interfaceCount = Marshal.ReadInt32(interfaceListPointer);
            IntPtr currentItem = IntPtr.Add(interfaceListPointer, 8);
            int itemSize = Marshal.SizeOf<WlanInterfaceInfo>();
            List<WlanInterfaceInfo> interfaces = [];
            for (int index = 0; index < interfaceCount; index++)
            {
                WlanInterfaceInfo info = Marshal.PtrToStructure<WlanInterfaceInfo>(currentItem);
                interfaces.Add(info);
                currentItem = IntPtr.Add(currentItem, itemSize);
            }

            return interfaces;
        }
        finally
        {
            WlanFreeMemory(interfaceListPointer);
        }
    }

    private static string CreateProfileXml(string connectionName, string password)
    {
        string escapedName = SecurityElement.Escape(connectionName) ?? connectionName;
        if (string.IsNullOrWhiteSpace(password))
        {
            return $"""
                <?xml version="1.0"?>
                <WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
                    <name>{escapedName}</name>
                    <SSIDConfig>
                        <SSID>
                            <name>{escapedName}</name>
                        </SSID>
                    </SSIDConfig>
                    <connectionType>ESS</connectionType>
                    <connectionMode>manual</connectionMode>
                    <MSM>
                        <security>
                            <authEncryption>
                                <authentication>open</authentication>
                                <encryption>none</encryption>
                                <useOneX>false</useOneX>
                            </authEncryption>
                        </security>
                    </MSM>
                </WLANProfile>
                """;
        }

        string escapedPassword = SecurityElement.Escape(password) ?? password;
        return $"""
            <?xml version="1.0"?>
            <WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
                <name>{escapedName}</name>
                <SSIDConfig>
                    <SSID>
                        <name>{escapedName}</name>
                    </SSID>
                </SSIDConfig>
                <connectionType>ESS</connectionType>
                <connectionMode>manual</connectionMode>
                <MSM>
                    <security>
                        <authEncryption>
                            <authentication>WPA2PSK</authentication>
                            <encryption>AES</encryption>
                            <useOneX>false</useOneX>
                        </authEncryption>
                        <sharedKey>
                            <keyType>passPhrase</keyType>
                            <protected>false</protected>
                            <keyMaterial>{escapedPassword}</keyMaterial>
                        </sharedKey>
                    </security>
                </MSM>
            </WLANProfile>
            """;
    }

    private static void ValidatePassword(string password)
    {
        if (password.Length is > 0 and < 8)
        {
            throw new ArgumentException("Private connection password must be at least 8 characters when one is required.", nameof(password));
        }
    }

    private static void WaitForConnectedProfile(IntPtr clientHandle, Guid interfaceGuid, string connectionName)
    {
        DateTimeOffset deadline = DateTimeOffset.UtcNow.Add(ConnectionTimeout);
        bool connectedToRequestedProfile = false;
        Exception? lastException = null;

        while (DateTimeOffset.UtcNow < deadline)
        {
            try
            {
                WlanConnectionStatus? status = QueryCurrentConnection(clientHandle, interfaceGuid);
                if (status is not null
                    && status.Value.State == WlanInterfaceState.Connected
                    && string.Equals(status.Value.ProfileName, connectionName, StringComparison.Ordinal))
                {
                    connectedToRequestedProfile = true;
                    if (HasUsableIpv4Address(interfaceGuid))
                    {
                        return;
                    }
                }
            }
            catch (Win32Exception exception)
            {
                lastException = exception;
            }

            Thread.Sleep(ConnectionPollInterval);
        }

        if (connectedToRequestedProfile)
        {
            return;
        }

        throw new InvalidOperationException(
            "Windows started the private connection request, but the Wi-Fi adapter did not finish connecting.",
            lastException);
    }

    private static WlanConnectionStatus? QueryCurrentConnection(IntPtr clientHandle, Guid interfaceGuid)
    {
        int result = WlanQueryInterface(
            clientHandle,
            ref interfaceGuid,
            WlanIntfOpcode.CurrentConnection,
            IntPtr.Zero,
            out _,
            out IntPtr dataPointer,
            out _);
        if (result != 0)
        {
            return null;
        }

        try
        {
            WlanInterfaceState state = (WlanInterfaceState)Marshal.ReadInt32(dataPointer);
            string profileName = Marshal.PtrToStringUni(IntPtr.Add(dataPointer, 8), 256)?.TrimEnd('\0') ?? string.Empty;
            return new WlanConnectionStatus(state, profileName);
        }
        finally
        {
            WlanFreeMemory(dataPointer);
        }
    }

    private static bool HasUsableIpv4Address(Guid interfaceGuid)
    {
        string withoutBraces = interfaceGuid.ToString("D");
        string withBraces = interfaceGuid.ToString("B");

        try
        {
            return NetworkInterface.GetAllNetworkInterfaces().Any(networkInterface =>
                (string.Equals(networkInterface.Id, withoutBraces, StringComparison.OrdinalIgnoreCase)
                    || string.Equals(networkInterface.Id, withBraces, StringComparison.OrdinalIgnoreCase))
                && networkInterface.OperationalStatus == OperationalStatus.Up
                && networkInterface.GetIPProperties().UnicastAddresses.Any(address =>
                    address.Address.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork
                    && !IPAddress.IsLoopback(address.Address)
                    && !address.Address.ToString().StartsWith("169.254.", StringComparison.Ordinal)));
        }
        catch (NetworkInformationException)
        {
            return false;
        }
    }

    private static void TryRefreshNetworkList(IntPtr clientHandle, Guid interfaceGuid)
    {
        _ = WlanScan(clientHandle, ref interfaceGuid, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
    }

    private static IReadOnlyList<VisibleWifiNetwork> EnumerateVisibleNetworks(IntPtr clientHandle, Guid interfaceGuid)
    {
        int result = WlanGetAvailableNetworkList(
            clientHandle,
            ref interfaceGuid,
            0,
            IntPtr.Zero,
            out IntPtr networkListPointer);
        if (result != 0)
        {
            return [];
        }

        try
        {
            int networkCount = Marshal.ReadInt32(networkListPointer);
            IntPtr currentItem = IntPtr.Add(networkListPointer, 8);
            int itemSize = Marshal.SizeOf<WlanAvailableNetwork>();
            List<VisibleWifiNetwork> networks = [];

            for (int index = 0; index < networkCount; index++)
            {
                WlanAvailableNetwork network = Marshal.PtrToStructure<WlanAvailableNetwork>(currentItem);
                string ssid = network.Dot11Ssid.ToDisplayString();
                if (!string.IsNullOrWhiteSpace(ssid) && network.NetworkConnectable)
                {
                    networks.Add(new VisibleWifiNetwork(
                        ssid.Trim(),
                        Math.Clamp((int)network.SignalQuality, 0, 100),
                        network.SecurityEnabled));
                }

                currentItem = IntPtr.Add(currentItem, itemSize);
            }

            return networks;
        }
        finally
        {
            WlanFreeMemory(networkListPointer);
        }
    }

    private static void ThrowIfFailed(int result, string message)
    {
        if (result != 0)
        {
            throw new Win32Exception(result, message);
        }
    }

    [DllImport("wlanapi.dll")]
    private static extern int WlanOpenHandle(
        uint clientVersion,
        IntPtr reserved,
        out uint negotiatedVersion,
        out IntPtr clientHandle);

    [DllImport("wlanapi.dll")]
    private static extern int WlanCloseHandle(
        IntPtr clientHandle,
        IntPtr reserved);

    [DllImport("wlanapi.dll")]
    private static extern int WlanEnumInterfaces(
        IntPtr clientHandle,
        IntPtr reserved,
        out IntPtr interfaceList);

    [DllImport("wlanapi.dll")]
    private static extern void WlanFreeMemory(IntPtr memory);

    [DllImport("wlanapi.dll")]
    private static extern int WlanScan(
        IntPtr clientHandle,
        ref Guid interfaceGuid,
        IntPtr dot11Ssid,
        IntPtr ieData,
        IntPtr reserved);

    [DllImport("wlanapi.dll")]
    private static extern int WlanGetAvailableNetworkList(
        IntPtr clientHandle,
        ref Guid interfaceGuid,
        uint flags,
        IntPtr reserved,
        out IntPtr availableNetworkList);

    [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)]
    private static extern int WlanSetProfile(
        IntPtr clientHandle,
        ref Guid interfaceGuid,
        uint flags,
        [MarshalAs(UnmanagedType.LPWStr)] string profileXml,
        [MarshalAs(UnmanagedType.LPWStr)] string? allUserProfileSecurity,
        [MarshalAs(UnmanagedType.Bool)] bool overwrite,
        IntPtr reserved,
        out uint reasonCode);

    [DllImport("wlanapi.dll", CharSet = CharSet.Unicode)]
    private static extern int WlanConnect(
        IntPtr clientHandle,
        ref Guid interfaceGuid,
        ref WlanConnectionParameters connectionParameters,
        IntPtr reserved);

    [DllImport("wlanapi.dll")]
    private static extern int WlanQueryInterface(
        IntPtr clientHandle,
        ref Guid interfaceGuid,
        WlanIntfOpcode opCode,
        IntPtr reserved,
        out int dataSize,
        out IntPtr data,
        out WlanOpcodeValueType opcodeValueType);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WlanInterfaceInfo
    {
        public Guid InterfaceGuid;

        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string? InterfaceDescription;

        public WlanInterfaceState State;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WlanConnectionParameters
    {
        public WlanConnectionMode ConnectionMode;

        [MarshalAs(UnmanagedType.LPWStr)]
        public string Profile;

        public IntPtr Dot11Ssid;

        public IntPtr DesiredBssidList;

        public Dot11BssType Dot11BssType;

        public uint Flags;
    }

    private enum WlanConnectionMode
    {
        Profile = 0
    }

    private enum Dot11BssType
    {
        Infrastructure = 1
    }

    private enum WlanIntfOpcode
    {
        CurrentConnection = 7
    }

    private enum WlanOpcodeValueType
    {
        QueryOnly = 0,
        SetByGroupPolicy = 1,
        SetByUser = 2,
        Invalid = 3
    }

    private enum WlanInterfaceState
    {
        NotReady = 0,
        Connected = 1,
        AdHocNetworkFormed = 2,
        Disconnecting = 3,
        Disconnected = 4,
        Associating = 5,
        Discovering = 6,
        Authenticating = 7
    }

    private readonly record struct WlanConnectionStatus(WlanInterfaceState State, string ProfileName);

    [StructLayout(LayoutKind.Sequential)]
    private struct Dot11Ssid
    {
        public uint Length;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 32)]
        public byte[] Bytes;

        public readonly string ToDisplayString()
        {
            if (Bytes is null || Length == 0)
            {
                return string.Empty;
            }

            int length = (int)Math.Min(Length, (uint)Bytes.Length);
            return System.Text.Encoding.UTF8.GetString(Bytes, 0, length);
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WlanAvailableNetwork
    {
        [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 256)]
        public string? ProfileName;

        public Dot11Ssid Dot11Ssid;

        public Dot11BssType BssType;

        public uint NumberOfBssids;

        [MarshalAs(UnmanagedType.Bool)]
        public bool NetworkConnectable;

        public uint NotConnectableReason;

        public uint NumberOfPhyTypes;

        [MarshalAs(UnmanagedType.ByValArray, SizeConst = 8)]
        public uint[] PhyTypes;

        [MarshalAs(UnmanagedType.Bool)]
        public bool MorePhyTypes;

        public uint SignalQuality;

        [MarshalAs(UnmanagedType.Bool)]
        public bool SecurityEnabled;

        public uint DefaultAuthAlgorithm;

        public uint DefaultCipherAlgorithm;

        public uint Flags;

        public uint Reserved;
    }
}

public sealed record VisibleWifiNetwork(string Ssid, int SignalQuality, bool IsSecure)
{
    public string DisplayName => IsSecure
        ? $"{Ssid} ({SignalQuality}%)"
        : $"{Ssid} ({SignalQuality}%, open)";

    public override string ToString()
    {
        return DisplayName;
    }
}
