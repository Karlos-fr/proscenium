// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Who may use the remote: the Host header must be one of the TV's names (against DNS
 * rebinding), and, when a password is set, HTTP Basic (any user name), compared in constant time.
 * After {@link #FREE_FAILURES} wrong passwords from one address, that address must wait, the pause
 * doubling up to {@link #MAX_PAUSE_MS}. Plain Java, thread-safe; tested on a PC (test/).
 */
final class Guard {
    enum Verdict { OK, WRONG_HOST, ASK_PASSWORD, PAUSED }

    static final int FREE_FAILURES = 5;
    static final long MAX_PAUSE_MS = 60_000, FORGET_MS = 10 * 60_000;
    private static final int MAX_TRACKED = 256;

    private final Map<String, long[]> failures = new HashMap<>();   // address → {count, paused until, last}
    private volatile String password;

    Guard(String password) {
        setPassword(password);
    }

    void setPassword(String p) {
        password = p == null ? "" : p;
        synchronized (failures) { failures.clear(); }
    }

    boolean hasPassword() { return !password.isEmpty(); }

    /**
     * {@code host}: the Host header (null when absent: allowed, for HTTP/1.0 tools); {@code names}:
     * the TV's names, lower case, without port (its IP, its .local names, localhost…).
     */
    static boolean hostAllowed(String host, Set<String> names) {
        if (host == null || host.isEmpty()) return true;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end >= 0) h = h.substring(0, end + 1);
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
        }
        return names.contains(h);
    }

    /** May {@code remote} (the phone's address) go on with this Authorization header? */
    Verdict check(String remote, String authorization, long now) {
        String pw = password;
        if (pw.isEmpty()) return Verdict.OK;
        synchronized (failures) {
            long[] f = failures.get(remote);
            if (f != null && f[1] > now) return Verdict.PAUSED;
        }
        String given = basicPassword(authorization);
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), pw.getBytes(StandardCharsets.UTF_8))) {
            synchronized (failures) { failures.remove(remote); }
            return Verdict.OK;
        }
        if (given != null) fail(remote, now);                // none given: the browser's first ask, not a failure
        return Verdict.ASK_PASSWORD;
    }

    /** Seconds before {@code remote} may try again (0: now). */
    long retryAfterSeconds(String remote, long now) {
        synchronized (failures) {
            long[] f = failures.get(remote);
            return f == null || f[1] <= now ? 0 : (f[1] - now + 999) / 1000;     // rounded up
        }
    }

    private void fail(String remote, long now) {
        synchronized (failures) {
            prune(now);
            long[] f = failures.get(remote);
            if (f == null) failures.put(remote, f = new long[3]);
            f[0]++;
            f[2] = now;
            if (f[0] >= FREE_FAILURES) f[1] = now + Math.min(MAX_PAUSE_MS, 1000L << Math.min(16, f[0] - FREE_FAILURES));
        }
    }

    /** Forgets addresses quiet for a while (and keeps the table small). */
    private void prune(long now) {
        for (Iterator<long[]> it = failures.values().iterator(); it.hasNext(); ) {
            long[] f = it.next();
            if (now - f[2] > FORGET_MS && f[1] <= now) it.remove();
        }
        if (failures.size() > MAX_TRACKED) failures.clear();
    }

    /** The password of a "Basic …" header (whatever the user name), or null. */
    static String basicPassword(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        byte[] raw = base64(authorization.substring(6).trim());
        if (raw == null) return null;
        String up = new String(raw, StandardCharsets.UTF_8);
        int colon = up.indexOf(':');
        return colon < 0 ? up : up.substring(colon + 1);
    }

    /** Standard Base64 (padding optional); null if invalid. java.util.Base64 needs Android 8. */
    static byte[] base64(String s) {
        String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buf = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=') break;
            int v = a.indexOf(c);
            if (v < 0) return null;
            buf = (buf << 6) | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out.write((buf >> bits) & 0xFF);
            }
        }
        return out.toByteArray();
    }
}
