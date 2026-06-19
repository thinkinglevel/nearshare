using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace PCMobileLink.Windows.Infrastructure.Discovery;

internal static class UdpBroadcastEndpoints
{
    public static IReadOnlyList<IPEndPoint> Resolve(int port)
    {
        Dictionary<string, IPEndPoint> endpoints = new(StringComparer.OrdinalIgnoreCase);
        AddEndpoint(endpoints, IPAddress.Broadcast, port);

        try
        {
            foreach (NetworkInterface networkInterface in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (networkInterface.OperationalStatus != OperationalStatus.Up
                    || networkInterface.NetworkInterfaceType == NetworkInterfaceType.Loopback)
                {
                    continue;
                }

                foreach (UnicastIPAddressInformation address in networkInterface.GetIPProperties().UnicastAddresses)
                {
                    if (address.Address.AddressFamily != AddressFamily.InterNetwork
                        || address.IPv4Mask is null)
                    {
                        continue;
                    }

                    AddEndpoint(endpoints, CreateBroadcastAddress(address.Address, address.IPv4Mask), port);
                }
            }
        }
        catch (NetworkInformationException)
        {
            // Keep the global broadcast fallback if adapter enumeration fails.
        }
        catch (SocketException)
        {
            // Keep the global broadcast fallback if adapter enumeration fails.
        }

        return endpoints.Values.ToList();
    }

    private static void AddEndpoint(Dictionary<string, IPEndPoint> endpoints, IPAddress address, int port)
    {
        endpoints[$"{address}:{port}"] = new IPEndPoint(address, port);
    }

    private static IPAddress CreateBroadcastAddress(IPAddress address, IPAddress subnetMask)
    {
        byte[] addressBytes = address.GetAddressBytes();
        byte[] maskBytes = subnetMask.GetAddressBytes();
        byte[] broadcastBytes = new byte[addressBytes.Length];

        for (int index = 0; index < addressBytes.Length; index++)
        {
            broadcastBytes[index] = (byte)(addressBytes[index] | ~maskBytes[index]);
        }

        return new IPAddress(broadcastBytes);
    }
}
