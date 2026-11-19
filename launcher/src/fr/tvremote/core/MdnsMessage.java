// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DNS messages as mDNS uses them (RFC 1035 / 6762), only what {@link Mdns} needs: build a probe,
 * an A answer or an NSEC ("no IPv6"), and read questions and records (compressed names included).
 * Plain Java; tested on a PC (test/).
 */
final class MdnsMessage {
    static final int TYPE_A = 1, TYPE_AAAA = 28, TYPE_NSEC = 47, TYPE_ANY = 255;
    static final int CLASS_IN = 1, UNIQUE = 0x8000;          // QU bit in questions, cache-flush bit in records

    static final class Question {
        String name;
        int type, cls;

        boolean unicast() { return (cls & UNIQUE) != 0; }
    }

    static final class Record {
        String name;
        int type, cls;
        long ttl;
        byte[] data;
    }

    int id, flags;
    final List<Question> questions = new ArrayList<>();
    final List<Record> answers = new ArrayList<>(), authorities = new ArrayList<>();

    boolean response() { return (flags & 0x8000) != 0; }

    // ------------------------------------------------------------------ building

    /** Probe for {@code fqdn} (RFC 6762 §8.1): question ANY with QU, our proposed A in the authority section. */
    static byte[] probe(String fqdn, byte[] ipv4) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        header(b, 0, 0, 1, 0, 1);
        name(b, fqdn);
        u16(b, TYPE_ANY);
        u16(b, CLASS_IN | UNIQUE);
        record(b, fqdn, TYPE_A, CLASS_IN, 120, ipv4);
        return b.toByteArray();
    }

    /**
     * Answer for {@code fqdn}: its A record ({@code nsec} false) or an NSEC saying it has only an A
     * one. {@code legacy}: for a one-shot resolver (id and question echoed, no cache-flush, ttl 10).
     */
    static byte[] answer(int id, String fqdn, byte[] ipv4, boolean nsec, long ttl, boolean legacy) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        header(b, legacy ? id : 0, 0x8400, legacy ? 1 : 0, 1, 0);
        if (legacy) {
            name(b, fqdn);
            u16(b, nsec ? TYPE_AAAA : TYPE_A);
            u16(b, CLASS_IN);
        }
        int cls = legacy ? CLASS_IN : CLASS_IN | UNIQUE;
        long t = legacy ? Math.min(ttl, 10) : ttl;
        if (!nsec) {
            record(b, fqdn, TYPE_A, cls, t, ipv4);
        } else {
            ByteArrayOutputStream r = new ByteArrayOutputStream();
            name(r, fqdn);                                     // next name: ours
            r.write(0); r.write(1); r.write(0x40);            // window 0, 1 byte, bit of type 1 (A)
            record(b, fqdn, TYPE_NSEC, cls, t, r.toByteArray());
        }
        return b.toByteArray();
    }

    private static void header(ByteArrayOutputStream b, int id, int flags, int qd, int an, int ns) {
        u16(b, id); u16(b, flags); u16(b, qd); u16(b, an); u16(b, ns); u16(b, 0);
    }

    private static void record(ByteArrayOutputStream b, String fqdn, int type, int cls, long ttl, byte[] data) {
        name(b, fqdn);
        u16(b, type);
        u16(b, cls);
        u16(b, (int) (ttl >>> 16) & 0xFFFF);
        u16(b, (int) ttl & 0xFFFF);
        u16(b, data.length);
        b.write(data, 0, data.length);
    }

    static void name(ByteArrayOutputStream b, String fqdn) {
        for (String label : fqdn.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.UTF_8);
            b.write(l.length);
            b.write(l, 0, l.length);
        }
        b.write(0);
    }

    private static void u16(ByteArrayOutputStream b, int v) { b.write((v >> 8) & 0xFF); b.write(v & 0xFF); }

    // ------------------------------------------------------------------ reading

    /** Reads a message; throws IllegalArgumentException if it is malformed. */
    static MdnsMessage parse(byte[] d, int len) {
        if (len < 12) throw new IllegalArgumentException("short");
        MdnsMessage m = new MdnsMessage();
        m.id = u16(d, 0, len);
        m.flags = u16(d, 2, len);
        int qd = u16(d, 4, len), an = u16(d, 6, len), ns = u16(d, 8, len);
        int[] pos = {12};
        for (int i = 0; i < qd; i++) {
            Question q = new Question();
            q.name = readName(d, pos, len);
            q.type = u16(d, pos[0], len);
            q.cls = u16(d, pos[0] + 2, len);
            pos[0] += 4;
            m.questions.add(q);
        }
        for (int i = 0; i < an; i++) m.answers.add(readRecord(d, pos, len));
        for (int i = 0; i < ns; i++) m.authorities.add(readRecord(d, pos, len));
        return m;                                              // additional records: not needed
    }

    private static Record readRecord(byte[] d, int[] pos, int len) {
        Record r = new Record();
        r.name = readName(d, pos, len);
        r.type = u16(d, pos[0], len);
        r.cls = u16(d, pos[0] + 2, len);
        r.ttl = ((long) u16(d, pos[0] + 4, len) << 16) | u16(d, pos[0] + 6, len);
        int rdlen = u16(d, pos[0] + 8, len);
        pos[0] += 10;
        if (pos[0] + rdlen > len) throw new IllegalArgumentException("rdata");
        r.data = new byte[rdlen];
        System.arraycopy(d, pos[0], r.data, 0, rdlen);
        pos[0] += rdlen;
        return r;
    }

    /** Reads a (possibly compressed) name at pos[0], lower case, and moves pos past it. */
    static String readName(byte[] d, int[] pos, int len) {
        StringBuilder sb = new StringBuilder();
        int p = pos[0], jumps = 0;
        boolean jumped = false;
        while (true) {
            if (p >= len) throw new IllegalArgumentException("name");
            int l = d[p] & 0xFF;
            if (l == 0) { p++; break; }
            if ((l & 0xC0) == 0xC0) {
                if (p + 1 >= len) throw new IllegalArgumentException("pointer");
                int ptr = ((l & 0x3F) << 8) | (d[p + 1] & 0xFF);
                if (!jumped) pos[0] = p + 2;
                jumped = true;
                if (++jumps > 20) throw new IllegalArgumentException("loop");
                p = ptr;
                continue;
            }
            if ((l & 0xC0) != 0 || p + 1 + l > len) throw new IllegalArgumentException("label");
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(d, p + 1, l, StandardCharsets.UTF_8));
            p += l + 1;
        }
        if (!jumped) pos[0] = p;
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static int u16(byte[] d, int at, int len) {
        if (at + 1 >= len) throw new IllegalArgumentException("truncated");
        return ((d[at] & 0xFF) << 8) | (d[at + 1] & 0xFF);
    }

    /**
     * Simultaneous probes (RFC 6762 §8.2): compares our proposed A data with theirs, as unsigned
     * bytes; the greater one wins. &lt; 0: we lose, 0: same (our own probe), &gt; 0: we win.
     */
    static int compare(byte[] ours, byte[] theirs) {
        for (int i = 0; i < Math.min(ours.length, theirs.length); i++) {
            int a = ours[i] & 0xFF, b = theirs[i] & 0xFF;
            if (a != b) return a - b;
        }
        return ours.length - theirs.length;
    }
}
