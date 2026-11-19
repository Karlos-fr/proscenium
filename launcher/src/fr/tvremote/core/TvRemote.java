// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web remote served by the TV on the local network (format v1, see FORMAT.md).
 *
 * <pre>
 * TvRemote remote = TvRemote.start(context, app, new TvRemote.Options().hostname("proscenium"));
 * remote.url();                 // http://192.168.1.20:8770/  → QR code (with Access.TOKEN: …/3f…/)
 * remote.localName();           // http://proscenium.local:8770/
 * remote.changed("weather");    // a setting changed on the TV: the phones refresh this screen
 * remote.state(json);           // live state (now playing…), sent to every phone
 * remote.stop();
 * </pre>
 *
 * Every call to {@link App} is made on the main thread (the core waits up to 5 s), except
 * {@link App#image}, made on a server thread so that pictures can be read or encoded there.
 * The pieces: {@link Http} (requests), {@link Guard} (names, password), {@link Sse} (live
 * connections), {@link Mdns} (the .local name), {@link Net} (the TV's address).
 */
public final class TvRemote {
    public static final int FORMAT = 1;
    public static final String VERSION = "0.2.1";
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

    /** How the page is reached. */
    public enum Access {
        /** Fixed address http://&lt;ip or name.local&gt;:&lt;port&gt;/, no token; the port is fixed. */
        OPEN,
        /** http://&lt;ip&gt;:&lt;port&gt;/&lt;random token&gt;/, new at each start; another port if busy. */
        TOKEN
    }

    /** @deprecated use {@link Access#OPEN}. */
    @Deprecated public static final String ACCESS_OPEN = "open";
    /** @deprecated use {@link Access#TOKEN}. */
    @Deprecated public static final String ACCESS_TOKEN = "token";

    /** Settings of {@link #start}, chained: {@code new Options().port(8770).hostname("tv")…}. */
    public static final class Options {
        int port = 8770;
        long lifetimeMs = 0;
        String assetDir = "tvremote";
        Access access = Access.OPEN;
        String hostname = "tv";
        String password = "";
        String realm = "TV Remote";
        Runnable onAddressChanged;

        /** Port; fixed with {@link Access#OPEN} (busy: {@link #start} fails, see {@link #lastError()}). */
        public Options port(int p) { port = p; return this; }

        /** 0: while the app runs (ALWAYS); otherwise the server stops after this many ms (WINDOW). */
        public Options lifetime(long ms) { lifetimeMs = ms; return this; }

        /** Folder of the page in the app's assets: index.html, core.js, the skin, icon-192/512.png. */
        public Options assetDir(String d) { assetDir = d; return this; }

        public Options access(Access a) { access = a == null ? Access.OPEN : a; return this; }

        /** @deprecated use {@link #access(Access)}. */
        @Deprecated
        public Options access(String a) { return access(ACCESS_TOKEN.equals(a) ? Access.TOKEN : Access.OPEN); }

        /** Name on the local network, without ".local" (cleaned with {@link #cleanHostname}); null: no mDNS. */
        public Options hostname(String h) { hostname = h; return this; }

        /** HTTP Basic password (any user name); empty: none. Can change later: {@link TvRemote#setPassword}. */
        public Options password(String p) { password = p == null ? "" : p; return this; }

        /** Shown by the browser's login box: the app's name. */
        public Options realm(String r) { realm = r == null ? "TV Remote" : r.replace("\"", ""); return this; }

        /** Runs on the main thread when the address or the .local name changed (new QR code). */
        public Options onAddressChanged(Runnable r) { onAddressChanged = r; return this; }
    }

    /** A valid .local name made from {@code name} (lower case, a-z 0-9 -, 1 to 63), or {@code fallback}. */
    public static String cleanHostname(String name, String fallback) {
        String h = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        boolean ok = h.length() >= 1 && h.length() <= 63 && h.matches("[a-z0-9-]+") && !h.startsWith("-") && !h.endsWith("-");
        return ok ? h : fallback;
    }

    private static volatile String lastError;

    /** Why the last {@link #start} returned null ("no local network", "port 8770 busy"…), or null. */
    public static String lastError() { return lastError; }

    /** Private IPv4 address of the TV on the local network (Ethernet or Wi-Fi first), or null. */
    public static String localAddress() { return Net.localAddress(); }

    private static final long MAIN_TIMEOUT_MS = 5000, MAIN_LATE_MS = 10_000, PING_MS = 20_000, NET_CHECK_MS = 10_000;

    private final Context ctx;
    private final AssetManager assets;
    private final App app;
    private final Options options;
    private final String token, prefix;
    private final Guard guard;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(16), r -> daemon(r, "tvremote-http"));
    private final ScheduledExecutorService sseThread = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "tvremote-sse"));
    private final Sse sse = new Sse(sseThread, this::clientGone);
    private final Map<String, Set<String>> openedBy = new HashMap<>(); // client → screens it shows; main thread only
    private final Map<String, Integer> openCount = new HashMap<>();    // screen → number of clients; main thread only
    private ServerSocket socket;
    private volatile Mdns mdns;
    private volatile boolean closed;
    private volatile String ip, url;

    private TvRemote(Context ctx, App app, Options options) {
        this.ctx = ctx.getApplicationContext();
        this.assets = this.ctx.getAssets();
        this.app = app;
        this.options = options;
        this.guard = new Guard(options.password);
        if (options.access == Access.TOKEN) {
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
     * or, with {@link Access#OPEN}, if the port is busy: see {@link #lastError()}.
     */
    public static TvRemote start(Context ctx, App app, Options options) {
        Options o = options == null ? new Options() : options;
        lastError = null;
        String ip = Net.localAddress();
        if (ip == null) {
            lastError = "no local network";
            return null;
        }
        TvRemote s = new TvRemote(ctx, app, o);
        try {
            try {
                s.socket = new ServerSocket(o.port);
            } catch (IOException busy) {
                if (o.access == Access.OPEN) {
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
        daemon(s::accept, "tvremote-accept").start();
        s.sseThread.scheduleAtFixedRate(s.sse::ping, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
        s.sseThread.scheduleAtFixedRate(s::checkNetwork, NET_CHECK_MS, NET_CHECK_MS, TimeUnit.MILLISECONDS);
        if (o.lifetimeMs > 0) s.main.postDelayed(s::stop, o.lifetimeMs);
        s.startMdns(ip);
        return s;
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

    public boolean running() { return !closed; }

    /** Changes the password at once (empty: none). The access mode or the name need a new start. */
    public void setPassword(String p) { guard.setPassword(p); }

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

    /** Stops the server; the phones are told ("bye") and the .local name is withdrawn. */
    public void stop() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        Mdns m = mdns;
        if (m != null) m.stop();
        try { socket.close(); } catch (IOException ignored) { }
        sse.closeAll();
        sseThread.shutdown();
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
        if (closed) return;
        sse.broadcast("changed", "{\"screen\":" + JSONObject.quote(screen == null ? "*" : screen)
                + ",\"by\":" + JSONObject.quote(by == null ? "" : by) + "}");
    }

    /** Live state of the app, sent to every phone now and to each new one. */
    public void state(JSONObject json) {
        if (!closed) sse.state(json == null ? "{}" : json.toString());
    }

    // ================================================================== Address and name

    private void setIp(String newIp) {
        ip = newIp;
        url = "http://" + newIp + ":" + socket.getLocalPort() + prefix;
    }

    private void startMdns(String forIp) {
        if (options.hostname == null) return;
        Mdns m = new Mdns(ctx, cleanHostname(options.hostname, "tv"), forIp, name -> addressChanged());
        mdns = m;
        m.start();
    }

    /**
     * The TV changed address (new DHCP lease, Wi-Fi ↔ Ethernet): new URL, and a new responder,
     * listening on the new interface. sse thread.
     */
    private void checkNetwork() {
        String now = Net.localAddress();
        if (closed || now == null || now.equals(ip)) return;
        Log.i(TAG, "Address " + ip + " -> " + now);
        setIp(now);
        Mdns old = mdns;
        if (old != null) old.stop();
        startMdns(now);
        addressChanged();
    }

    private void addressChanged() {
        Runnable r = options.onAddressChanged;
        if (r != null && !closed) main.post(() -> safe(r));
    }

    /** Names the Host header may carry: the IP, the .local names, localhost. */
    private Set<String> names() {
        Set<String> n = new HashSet<>();
        n.add(ip);
        n.add("localhost");
        n.add("127.0.0.1");
        n.add("[::1]");
        if (options.hostname != null) n.add(cleanHostname(options.hostname, "tv") + ".local");
        Mdns m = mdns;
        if (m != null) n.add(m.name() + ".local");
        return n;
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

    private void handle(Socket c) {
        boolean keep = false;
        try {
            c.setSoTimeout(10_000);
            Http.Request rq = Http.read(new BufferedInputStream(c.getInputStream(), 8192));
            if (rq == null) return;
            rq.remote = c.getInetAddress() == null ? "" : c.getInetAddress().getHostAddress();
            keep = route(rq, c);
        } catch (Exception e) {
            if (!closed) Log.w(TAG, "request", e);
        } finally {
            if (!keep) closeQuietly(c);
        }
    }

    /** @return true if the socket is kept (live connection). */
    private boolean route(Http.Request rq, Socket c) throws Exception {
        OutputStream out = c.getOutputStream();
        if (!Guard.hostAllowed(rq.host, names())) {             // DNS rebinding: only our own names
            Http.status(out, "421 Misdirected Request");
            return false;
        }
        long now = System.currentTimeMillis();
        switch (guard.check(rq.remote, rq.authorization, now)) {
            case PAUSED:
                Http.write(out, "429 Too Many Requests", null, null,
                        "Retry-After: " + guard.retryAfterSeconds(rq.remote, now) + "\r\n");
                return false;
            case ASK_PASSWORD:
                Http.write(out, "401 Unauthorized", null, null,
                        "WWW-Authenticate: Basic realm=\"" + options.realm + "\", charset=\"UTF-8\"\r\n");
                return false;
            default:
                break;
        }
        if (!token.isEmpty() && rq.path.equals("/" + token)) {   // missing slash: relative URLs need it
            Http.write(out, "302 Found", null, null, "Location: " + prefix + "\r\n");
            return false;
        }
        if (!rq.path.startsWith(prefix)) {
            Http.status(out, "404 Not Found");
            return false;
        }
        String path = rq.path.substring(prefix.length());
        if (rq.post() && !rq.contentType.startsWith("application/json")) {
            Http.status(out, "415 Unsupported Media Type");
            return false;
        }
        try {
            if (rq.get() && path.isEmpty()) {
                asset(out, "index.html");
            } else if (rq.get() && path.equals("manifest.webmanifest")) {
                Http.write(out, "200 OK", Http.mime(path), onMain(this::manifest).getBytes(StandardCharsets.UTF_8),
                        "Cache-Control: no-cache\r\n");
            } else if (rq.get() && path.startsWith("static/")) {
                asset(out, path.substring(7));
            } else if (rq.get() && path.equals("api/app")) {
                Http.json(out, onMain(() -> app.info().json.toString()));
            } else if (rq.get() && path.startsWith("api/screen/")) {
                Map<String, String> q = Http.params(rq.query);
                String id = Http.decode(path.substring(11));
                Query query = new Query(q.get("q"), Http.toInt(q.get("offset")), Http.toInt(q.get("limit")));
                String s = onMain(() -> {
                    Screen screen = app.screen(id, query);
                    return screen == null ? null : screen.json.toString();
                });
                if (s == null) Http.status(out, "404 Not Found"); else Http.json(out, s);
            } else if (rq.post() && (path.equals("api/set") || path.equals("api/action"))) {
                JSONObject b = new JSONObject(new String(rq.body, StandardCharsets.UTF_8));
                Http.json(out, change(b, path.equals("api/action")));
            } else if (rq.post() && (path.equals("api/open") || path.equals("api/close"))) {
                JSONObject b = new JSONObject(new String(rq.body, StandardCharsets.UTF_8));
                String client = b.optString("client"), screen = b.optString("screen");
                boolean open = path.equals("api/open");
                onMain(() -> {
                    if (open) markOpen(client, screen); else markClosed(client, screen);
                    return "";
                });
                Http.json(out, "{\"ok\":true}");
            } else if (rq.get() && path.equals("api/events")) {
                String id = Http.params(rq.query).get("client");
                return sse.open(c, id == null || id.isEmpty() ? "anon-" + System.nanoTime() : id);
            } else if (rq.get() && path.startsWith("img/")) {
                image(out, Http.decode(path.substring(4)), rq.ifNoneMatch);
            } else {
                Http.status(out, "404 Not Found");
            }
        } catch (Busy busy) {
            // The TV's main thread did not take the task in time (it was dropped): try again
            Http.write(out, "503 Service Unavailable", "application/json; charset=utf-8",
                    "{\"ok\":false,\"code\":\"busy\"}".getBytes(StandardCharsets.UTF_8), "Retry-After: 2\r\n");
        }
        return false;
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

    private void asset(OutputStream out, String raw) throws IOException {
        String path = Http.assetPath(raw);                       // decoded first, then checked
        if (path == null) {
            Http.status(out, "404 Not Found");
            return;
        }
        byte[] data;
        try (InputStream in = assets.open(options.assetDir + "/" + path)) {
            data = Http.readAll(in);
        } catch (IOException missing) {
            Http.status(out, "404 Not Found");
            return;
        }
        Http.write(out, "200 OK", Http.mime(path), data, "Cache-Control: no-cache\r\n");
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
            Http.status(out, "404 Not Found");
            return;
        }
        String etag = img.etag == null ? null : "\"" + img.etag.replace("\"", "") + "\"";
        String headers = "Cache-Control: max-age=86400\r\n" + (etag == null ? "" : "ETag: " + etag + "\r\n");
        if (etag != null && etag.equals(ifNoneMatch)) {
            Http.write(out, "304 Not Modified", null, null, headers);
        } else {
            Http.write(out, "200 OK", img.mime, img.data, headers);
        }
    }

    /** Web app manifest ("Add to home screen"): the app's name and colours; icons from the page folder. */
    private String manifest() throws JSONException {
        JSONObject info = app.info().json;
        String name = info.optString("app", "TV Remote");
        JSONObject theme = info.optJSONObject("theme");
        JSONObject m = new JSONObject();
        m.put("name", name);
        m.put("short_name", name);
        m.put("start_url", ".");
        m.put("scope", ".");
        m.put("display", "standalone");
        m.put("theme_color", theme == null ? "#000000" : theme.optString("accent", "#000000"));
        m.put("background_color", theme == null ? "#000000" : theme.optString("background", "#000000"));
        JSONArray icons = new JSONArray();
        for (int size : new int[]{192, 512}) {
            String f = "icon-" + size + ".png";
            try (InputStream in = assets.open(options.assetDir + "/" + f)) {
                icons.put(new JSONObject().put("src", "static/" + f).put("sizes", size + "x" + size).put("type", "image/png"));
            } catch (IOException missing) { /* no icon of this size */ }
        }
        m.put("icons", icons);
        return m.toString();
    }

    // ================================================================== Main thread

    /** The main thread did not take the task in time; it was dropped. */
    private static final class Busy extends IOException {
        Busy() { super("main thread busy"); }
    }

    /**
     * Runs {@code task} on the main thread and waits for it. Not started within 5 s: dropped, so
     * that it does not run later behind the phone's back ({@link Busy}); started: waited for.
     */
    private <T> T onMain(Callable<T> task) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return task.call();
        Object[] result = new Object[1];
        Exception[] error = new Exception[1];
        CountDownLatch done = new CountDownLatch(1);
        AtomicInteger stage = new AtomicInteger();                // 0 waiting, 1 started, 2 dropped
        main.post(() -> {
            if (!stage.compareAndSet(0, 1)) return;
            try {
                result[0] = task.call();
            } catch (Exception e) {
                error[0] = e;
            } finally {
                done.countDown();
            }
        });
        if (!done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            if (stage.compareAndSet(0, 2)) throw new Busy();
            if (!done.await(MAIN_LATE_MS, TimeUnit.MILLISECONDS)) throw new Busy();
        }
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

    // ================================================================== Helpers

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }
}
