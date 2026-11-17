package fr.proscenium.launcher;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Petites requêtes HTTP GET, appelées hors du fil principal. */
final class Net {
    private Net() {}

    static byte[] get(String url) throws IOException {
        return get(url, null);
    }

    /** {@code bearer} : jeton d'accès envoyé dans l'en-tête Authorization, ou null. */
    static byte[] get(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(20_000);
        c.setRequestProperty("User-Agent", "Proscenium (Android TV)");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode() + " " + url.replaceAll("api_key=[^&]+", "api_key=…"));
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    static String getText(String url) throws IOException {
        return getText(url, null);
    }

    static String getText(String url, String bearer) throws IOException {
        return new String(get(url, bearer), StandardCharsets.UTF_8);
    }
}
