// TV Remote core 0.2.0 - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
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
 * On start it probes the name (3 queries, 250 ms apart): if another device answers for it, it
 * takes "&lt;name&gt;-2", "-3"… Then it announces the address twice, answers A queries (and AAAA ones
 * with an NSEC saying there is no IPv6 address), and says goodbye (TTL 0) when stopped.
 * Everything runs on its own threads; nothing here blocks the caller.
 */
final class Mdns {
    private static final String TAG = "TvRemote";
    private static final String GROUP = "224.0.0.251";
    private static final int PORT = 5353, TTL = 120;
    private static final int TYPE_A = 1, TYPE_AAAA = 28, TYPE_NSEC = 47, TYPE_ANY = 255, CLASS_IN = 1;

    interface Listener { void nameChanged(String name); }

    private final Context ctx;
    private final String wanted;
    private final Listener listener;
    private final ExecutorService out = Executors.newSingleThreadExecutor(r -> daemon(r, "tvremote-mdns-out"));
    private volatile String name;        // name in use, without ".local"
    private volatile byte[] address;     // IPv4, 4 bytes
    private volatile boolean probing, conflict, closed;
    private MulticastSocket socket;
    private WifiManager.MulticastLock lock;

    Mdns(Context ctx, String name, String ip, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.wanted = name;
        this.name = name;
        this.listener = listener;
        this.address = ipv4(ip);
    }

    /** Name in use, without ".local" (may change once, after the probe). */
    String name() { return name; }

    void start() {
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
        daemon(this::run, "tvremote-mdns").start();
    }

    /** The TV's address changed: announce the new one. */
    void setAddress(String ip) {
        byte[] a = ipv4(ip);
        if (a == null) return;
        address = a;
        submit(() -> { announce(TTL); sleep(1000); announce(TTL); });
    }

    void stop() {
        if (closed) return;
        closed = true;
        submit(() -> {
            announce(0);                                  // goodbye
            MulticastSocket s = socket;
            if (s != null) s.close();
        });
        out.shutdown();
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (RuntimeException ignored) { }
    }

    // ------------------------------------------------------------------ receive

