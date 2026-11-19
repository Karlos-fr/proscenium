// TV Remote core 0.2.1 (v0.2.1 44d37f1) - copied by tvremote\sync.ps1, do not edit here.
package fr.tvremote.core;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

/**
 * The phones' live connections (Server-Sent Events): at most {@link #MAX} (the oldest goes), a
 * ping every 20 s to notice phones gone to sleep, the last state sent to each new phone. Everything
 * runs on one thread, {@code thread}; {@code gone} learns the phones that left.
 */
final class Sse {
    static final int MAX = 4;

    private final ScheduledExecutorService thread;
    private final Consumer<String> gone;
    private final List<Client> clients = new ArrayList<>();          // oldest first; this.thread only
    private volatile String state;                                    // last state, sent to new clients

    Sse(ScheduledExecutorService thread, Consumer<String> gone) {
        this.thread = thread;
        this.gone = gone;
    }

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
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Takes over socket {@code c} for phone {@code id}; false if stopped (the caller closes it). */
    boolean open(Socket c, String id) throws IOException {
        c.setSoTimeout(0);
        OutputStream out = c.getOutputStream();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nConnection: keep-alive\r\n\r\n"
                + "retry: 3000\n\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        Client client = new Client(id, c);
        return run(() -> {
            // Same phone reconnecting: the old connection goes, its screens stay open
            for (Iterator<Client> it = clients.iterator(); it.hasNext(); ) {
                Client old = it.next();
                if (old.id.equals(id)) { old.close(); it.remove(); }
            }
            while (clients.size() >= MAX) {
                Client oldest = clients.remove(0);
                oldest.send("bye", "{}");
                oldest.close();
                gone.accept(oldest.id);
            }
            clients.add(client);
            String s = state;
            if (s != null) client.send("state", s);
        });
    }

    void broadcast(String event, String data) {
        run(() -> {
            for (Client c : clients) c.send(event, data);
            prune();
        });
    }

    /** Live state of the app, to every phone now and to each new one. */
    void state(String json) {
        state = json;
        broadcast("state", json);
    }

    void ping() {
        run(() -> {
            for (Client c : clients) c.write(": ping\n\n");
            prune();
        });
    }

    /** Says goodbye to every phone and closes the connections. */
    void closeAll() {
        run(() -> {
            for (Client c : clients) {
                c.send("bye", "{}");
                c.close();
            }
            clients.clear();
        });
    }

    private void prune() {
        for (Iterator<Client> it = clients.iterator(); it.hasNext(); ) {
            Client c = it.next();
            if (c.dead) {
                c.close();
                it.remove();
                gone.accept(c.id);
            }
        }
    }

    private boolean run(Runnable r) {
        try {
            thread.execute(r);
            return true;
        } catch (RejectedExecutionException stopped) {
            return false;
        }
    }
}
