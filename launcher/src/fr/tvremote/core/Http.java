// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The little HTTP/1.1 the remote needs: reading a request (line, headers, body), writing an answer
 * (always "Connection: close"), decoding URLs. Plain Java, no Android: tested on a PC (test/).
 */
final class Http {
    static final int MAX_HEAD = 16 * 1024, MAX_BODY = 64 * 1024;

    private Http() { }

    /** A request as read; {@code path} is still URL-encoded. */
    static final class Request {
        String method = "", path = "", query = "", contentType = "", ifNoneMatch, host, authorization;
        /** Address of the phone, for the password failures. */
        String remote = "";
        byte[] body = new byte[0];

        boolean get() { return method.equals("GET"); }

        boolean post() { return method.equals("POST"); }
    }

    /** Reads the request line, headers and body; null if malformed or too big. {@code in}: buffered. */
    static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last = 0;
        while (last != 0x0D0A0D0A) {                             // up to the blank line
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > MAX_HEAD) return null;
            last = (last << 8) | b;
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
        if (lines.length == 0) return null;                      // only blank lines
        String[] first = lines[0].split(" ");
        if (first.length < 2) return null;
        Request rq = new Request();
        rq.method = first[0];
        String target = first[1];
        int qm = target.indexOf('?');
        rq.path = qm < 0 ? target : target.substring(0, qm);
        if (qm >= 0) rq.query = target.substring(qm + 1);
        int length = 0;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 0) continue;
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            switch (name) {
                case "content-length":
                    try { length = Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
                    break;
                case "content-type": rq.contentType = value.toLowerCase(Locale.ROOT); break;
                case "if-none-match": rq.ifNoneMatch = value; break;
                case "host": rq.host = value.toLowerCase(Locale.ROOT); break;
                case "authorization": rq.authorization = value; break;
                default: break;
            }
        }
        if (length < 0 || length > MAX_BODY) return null;
        rq.body = new byte[length];
        for (int read = 0; read < length; ) {
            int n = in.read(rq.body, read, length - read);
            if (n < 0) return null;
            read += n;
        }
        return rq;
    }

    /**
     * Writes an answer. {@code headers}: extra lines, each ending with CRLF. Every answer says the
     * page may not be framed by another site.
     */
    static void write(OutputStream out, String status, String type, byte[] body, String headers) throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append("X-Frame-Options: DENY\r\n")
                .append("Content-Security-Policy: frame-ancestors 'none'\r\n")
                .append(headers == null ? "" : headers).append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();
    }

    static void json(OutputStream out, String body) throws IOException {
        write(out, "200 OK", "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8),
                "Cache-Control: no-store\r\n");
    }

    static void status(OutputStream out, String status) throws IOException {
        write(out, status, null, null, "");
    }

    /** URL-decodes {@code s}; '+' stays a '+' (paths). */
    static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /** Query string → map; '+' is a space there. */
    static Map<String, String> params(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) m.put(decode(kv), "");
            else m.put(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1).replace('+', ' ')));
        }
        return m;
    }

    static int toInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A file of the page's folder: {@code raw} as found in the URL, decoded, then checked (no
     * "..", no absolute path, no backslash); null if it may not be served.
     */
    static String assetPath(String raw) {
        String p = decode(raw);
        if (p.isEmpty() || p.startsWith("/") || p.contains("\\") || p.indexOf('\0') >= 0) return null;
        for (String part : p.split("/")) if (part.equals("..") || part.equals(".")) return null;
        return p;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".webmanifest")) return "application/manifest+json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