    private void run() {
        try {
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(PORT));
            s.setTimeToLive(255);
            NetworkInterface ni = iface();
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
                handle(p);
            } catch (RuntimeException ignored) {          // malformed packet
            }
        }
    }

    private void handle(DatagramPacket p) {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 12) return;
        int id = u16(d, 0), flags = u16(d, 2), qd = u16(d, 4), an = u16(d, 6);
        int[] pos = {12};
        String fqdn = name + ".local";
        if ((flags & 0x8000) != 0) {                      // a response: someone else holding our name?
            if (!probing) return;
            for (int i = 0; i < qd; i++) { readName(d, pos, len); pos[0] += 4; }
            for (int i = 0; i < an; i++) {
                String n = readName(d, pos, len);
                int type = u16(d, pos[0]);
                int rdlen = u16(d, pos[0] + 8);
                pos[0] += 10;
                if (n.equalsIgnoreCase(fqdn) && type == TYPE_A && rdlen == 4 && !sameAddress(d, pos[0])) conflict = true;
                pos[0] += rdlen;
            }
            return;
        }
        if (probing) return;
        boolean legacy = p.getPort() != PORT;             // one-shot resolver: unicast answer, same id
        for (int i = 0; i < qd; i++) {
            String n = readName(d, pos, len);
            int type = u16(d, pos[0]), cls = u16(d, pos[0] + 2);
            pos[0] += 4;
            if (!n.equalsIgnoreCase(fqdn)) continue;
            boolean unicast = legacy || (cls & 0x8000) != 0;
            if (type == TYPE_A || type == TYPE_ANY) {
                reply(legacy ? id : 0, legacy ? fqdn : null, TYPE_A, unicast ? p.getSocketAddress() : null, legacy);
            } else if (type == TYPE_AAAA) {
                reply(legacy ? id : 0, legacy ? fqdn : null, TYPE_NSEC, unicast ? p.getSocketAddress() : null, legacy);
            }
        }
    }

    private boolean sameAddress(byte[] d, int at) {
        byte[] a = address;
        if (a == null) return false;
        for (int i = 0; i < 4; i++) if (d[at + i] != a[i]) return false;
        return true;
    }

    // ------------------------------------------------------------------ send

    /** Probes the name (taking "-2", "-3"… on conflict), then announces it twice. */
    private void probeAndAnnounce() {
        for (int n = 1; n <= 20 && !closed; n++) {
            String candidate = n == 1 ? wanted : suffixed(wanted, n);
            name = candidate;
            conflict = false;
            probing = true;
            for (int i = 0; i < 3 && !conflict; i++) {
                send(query(candidate + ".local"), null);
                sleep(250);
            }
            sleep(250);
            probing = false;
            if (!conflict) break;
            Log.i(TAG, "mDNS: " + candidate + ".local is taken");
        }
        if (!name.equals(wanted) && listener != null) listener.nameChanged(name);
        Log.i(TAG, "mDNS: " + name + ".local");
        announce(TTL);
        sleep(1000);
        announce(TTL);
    }

    private void announce(int ttl) {
        byte[] m = response(0, null, TYPE_A, ttl, false);
        if (m != null) send(m, null);
    }

    private void reply(int id, String question, int type, java.net.SocketAddress to, boolean legacy) {
        byte[] m = response(id, question, type, legacy ? 10 : TTL, legacy);
        if (m != null) submit(() -> send(m, to));
    }

    private void send(byte[] m, java.net.SocketAddress to) {
        MulticastSocket s = socket;
        if (s == null) return;
        try {
            java.net.SocketAddress dest = to != null ? to : new InetSocketAddress(InetAddress.getByName(GROUP), PORT);
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

    // ------------------------------------------------------------------ messages

    private static byte[] query(String fqdn) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        u16(b, 0); u16(b, 0); u16(b, 1); u16(b, 0); u16(b, 0); u16(b, 0);
        writeName(b, fqdn);
        u16(b, TYPE_ANY);
        u16(b, CLASS_IN | 0x8000);                       // QU: answer us directly
        return b.toByteArray();
    }

    /** An A record (or an NSEC saying "A only") for our name; {@code question}: echoed for legacy resolvers. */
    private byte[] response(int id, String question, int type, int ttl, boolean legacy) {
        byte[] a = address;
        if (a == null) return null;
        String fqdn = name + ".local";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        u16(b, id);
        u16(b, 0x8400);                                  // response, authoritative
        u16(b, question == null ? 0 : 1);
        u16(b, 1); u16(b, 0); u16(b, 0);
        if (question != null) {
            writeName(b, question);
            u16(b, type == TYPE_NSEC ? TYPE_AAAA : TYPE_A);
            u16(b, CLASS_IN);
        }
        writeName(b, fqdn);
        u16(b, type);
        u16(b, legacy ? CLASS_IN : CLASS_IN | 0x8000);   // cache flush: we own this name
        u32(b, ttl);
        if (type == TYPE_A) {
            u16(b, 4);
            b.write(a, 0, 4);
        } else {                                          // NSEC: next name = ours, bitmap: A only
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            writeName(r, fqdn);
            r.write(0); r.write(1); r.write(0x40);
            byte[] rd = r.toByteArray();
            u16(b, rd.length);
            b.write(rd, 0, rd.length);
        }
        return b.toByteArray();
    }

    private static void writeName(ByteArrayOutputStream b, String fqdn) {
        for (String label : fqdn.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.UTF_8);
            b.write(l.length);
            b.write(l, 0, l.length);
        }
        b.write(0);
    }

    /** Reads a (possibly compressed) name at pos[0] and moves pos past it. */
    private static String readName(byte[] d, int[] pos, int len) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0], jumps = 0;
        boolean jumped = false;
        while (true) {
            if (p >= len) throw new IllegalArgumentException("name");
            int l = d[p] & 0xFF;
            if (l == 0) { p++; break; }
            if ((l & 0xC0) == 0xC0) {
                int ptr = ((l & 0x3F) << 8) | (d[p + 1] & 0xFF);
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                if (++jumps > 20) throw new IllegalArgumentException("loop");
                p = ptr;
                continue;
            }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, p + 1, l, StandardCharsets.UTF_8));
            p += l + 1;
        }
        if (!jumped) pos[0] = p;
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ helpers

    private NetworkInterface iface() {
        try {
            byte[] a = address;
            return a == null ? null : NetworkInterface.getByInetAddress(InetAddress.getByAddress(a));
        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] ipv4(String ip) {
        try {
            InetAddress a = InetAddress.getByName(ip);
            return a instanceof Inet4Address ? a.getAddress() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String suffixed(String base, int n) {
        String s = "-" + n;
        return (base.length() + s.length() > 63 ? base.substring(0, 63 - s.length()) : base) + s;
    }

    private static int u16(byte[] d, int at) { return ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF); }

    private static void u16(ByteArrayOutputStream b, int v) { b.write((v >> 8) & 0xFF); b.write(v & 0xFF); }

    private static void u32(ByteArrayOutputStream b, int v) { u16(b, (v >>> 16) & 0xFFFF); u16(b, v & 0xFFFF); }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
