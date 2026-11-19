// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** The TV's address on the local network. Plain Java; the choice of interface is tested on a PC. */
final class Net {
    private Net() { }

    /**
     * Private IPv4 address of the best interface: Ethernet or Wi-Fi first; VPNs, mobile data,
     * containers and other virtual interfaces last; null if there is none.
     */
    static String localAddress() {
        List<String[]> found = new ArrayList<>();             // {interface name, address}
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) found.add(new String[]{ni.getName(), a.getHostAddress()});
                }
            }
        } catch (Exception ignored) { }
        return best(found);
    }

    /** The address of the best-ranked interface among {name, address} pairs (see {@link #rank}). */
    static String best(List<String[]> found) {
        String[] best = null;
        for (String[] f : found) if (best == null || rank(f[0]) < rank(best[0])) best = f;
        return best == null ? null : best[1];
    }

    /** 0: Ethernet, 1: Wi-Fi, 2: other, 3: VPN, mobile data, bridges, containers. */
    static int rank(String iface) {
        String n = iface == null ? "" : iface.toLowerCase(Locale.ROOT);
        if (n.startsWith("eth") || n.startsWith("en")) return 0;
        if (n.startsWith("wlan") || n.startsWith("wl")) return 1;
        String[] virtual = {"tun", "tap", "ppp", "rmnet", "ccmni", "docker", "veth", "br-", "virbr", "dummy", "ipsec", "wg", "p2p"};
        for (String v : virtual) if (n.startsWith(v)) return 3;
        return 2;
    }
}
