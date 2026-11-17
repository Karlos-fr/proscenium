package fr.proscenium.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;

/**
 * Saisie de la clé TMDB depuis le téléphone : un tout petit serveur web sur le réseau local,
 * ouvert le temps de la saisie (10 minutes au plus). La TV affiche un QR code vers
 * http://<adresse de la TV>:<port>/<jeton> ; la page reçue sur le téléphone permet de coller
 * la clé, que la TV vérifie auprès de TMDB avant de l'enregistrer.
 * Le jeton aléatoire empêche un autre appareil du réseau de deviner l'adresse.
 */
final class KeyServer {
    private static final String TAG = "Proscenium";
    private static final int PORT = 8765;
    private static final long LIFETIME_MS = 10 * 60_000L;

    interface Listener { void received(String key); }

    private static KeyServer current;

    private final Context ctx;
    private final Listener listener;
    private final String token;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ServerSocket socket;
    private volatile boolean done;
    String url;

    private KeyServer(Context ctx, Listener listener) {
        // Contexte de l'activité : ses textes suivent la langue choisie dans l'appli
        this.ctx = ctx;
        this.listener = listener;
        byte[] r = new byte[6];
        new SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(String.format("%02x", b & 0xFF));
        token = sb.toString();
    }

    /** Ouvre le serveur (en remplaçant le précédent) ; null si la TV n'a pas d'adresse sur le réseau local. */
    static synchronized KeyServer start(Context ctx, Listener listener) {
        stop();
        String ip = localAddress();
        if (ip == null) return null;
        KeyServer s = new KeyServer(ctx, listener);
        try {
            try {
                s.socket = new ServerSocket(PORT);
            } catch (Exception busy) {
                s.socket = new ServerSocket(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Serveur de clé impossible", e);
            return null;
        }
        s.url = "http://" + ip + ":" + s.socket.getLocalPort() + "/" + s.token;
        current = s;
        Thread t = new Thread(s::serve, "key-server");
        t.setDaemon(true);
        t.start();
        s.ui.postDelayed(s::close, LIFETIME_MS);
        return s;
    }

    static synchronized void stop() {
        if (current != null) current.close();
        current = null;
    }

    private void close() {
        done = true;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) { }
    }

    /** Première adresse IPv4 du réseau local (Wi-Fi ou Ethernet). */
    static String localAddress() {
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

    private void serve() {
        while (!done) {
            try (Socket c = socket.accept()) {
                c.setSoTimeout(10_000);
                handle(c);
            } catch (Exception e) {
                if (!done) Log.w(TAG, "Serveur de clé", e);
            }
        }
    }

    private void handle(Socket c) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        String request = in.readLine();
        if (request == null) return;
        String[] parts = request.split(" ");
        int length = 0;
        for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
            if (h.toLowerCase().startsWith("content-length:")) length = Integer.parseInt(h.substring(15).trim());
        }
        OutputStream out = c.getOutputStream();
        if (parts.length >= 2 && parts[1].equals("/logo.png")) {
            // Bannière de l'appli, en tête de page (même fond bleu nuit que la page)
            try (java.io.InputStream logo = ctx.getResources().openRawResource(R.raw.web_banner)) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = logo.read(buf)) > 0; ) bytes.write(buf, 0, n);
                send(out, "200 OK", "image/png", bytes.toByteArray());
            }
            return;
        }
        if (parts.length < 2 || !parts[1].equals("/" + token)) {
            respond(out, "404 Not Found", page(ctx.getString(R.string.key_page_expired), false, null));
            return;
        }
        if (parts[0].equals("POST")) {
            char[] body = new char[Math.min(length, 4096)];
            int read = 0;
            while (read < body.length) {
                int n = in.read(body, read, body.length - read);
                if (n < 0) break;
                read += n;
            }
            String key = "";
            for (String field : new String(body, 0, read).split("&")) {
                if (field.startsWith("key=")) key = URLDecoder.decode(field.substring(4), "UTF-8").replaceAll("\\s", "");
            }
            boolean ok = !key.isEmpty() && new Tmdb(key, "en-US").test();
            if (ok) {
                String k = key;
                done = true;
                ui.post(() -> listener.received(k));
                respond(out, "200 OK", page(ctx.getString(R.string.key_page_ok), false, null));
                close();
            } else {
                respond(out, "200 OK", page(ctx.getString(R.string.key_page_bad), true, "#ff8a80"));
            }
            return;
        }
        respond(out, "200 OK", page(ctx.getString(R.string.key_page_hint), true, null));
    }

    /** Page du téléphone : un message, et le champ de saisie si {@code form}. */
    private String page(String message, boolean form, String color) {
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Proscenium</title><style>")
                .append("body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;")
                .append("background:#000820;color:#eef;font:16px/1.5 system-ui,sans-serif}")
                .append("main{width:min(420px,100% - 32px)}")
                .append("input{box-sizing:border-box;width:100%;padding:12px;border-radius:10px;border:1px solid #445;")
                .append("background:#0d1430;color:#fff;font:15px monospace}")
                .append("button{margin-top:12px;width:100%;padding:12px;border:0;border-radius:10px;")
                .append("background:#5b8cff;color:#fff;font-size:16px}p{opacity:.85}")
                .append("img{display:block;width:100%;max-width:360px;margin:0 auto 8px}")
                .append("</style></head><body><main><img src=\"/logo.png\" alt=\"Proscenium\"><p")
                .append(color == null ? "" : " style=\"color:" + color + "\"").append(">")
                .append(escape(message)).append("</p>");
        if (form) {
            h.append("<form method=\"post\" action=\"/").append(token).append("\">")
                    .append("<input name=\"key\" autocomplete=\"off\" autocapitalize=\"off\" spellcheck=\"false\" ")
                    .append("placeholder=\"").append(escape(ctx.getString(R.string.prompt_tmdb_key))).append("\">")
                    .append("<button>").append(escape(ctx.getString(R.string.key_page_send))).append("</button></form>");
        }
        return h.append("</main></body></html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void respond(OutputStream out, String status, String html) throws Exception {
        send(out, status, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, String status, String type, byte[] body) throws Exception {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: "
                + body.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}
