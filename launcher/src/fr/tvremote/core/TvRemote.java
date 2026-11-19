// TV Remote core 0.2.0 - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Web remote served by the TV on the local network (format v1, see FORMAT.md).
 *
 * <pre>
 * TvRemote remote = TvRemote.start(context, app, new TvRemote.Options().hostname("proscenium"));
 * remote.url();                 // http://192.168.1.20:8770/  → QR code (with ACCESS_TOKEN: …/3f…/)
 * remote.localName();           // http://proscenium.local:8770/
 * remote.changed("weather");    // a setting changed on the TV: the phones refresh this screen
 * remote.state(json);           // live state (now playing…), sent to every phone
 * remote.stop();
 * </pre>
 *
 * Every call to {@link App} is made on the main thread (the core waits up to 5 s), except
 * {@link App#image}, made on a server thread so that pictures can be read or encoded there.
 */
public final class TvRemote {
    public static final int FORMAT = 1;
    public static final String VERSION = "0.2.0";
    private static final String TAG = "TvRemote";

    /** What the app gives the remote. */
    public interface App {
        /** Name, language, theme, root screen, tabs. */
        AppInfo info();

        /** The screen {@code id}, or null if there is none. {@code q}: paging and search (paged screens). */
        Screen screen(String id, Query q);

        /**
         * A value changed on the phone. {@code value}: Boolean, Number, String, or JSONArray
         * (order of a list). Ticking a tile or a row gives {@code id = "<grid>/<tile>"} and a Boolean.
         */
        Result set(String screen, String id, Object value);

        /** A button, or a tile of a grid that opens something, was tapped ({@code id = "<grid>/<tile>"}). */
        Result action(String screen, String id);

        /** A phone shows this screen (first phone only) — e.g. to preview something on the TV. */
        default void opened(String screen) { }

        /** No phone shows this screen any more. */
        default void closed(String screen) { }

        /** Picture served at img/&lt;key&gt; (key chosen by the app, never a path); null: 404. Server thread. */
        default Image image(String key) { return null; }
    }

    /** Paging of a screen: search text, first entry, number of entries. */
    public static final class Query {
        public final String q;
        public final int offset, limit;

        Query(String q, int offset, int limit) {
            this.q = q == null ? "" : q;
            this.offset = Math.max(0, offset);
            this.limit = limit <= 0 ? 60 : Math.min(limit, 500);
        }
    }

    /** Fixed address http://&lt;name&gt;.local:&lt;port&gt;/ (and the IP), no token. */
    public static final String ACCESS_OPEN = "open";
    /** Address with a random token, new at each start: http://&lt;ip&gt;:&lt;port&gt;/&lt;token&gt;/. */
    public static final String ACCESS_TOKEN = "token";

    public static final class Options {
        /**
         * Port. With {@link #ACCESS_OPEN} it is fixed: if it is busy, {@link #start} fails
         * ({@link #lastError()}); with {@link #ACCESS_TOKEN} a free one is taken instead.
         */
        public int port = 8770;
        /** 0: while the app runs (ALWAYS); otherwise the server stops after this many ms (WINDOW). */
        public long lifetimeMs = 0;
        /** Folder of the page in the app's assets: index.html, core.js, the skin. */
        public String assetDir = "tvremote";
        public String access = ACCESS_OPEN;
        /** Name on the local network, without ".local": a-z, 0-9, "-" (1 to 63); null: no mDNS. */
        public String hostname = "tv";
        /** HTTP Basic password (any user name); empty: none. Can change while running: {@link #setPassword}. */
        public String password = "";
        /** Shown by the browser's login box (the app's name). */
        public String realm = "TV Remote";
        /** Runs on the main thread when the address or the .local name changed (new QR code). */
        public Runnable onAddressChanged;

        public Options port(int p) { port = p; return this; }
        public Options lifetime(long ms) { lifetimeMs = ms; return this; }
        public Options assetDir(String d) { assetDir = d; return this; }
        public Options access(String a) { access = ACCESS_TOKEN.equals(a) ? ACCESS_TOKEN : ACCESS_OPEN; return this; }
        public Options hostname(String h) { hostname = h; return this; }
        public Options password(String p) { password = p == null ? "" : p; return this; }
        public Options realm(String r) { realm = r; return this; }
        public Options onAddressChanged(Runnable r) { onAddressChanged = r; return this; }
    }

    /** A valid .local name made from {@code name} (lower case, a-z 0-9 -), or {@code fallback}. */
    public static String cleanHostname(String name, String fallback) {
        String h = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        boolean ok = h.length() >= 1 && h.length() <= 63 && h.matches("[a-z0-9-]+") && !h.startsWith("-") && !h.endsWith("-");
        return ok ? h : fallback;
    }

    private static volatile String lastError;

    /** Why the last {@link #start} returned null ("no network", "port 8770 busy"…), or null. */
    public static String lastError() { return lastError; }

    private static final int MAX_BODY = 64 * 1024, MAX_SSE = 4;
    private static final long MAIN_TIMEOUT_MS = 5000, PING_MS = 20_000, NET_CHECK_MS = 10_000;
    private static final int FREE_FAILURES = 5;
    private static final long MAX_PAUSE_MS = 60_000;

    private final AssetManager assets;
    private final App app;
    private final Options options;
    private final String token, prefix;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16), r -> daemon(r, "tvremote-http"));
    private final ScheduledExecutorService sse = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "tvremote-sse"));
    private final List<Client> clients = new ArrayList<>();          // SSE connections, oldest first; sse thread only
    private final Map<String, Set<String>> openedBy = new HashMap<>(); // client → screens it shows; main thread only
    private final Map<String, Integer> openCount = new HashMap<>();    // screen → number of clients; main thread only
    private final Map<String, long[]> failures = new HashMap<>();      // ip → {failures, paused until}
    private ServerSocket socket;
    private Mdns mdns;
    private volatile boolean closed;
    private volatile String state;                                    // last state, sent to new clients
    private volatile String ip, url, password;

    private TvRemote(Context ctx, App app, Options options) {
        this.assets = ctx.getApplicationContext().getAssets();
        this.app = app;
        this.options = options;
        this.password = options.password == null ? "" : options.password;
        if (ACCESS_TOKEN.equals(options.access)) {
            byte[] r = new byte[16];
            new SecureRandom().nextBytes(r);
            StringBuilder sb = new StringBuilder();
            for (byte b : r) sb.append(String.format("%02x", b & 0xFF));
            token = sb.toString();
            prefix = "/" + token + "/";
        } else {
            token = "";
            prefix = "/";
        }
    }

    /**
     * Starts the server (and the .local name); null if the TV has no address on the local network
     * or, with {@link #ACCESS_OPEN}, if the port is busy: see {@link #lastError()}.
     */
    public static TvRemote start(Context ctx, App app, Options options) {
        Options o = options == null ? new Options() : options;
        lastError = null;
        String ip = localAddress();
        if (ip == null) {
            lastError = "no local network";
            return null;
        }
        TvRemote s = new TvRemote(ctx, app, o);
        try {
            try {
                s.socket = new ServerSocket(o.port);
            } catch (IOException busy) {
                if (ACCESS_OPEN.equals(o.access)) {
                    lastError = "port " + o.port + " busy";
                    Log.w(TAG, lastError);
                    return null;
                }
                s.socket = new ServerSocket(0);
            }
        } catch (IOException e) {
            lastError = "no port";
            Log.w(TAG, "No port", e);
            return null;
        }
        s.setIp(ip);
        Thread t = daemon(s::accept, "tvremote-accept");
        t.start();
        s.sse.scheduleAtFixedRate(s::ping, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
        s.sse.scheduleAtFixedRate(s::checkNetwork, NET_CHECK_MS, NET_CHECK_MS, TimeUnit.MILLISECONDS);
        if (s.options.lifetimeMs > 0) s.main.postDelayed(s::stop, s.options.lifetimeMs);
        if (o.hostname != null) {
            s.mdns = new Mdns(ctx, cleanHostname(o.hostname, "tv"), ip, name -> s.addressChanged());
            s.mdns.start();
        }
        return s;
    }

    private void setIp(String newIp) {
        ip = newIp;
        url = "http://" + newIp + ":" + socket.getLocalPort() + prefix;
    }

    /** The TV changed address (new DHCP lease, Wi-Fi ↔ Ethernet): new URL, announced again. */
    private void checkNetwork() {
        String now = localAddress();
        if (now == null || now.equals(ip)) return;
        Log.i(TAG, "Address " + ip + " -> " + now);
        setIp(now);
        if (mdns != null) mdns.setAddress(now);
        addressChanged();
    }

    private void addressChanged() {
        Runnable r = options.onAddressChanged;
        if (r != null && !closed) main.post(() -> safe(r));
    }

    /** Address to open on the phone, with the IP (also for the QR code: every phone opens it). */
    public String url() { return url; }

    /** The same address with the .local name (null without one): to type or bookmark. */
    public String localName() {
        Mdns m = mdns;
        return m == null ? null : "http://" + m.name() + ".local:" + socket.getLocalPort() + prefix;
    }

    /** The .local name in use, without ".local" (may differ from the one asked: "-2"…), or null. */
    public String hostname() {
        Mdns m = mdns;
        return m == null ? null : m.name();
    }

    /** Port actually used. */
    public int port() { return socket.getLocalPort(); }

    /** Changes the password at once (empty: none). The access mode or the name need a new start. */
    public void setPassword(String p) {
        password = p == null ? "" : p;
        synchronized (failures) { failures.clear(); }
    }

    public boolean running() { return !closed; }

    /** QR code of {@link #url()}, black on white, with a quiet zone. */
    public Bitmap qrBitmap(int sizePx) {
        QrCode qr = QrCode.encode(url);
        int n = qr.size + 8, scale = Math.max(1, sizePx / n), side = n * scale;
        int[] px = new int[side * side];
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) {
                px[y * side + x] = qr.get(x / scale - 4, y / scale - 4) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return Bitmap.createBitmap(px, side, side, Bitmap.Config.ARGB_8888);
    }

    /** Stops the server; the phones are told ("bye"). */
    public void stop() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (mdns != null) mdns.stop();
        try { socket.close(); } catch (IOException ignored) { }
        try {
            sse.execute(() -> {
                for (Client c : clients) {
                    c.send("bye", "{}");
                    c.close();
                }
                clients.clear();
            });
        } catch (RejectedExecutionException ignored) { }
        sse.shutdown();
        pool.shutdown();
        // Screens still shown on a phone are closed for the app
        Runnable closeAll = () -> {
            for (String screen : new ArrayList<>(openCount.keySet())) safe(() -> app.closed(screen));
            openCount.clear();
            openedBy.clear();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) closeAll.run(); else main.post(closeAll);
    }

    /** A screen changed on the TV (null: all screens): the phones showing it refresh it. */
    public void changed(String screen) {
        changed(screen, "");
    }

    /** {@code by}: the phone that made the change (it already has the new screen). */
    private void changed(String screen, String by) {
        broadcast("changed", "{\"screen\":" + JSONObject.quote(screen == null ? "*" : screen)
                + ",\"by\":" + JSONObject.quote(by == null ? "" : by) + "}");
    }

    /** Live state of the app, sent to every phone now and to each new one. */
    public void state(JSONObject json) {
        state = json == null ? "{}" : json.toString();
        broadcast("state", state);
    }

    /** First IPv4 address of the local network (Wi-Fi or Ethernet), or null. */
    public static String localAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    // ================================================================== HTTP

    private void accept() {
        while (!closed) {
            Socket c;
            try {
                c = socket.accept();
            } catch (IOException e) {
                if (!closed) Log.w(TAG, "accept", e);
                continue;
            }
            try {
                pool.execute(() -> handle(c));
            } catch (RejectedExecutionException busy) {
                closeQuietly(c);
            }
        }
    }

    private static final class Request {
        String method, path, query = "", contentType = "", ifNoneMatch, host, authorization, remote = "";
        byte[] body = new byte[0];
    }

    private void handle(Socket c) {
        boolean keep = false;
        try {
            c.setSoTimeout(10_000);
            Request rq = read(c.getInputStream());
            if (rq == null) return;
            rq.remote = c.getInetAddress() == null ? "" : c.getInetAddress().getHostAddress();
            keep = route(rq, c);
        } catch (Exception e) {
            if (!closed) Log.w(TAG, "request", e);
        } finally {
            if (!keep) closeQuietly(c);
        }
    }

    /** Reads the request line, headers and body; null if malformed. */
    private static Request read(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int last = 0;
        while (last != 0x0D0A0D0A) {                             // up to the blank line
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            if (head.size() > 16 * 1024) return null;
            last = (last << 8) | b;
        }
        String[] lines = head.toString("ISO-8859-1").split("\r\n");
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
            if (name.equals("content-length")) {
                try { length = Integer.parseInt(value); } catch (NumberFormatException e) { return null; }
            } else if (name.equals("content-type")) {
                rq.contentType = value.toLowerCase(Locale.ROOT);
            } else if (name.equals("if-none-match")) {
                rq.ifNoneMatch = value;
            } else if (name.equals("host")) {
                rq.host = value.toLowerCase(Locale.ROOT);
            } else if (name.equals("authorization")) {
                rq.authorization = value;
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

    /** @return true if the socket is kept (SSE). */
    private boolean route(Request rq, Socket c) throws Exception {
        OutputStream out = c.getOutputStream();
        if (!hostAllowed(rq.host)) {                             // DNS rebinding: only our own names
            write(out, "421 Misdirected Request", null, null, "");
            return false;
        }
        if (!authorized(rq, out)) return false;
        if (!token.isEmpty() && rq.path.equals("/" + token)) {   // missing slash: relative URLs need it
            write(out, "302 Found", null, null, "Location: " + prefix + "\r\n");
            return false;
        }
        if (!rq.path.startsWith(prefix)) {
            write(out, "404 Not Found", null, null, "");
            return false;
        }
        String path = rq.path.substring(prefix.length());
        boolean get = rq.method.equals("GET"), post = rq.method.equals("POST");
        if (post && !rq.contentType.startsWith("application/json")) {
            write(out, "415 Unsupported Media Type", null, null, "");
            return false;
        }
        if (get && path.isEmpty()) {
            asset(out, "index.html");
        } else if (get && path.equals("manifest.webmanifest")) {
            write(out, "200 OK", "application/manifest+json; charset=utf-8",
                    onMain(this::manifest).getBytes(StandardCharsets.UTF_8), "Cache-Control: no-cache\r\n");
        } else if (get && path.startsWith("static/")) {
            asset(out, path.substring(7));
        } else if (get && path.equals("api/app")) {
            json(out, onMain(() -> app.info().json.toString()));
        } else if (get && path.startsWith("api/screen/")) {
            Map<String, String> q = params(rq.query);
            String id = decode(path.substring(11));
            Query query = new Query(q.get("q"), toInt(q.get("offset")), toInt(q.get("limit")));
            String s = onMain(() -> {
                Screen screen = app.screen(id, query);
                return screen == null ? null : screen.json.toString();
            });
            if (s == null) write(out, "404 Not Found", null, null, ""); else json(out, s);
        } else if (post && path.equals("api/set")) {
            JSONObject b = new JSONObject(new String(rq.body, StandardCharsets.UTF_8));
            json(out, change(b, false));
        } else if (post && path.equals("api/action")) {
            JSONObject b = new JSONObject(new String(rq.body, StandardCharsets.UTF_8));
            json(out, change(b, true));
        } else if (post && (path.equals("api/open") || path.equals("api/close"))) {
            JSONObject b = new JSONObject(new String(rq.body, StandardCharsets.UTF_8));
            String client = b.optString("client"), screen = b.optString("screen");
            boolean open = path.equals("api/open");
            onMain(() -> {
                if (open) markOpen(client, screen); else markClosed(client, screen);
                return "";
            });
            json(out, "{\"ok\":true}");
        } else if (get && path.equals("api/events")) {
            return events(c, params(rq.query).get("client"));
        } else if (get && path.startsWith("img/")) {
            image(out, decode(path.substring(4)), rq.ifNoneMatch);
        } else {
            write(out, "404 Not Found", null, null, "");
        }
        return false;
    }

    // ================================================================== Access

    /** Host header: the IP, the .local name, localhost; none (HTTP/1.0 tools) is allowed. */
    private boolean hostAllowed(String host) {
        if (host == null || host.isEmpty()) return true;
        String h = host;
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end < 0 ? h : h.substring(0, end + 1);
        } else {
            int colon = h.indexOf(':');
            if (colon >= 0) h = h.substring(0, colon);
        }
        if (h.equals(ip) || h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]")) return true;
        Mdns m = mdns;
        if (m != null && (h.equals(m.name() + ".local") || h.equals(cleanHostname(options.hostname, "tv") + ".local"))) return true;
        return false;
    }

    /** HTTP Basic, when a password is set; failures from one address pause it (doubling, 60 s at most). */
    private boolean authorized(Request rq, OutputStream out) throws IOException {
        String pw = password;
        if (pw.isEmpty()) return true;
        long now = System.currentTimeMillis();
        synchronized (failures) {
            long[] f = failures.get(rq.remote);
            if (f != null && f[1] > now) {
                write(out, "429 Too Many Requests", null, null, "Retry-After: " + ((f[1] - now) / 1000 + 1) + "\r\n");
                return false;
            }
        }
        String given = null;
        if (rq.authorization != null && rq.authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            try {
                String up = new String(Base64.decode(rq.authorization.substring(6).trim(), Base64.DEFAULT), StandardCharsets.UTF_8);
                int colon = up.indexOf(':');
                given = colon < 0 ? up : up.substring(colon + 1);
            } catch (IllegalArgumentException ignored) { }
        }
        if (given != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), pw.getBytes(StandardCharsets.UTF_8))) {
            synchronized (failures) { failures.remove(rq.remote); }
            return true;
        }
        if (given != null) {                                     // a wrong password (none yet: the first ask)
            synchronized (failures) {
                long[] f = failures.get(rq.remote);
                if (f == null) failures.put(rq.remote, f = new long[2]);
                f[0]++;
                if (f[0] >= FREE_FAILURES) f[1] = now + Math.min(MAX_PAUSE_MS, 1000L << Math.min(16, f[0] - FREE_FAILURES));
            }
        }
        String realm = options.realm == null ? "TV Remote" : options.realm.replace("\"", "");
        write(out, "401 Unauthorized", null, null, "WWW-Authenticate: Basic realm=\"" + realm + "\", charset=\"UTF-8\"\r\n");
        return false;
    }

    /** Web app manifest ("Add to home screen"): the app's name and colour; icons from the skin. */
    private String manifest() throws org.json.JSONException {
        JSONObject info = app.info().json;
        String name = info.optString("app", "TV Remote");
        JSONObject theme = info.optJSONObject("theme");
        String accent = theme == null ? "#000000" : theme.optString("accent", "#000000");
        JSONObject m = new JSONObject();
        m.put("name", name);
        m.put("short_name", name);
        m.put("start_url", ".");
        m.put("scope", ".");
        m.put("display", "standalone");
        m.put("theme_color", accent);
        m.put("background_color", theme == null ? "#000000" : theme.optString("background", "#000000"));
        org.json.JSONArray icons = new org.json.JSONArray();
        for (int size : new int[]{192, 512}) {
            String f = "icon-" + size + ".png";
            try (InputStream in = assets.open(options.assetDir + "/" + f)) {
                icons.put(new JSONObject().put("src", "static/" + f).put("sizes", size + "x" + size).put("type", "image/png"));
            } catch (IOException missing) { /* the app has no icon of this size */ }
        }
        m.put("icons", icons);
        return m.toString();
    }

    /** set or action: runs it on the main thread and answers with the screen, updated. */
    private String change(JSONObject b, boolean action) throws Exception {
        String screen = b.optString("screen"), id = b.optString("id"), by = b.optString("client");
        Object value = b.opt("value");
        Query query = new Query(b.optString("q"), b.optInt("offset"), b.optInt("limit"));
        return onMain(() -> {
            Result r;
            try {
                r = action ? app.action(screen, id) : app.set(screen, id, value == JSONObject.NULL ? null : value);
            } catch (RuntimeException e) {
                Log.w(TAG, (action ? "action " : "set ") + screen + " " + id, e);
                r = Result.error(e.toString(), "exception");
            }
            if (r == null) r = Result.ok();
            JSONObject o = new JSONObject();
            Item.put(o, "ok", r.ok);
            if (r.error != null) Item.put(o, "error", r.error);
            if (r.code != null) Item.put(o, "code", r.code);
            if (r.open != null) {
                Item.put(o, "open", r.open);
            } else {
                Screen s = app.screen(screen, query);
                if (s != null) Item.put(o, "screen", s.json);
            }
            if (r.ok) changed(screen, by);
            return o.toString();
        });
    }

    private void asset(OutputStream out, String path) throws IOException {
        if (path.isEmpty() || path.contains("..") || path.startsWith("/") || path.contains("\\")) {
            write(out, "404 Not Found", null, null, "");
            return;
        }
        byte[] data;
        try (InputStream in = assets.open(options.assetDir + "/" + decode(path))) {
            data = readAll(in);
        } catch (IOException missing) {
            write(out, "404 Not Found", null, null, "");
            return;
        }
        write(out, "200 OK", mime(path), data, "Cache-Control: no-cache\r\n");
    }

    private void image(OutputStream out, String key, String ifNoneMatch) throws IOException {
        Image img;
        try {
            img = app.image(key);
        } catch (RuntimeException e) {
            Log.w(TAG, "image " + key, e);
            img = null;
        }
        if (img == null || img.data == null) {
            write(out, "404 Not Found", null, null, "");
            return;
        }
        String etag = img.etag == null ? null : "\"" + img.etag.replace("\"", "") + "\"";
        String headers = "Cache-Control: max-age=86400\r\n" + (etag == null ? "" : "ETag: " + etag + "\r\n");
        if (etag != null && etag.equals(ifNoneMatch)) {
            write(out, "304 Not Modified", null, null, headers);
        } else {
            write(out, "200 OK", img.mime, img.data, headers);
        }
    }

    private static void json(OutputStream out, String body) throws IOException {
        write(out, "200 OK", "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8),
                "Cache-Control: no-store\r\n");
    }

    private static void write(OutputStream out, String status, String type, byte[] body, String headers)
            throws IOException {
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("\r\n");
        h.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n")
                .append("X-Content-Type-Options: nosniff\r\n")
                .append(headers).append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body != null) out.write(body);
        out.flush();
    }

    // ================================================================== Main thread

    /** Runs {@code task} on the main thread and waits for it (5 s at most). */
    private <T> T onMain(Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.call();
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                result[0] = task.call();
            } catch (Exception e) {
                error[0] = e;
            } finally {
                done.countDown();
            }
        });
        if (!done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw new IOException("main thread busy");
        if (error[0] != null) throw error[0];
        @SuppressWarnings("unchecked") T t = (T) result[0];
        return t;
    }

    private void markOpen(String client, String screen) {
        if (screen.isEmpty()) return;
        Set<String> set = openedBy.get(client);
        if (set == null) openedBy.put(client, set = new HashSet<>());
        if (!set.add(screen)) return;
        Integer n = openCount.get(screen);
        openCount.put(screen, n == null ? 1 : n + 1);
        if (n == null) safe(() -> app.opened(screen));
    }

    private void markClosed(String client, String screen) {
        Set<String> set = openedBy.get(client);
        if (set == null || !set.remove(screen)) return;
        if (set.isEmpty()) openedBy.remove(client);
        Integer n = openCount.get(screen);
        if (n == null || n <= 1) {
            openCount.remove(screen);
            safe(() -> app.closed(screen));
        } else {
            openCount.put(screen, n - 1);
        }
    }

    /** A phone went away: the screens it showed are closed. */
    private void clientGone(String client) {
        main.post(() -> {
            Set<String> set = openedBy.get(client);
            if (set == null) return;
            for (String screen : new ArrayList<>(set)) markClosed(client, screen);
        });
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Log.w(TAG, "app", e);
        }
    }

    // ================================================================== Server-Sent Events

    private static final class Client {
        final String id;
        final Socket socket;
        final OutputStream out;
        boolean dead;

        Client(String id, Socket socket) throws IOException {
            this.id = id;
            this.socket = socket;
            this.out = socket.getOutputStream();
        }

        void send(String event, String data) {
            write((event == null ? "" : "event: " + event + "\n") + "data: " + data + "\n\n");
        }

        void write(String s) {
            if (dead) return;
            try {
                out.write(s.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                dead = true;
            }
        }

        void close() {
            dead = true;
            closeQuietly(socket);
        }
    }

    private boolean events(Socket c, String clientId) throws IOException {
        String id = clientId == null || clientId.isEmpty() ? "anon-" + System.nanoTime() : clientId;
        c.setSoTimeout(0);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write("retry: 3000\n\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        Client client = new Client(id, c);
        try {
            sse.execute(() -> {
                // Same phone reconnecting: the old connection goes, its screens stay open
                for (Iterator<Client> it = clients.iterator(); it.hasNext(); ) {
                    Client old = it.next();
                    if (old.id.equals(id)) { old.close(); it.remove(); }
                }
                while (clients.size() >= MAX_SSE) {
                    Client oldest = clients.remove(0);
                    oldest.send("bye", "{}");
                    oldest.close();
                    clientGone(oldest.id);
                }
                clients.add(client);
                if (state != null) client.send("state", state);
            });
        } catch (RejectedExecutionException stopped) {
            return false;
        }
        return true;
    }

    private void broadcast(String event, String data) {
        if (closed) return;
        try {
            sse.execute(() -> {
                for (Client c : clients) c.send(event, data);
                prune();
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private void ping() {
        for (Client c : clients) c.write(": ping\n\n");
        prune();
    }

    private void prune() {
        for (Iterator<Client> it = clients.iterator(); it.hasNext(); ) {
            Client c = it.next();
            if (c.dead) {
                c.close();
                it.remove();
                clientGone(c.id);
            }
        }
    }

    // ================================================================== Helpers

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static Map<String, String> params(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq < 0) m.put(decode(kv), "");
            else m.put(decode(kv.substring(0, eq)), decode(kv.substring(eq + 1).replace('+', ' ')));
        }
        return m;
    }

    private static int toInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String mime(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json")) return "application/json; charset=utf-8";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }
}
