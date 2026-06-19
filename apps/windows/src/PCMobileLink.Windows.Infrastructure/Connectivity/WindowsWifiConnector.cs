using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security;

namespace PCMobileLink.Windows.Infrastructure.Connectivity;

public sealed class WindowsWifiConnector
{
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
}
