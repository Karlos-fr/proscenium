// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A minimal mDNS responder (RFC 6762) for one name: answers "&lt;name&gt;.local" with the TV's IPv4
 * address, so that phones and computers can open http://&lt;name&gt;.local:&lt;port&gt;/.
 *
 * Android's own mDNS daemon only publishes services, under a host name the app cannot choose
 * (Android_xxxx.local), hence this responder. It shares port 5353 with the system (SO_REUSEADDR)
 * and holds a Wi-Fi multicast lock while it runs (permission CHANGE_WIFI_MULTICAST_STATE).
 *
 * Name: probed first (3 probes, 250 ms apart, our address in the authority section). Another device
 * answering for it, or probing for it at the same time with a greater address (§8.2), makes us take
 * "&lt;name&gt;-2", "-3"…; so does a device announcing it later with another address (§9: probe
 * again). Then: two announcements, answers to A queries (AAAA ones get an NSEC: no IPv6), and a
 * goodbye (TTL 0) when stopped. One instance per interface and address: on a network change, the
 * owner stops it and starts a new one. Nothing here blocks the caller.
 */
final class Mdns {
    private static final String TAG = "TvRemote";
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353, TTL = 120, MAX_TRIES = 20;

    interface Listener { void nameChanged(String name); }

    private final Context ctx;
    private final String wanted;
    private final byte[] address;            // IPv4, 4 bytes
    private final Listener listener;
    private final ExecutorService out = Executors.newSingleThreadExecutor(r -> daemon(r, "tvremote-mdns-out"));
    private volatile String name;            // name in use, without ".local"
    private volatile int attempt = 1;
    private volatile boolean probing, conflict, closed;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;

    Mdns(Context ctx, String name, String ip, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.wanted = name;
        this.name = name;
        this.address = ipv4(ip);
        this.listener = listener;
    }

    /** Name in use, without ".local". */
    String name() { return name; }

    void start() {
        if (address == null) return;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("tvremote-mdns");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "mDNS: no multicast lock (permission CHANGE_WIFI_MULTICAST_STATE?)", e);
        }
        daemon(this::receive, "tvremote-mdns").start();
    }

    void stop() {
        if (closed) return;
        closed = true;
        submit(() -> {
            if (!probing) send(MdnsMessage.answer(0, name + ".local", address, false, 0, false), null);   // goodbye
            MulticastSocket s = socket;
            if (s != null) s.close();
        });
        out.shutdown();
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ receive

    private void receive() {
        try {
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            s.setTimeToLive(255);
            NetworkInterface ni = NetworkInterface.getByInetAddress(InetAddress.getByAddress(address));
            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName(GROUP), PORT);
            if (ni != null) {
                s.setNetworkInterface(ni);
                s.joinGroup(group, ni);
            } else {
                s.joinGroup(InetAddress.getByName(GROUP));
            }
            socket = s;
        } catch (IOException e) {
            Log.w(TAG, "mDNS: cannot listen on 5353", e);
            return;
        }
        submit(this::probeAndAnnounce);
        byte[] buf = new byte[9000];
        while (!closed) {
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (IOException e) {
                if (!closed) Log.w(TAG, "mDNS: receive", e);
                break;
            }
            try {
                handle(MdnsMessage.parse(p.getData(), p.getLength()), p.getSocketAddress(), p.getPort());
            } catch (IllegalArgumentException ignored) {      // malformed packet
            }
        }
    }

    private void handle(MdnsMessage m, SocketAddress from, int fromPort) {
        String fqdn = name + ".local";
        if (m.response()) {
            // Someone else answering for our name with another address
            for (MdnsMessage.Record r : m.answers) {
                if (r.type == MdnsMessage.TYPE_A && r.name.equals(fqdn) && r.ttl > 0 && !Arrays.equals(r.data, address)) {
                    if (probing) conflict = true; else submit(this::reprobe);
                }
            }
            return;
        }
        if (probing) {
            // A simultaneous probe for the same name: the greater address keeps it (§8.2)
            for (MdnsMessage.Record r : m.authorities) {
                if (r.type == MdnsMessage.TYPE_A && r.name.equals(fqdn) && MdnsMessage.compare(address, r.data) < 0) conflict = true;
            }
            return;
        }
        boolean legacy = fromPort != PORT;                    // one-shot resolver: unicast answer, same id
        for (MdnsMessage.Question q : m.questions) {
            if (!q.name.equals(fqdn)) continue;
            boolean a = q.type == MdnsMessage.TYPE_A || q.type == MdnsMessage.TYPE_ANY;
            if (!a && q.type != MdnsMessage.TYPE_AAAA) continue;
            byte[] answer = MdnsMessage.answer(m.id, fqdn, address, !a, TTL, legacy);
            SocketAddress to = legacy || q.unicast() ? from : null;
            submit(() -> send(answer, to));
        }
    }

    // ------------------------------------------------------------------ send

    /** Probes the name (taking "-2", "-3"… on conflict), then announces it twice. */
    private void probeAndAnnounce() {
        String before = name;
        for (; attempt <= MAX_TRIES && !closed; attempt++) {
            String candidate = attempt == 1 ? wanted : suffixed(wanted, attempt);
            name = candidate;
            conflict = false;
            probing = true;
            for (int i = 0; i < 3 && !conflict; i++) {
                send(MdnsMessage.probe(candidate + ".local", address), null);
                sleep(250);
            }
            sleep(250);
            probing = false;
            if (!conflict) break;
            Log.i(TAG, "mDNS: " + candidate + ".local is taken");
        }
        if (closed) return;
        Log.i(TAG, "mDNS: " + name + ".local");
        if (!name.equals(before) && listener != null) listener.nameChanged(name);
        for (int i = 0; i < 2 && !closed; i++) {
            if (i > 0) sleep(1000);
            send(MdnsMessage.answer(0, name + ".local", address, false, TTL, false), null);
        }
    }

    /** Our name was claimed later by another device: probe again, from the next name (§9). */
    private void reprobe() {
        if (closed || probing) return;
        attempt++;
        probeAndAnnounce();
    }

    private void send(byte[] m, SocketAddress to) {
        MulticastSocket s = socket;
        if (s == null) return;
        try {
            SocketAddress dest = to != null ? to : new InetSocketAddress(InetAddress.getByName(GROUP), PORT);
            s.send(new DatagramPacket(m, m.length, dest));
        } catch (IOException e) {
            if (!closed) Log.w(TAG, "mDNS: send", e);
        }
    }

    private void submit(Runnable r) {
        try {
            out.execute(r);
        } catch (RuntimeException ignored) {             // stopped
        }
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] ipv4(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a instanceof Inet4Address ? a.getAddress() : null;
        } catch (Exception e) {
            return null;
        }
    }

    static String suffixed(String base, int n) {
        String s = "-" + n;
        return (base.length() + s.length() > 63 ? base.substring(0, 63 - s.length()) : base) + s;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
