package tv.inicio.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Servidor mínimo en la red de casa: desde el móvil se abre http://IP:8765/PIN y se pegan
 * enlaces de AceStream para reproducirlos o guardarlos en la tele. Solo acepta IDs; no busca nada.
 */
public class PhoneServer {
    static final int PORT = 8765;
    static volatile Thread thread;
    static Context app;
    static final Handler main = new Handler(Looper.getMainLooper());

    static synchronized void ensure(Context c) {
        if (AceLinks.pkg(c) == null) return;
        app = c.getApplicationContext();
        if (thread != null && thread.isAlive()) return;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, "inicio-movil");
        thread.setDaemon(true);
        thread.start();
    }

    static String ip() {
        try {
            List<NetworkInterface> l = Collections.list(NetworkInterface.getNetworkInterfaces());
            String other = null;
            for (NetworkInterface n : l) {
                if (!n.isUp() || n.isLoopback()) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String h = a.getHostAddress();
                    if (n.getName().startsWith("wlan") || n.getName().startsWith("eth")) return h;
                    if (other == null && !n.getName().startsWith("tun")) other = h;
                }
            }
            return other;
        } catch (Throwable t) { return null; }
    }

    static String address(Context c) {
        String ip = ip();
        return (ip == null ? "?" : ip) + ":" + PORT + "/" + AceLinks.pin(c);
    }

    static void loop() {
        while (true) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(PORT));
                while (true) {
                    final Socket s = ss.accept();
                    try { handle(s); } catch (Throwable t) { }
                    try { s.close(); } catch (Throwable t) { }
                }
            } catch (Throwable t) {
                try { if (ss != null) ss.close(); } catch (Throwable t2) { }
                try { Thread.sleep(5000); } catch (Throwable t3) { }
            }
        }
    }

    static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new Exception("línea larga");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        if (body == null) return m;
        for (String kv : body.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            try { m.put(URLDecoder.decode(kv.substring(0, e), "UTF-8"), URLDecoder.decode(kv.substring(e + 1), "UTF-8")); }
            catch (Throwable t) { }
        }
        return m;
    }

    static void handle(Socket s) throws Exception {
        s.setSoTimeout(8000);
        InputStream in = s.getInputStream();
        String req = readLine(in);
        if (req == null) return;
        String[] parts = req.split(" ");
        if (parts.length < 2) return;
        String method = parts[0], path = parts[1];
        int len = 0;
        String h;
        while ((h = readLine(in)) != null && !h.isEmpty()) {
            if (h.toLowerCase().startsWith("content-length:")) {
                try { len = Integer.parseInt(h.substring(15).trim()); } catch (Throwable t) { }
            }
        }
        if (len > 16384) len = 16384;
        byte[] body = new byte[Math.max(0, len)];
        int got = 0;
        while (got < len) { int r = in.read(body, got, len - got); if (r < 0) break; got += r; }
        OutputStream out = s.getOutputStream();

        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        String pin = AceLinks.pin(app);
        String base = "/" + pin;
        if (!(path.equals(base) || path.equals(base + "/") || path.startsWith(base + "/api/"))) {
            send(out, 404, "text/html; charset=utf-8", "<meta name=viewport content='width=device-width'>"
                    + "<body style='font-family:sans-serif;background:#1C0C1E;color:#FFF6F1;padding:24px'>"
                    + "Dirección incompleta. Usa la que aparece en la tele, en AceStream.</body>");
            return;
        }
        if (method.equals("GET") && !path.startsWith(base + "/api/")) {
            send(out, 200, "text/html; charset=utf-8", page());
            return;
        }
        Map<String, String> f = form(new String(body, 0, got, "UTF-8"));
        String api = path.substring((base + "/api/").length());
        JSONObject res = new JSONObject();
        if (api.equals("list")) {
            res.put("ok", true);
        } else if (api.equals("play") || api.equals("save")) {
            final String id = AceLinks.parse(f.get("link"));
            // Tipo de ID: lo dice el enlace (infohash=, magnet) o, si es un ID suelto o acestream://,
            // se mira en las listas del buscador (solo contienen infohashes). Este hilo no es el
            // principal, así que puede esperar a que se descarguen si la tele aún no las tiene.
            String t0 = AceLinks.parseType(f.get("link"));
            if (id != null && t0.isEmpty() && AceSearcher.knownInfohashBlocking(app, id, 12000)) t0 = AceLinks.INFOHASH;
            final String type = t0;
            if (id == null) {
                res.put("ok", false).put("msg", "No veo un ID de AceStream (40 caracteres) en lo que has pegado.");
            } else {
                String name = f.get("name");
                boolean store = api.equals("save") || "1".equals(f.get("store"));
                final String n = store ? AceLinks.save(app, name, id, type) : AceLinks.cleanName(name, id);
                if (api.equals("play")) {
                    final boolean[] ok = {false};
                    final Object lock = new Object();
                    main.post(new Runnable() { public void run() {
                        ok[0] = AceLinks.play(app, id, type);
                        synchronized (lock) { lock.notifyAll(); }
                    } });
                    synchronized (lock) { lock.wait(3000); }
                    boolean vpn = AceLinks.vpnOn(app);
                    // Proton VPN gratis bloquea el P2P de AceStream: avisar si está puesta
                    if (vpn) toast("La VPN está conectada: Proton gratis bloquea AceStream. Desconéctala si no carga");
                    res.put("ok", ok[0]).put("msg", !ok[0] ? "No se pudo abrir AceStream en la tele"
                            : !vpn ? "Abriendo en la tele: " + n
                            : "Abriendo, pero la VPN de la tele está conectada: Proton gratis bloquea AceStream, desconéctala si no carga");
                } else {
                    toast("Guardado en AceStream: " + n);
                    res.put("ok", true).put("msg", "Guardado: " + n);
                }
            }
        } else if (api.equals("del")) {
            boolean ok = AceLinks.remove(app, String.valueOf(f.get("id")));
            res.put("ok", ok).put("msg", ok ? "Borrado" : "No estaba guardado");
        } else if (api.equals("feed")) {
            String u = f.get("url") == null ? "" : f.get("url").trim();
            if (!AceSearcher.validUrl(u)) {
                res.put("ok", false).put("msg", "La dirección tiene que empezar por http:// o https://");
            } else {
                AceSearcher.setFeedUrl(app, u);
                if (!u.isEmpty()) toast("Lista M3U guardada. Ábrela desde AceStream en Inicio.");
                res.put("ok", true).put("msg", u.isEmpty() ? "Lista M3U quitada" : "Lista M3U guardada");
            }
        } else if (api.equals("up")) {
            AceLinks.moveUp(app, String.valueOf(f.get("id")));
            res.put("ok", true);
        } else {
            send(out, 404, "application/json", "{\"ok\":false}");
            return;
        }
        JSONArray a = new JSONArray();
        for (String[] x : AceLinks.list(app)) a.put(new JSONObject().put("n", x[0]).put("id", x[1]));
        res.put("links", a);
        res.put("feed", AceSearcher.feedUrl(app));
        send(out, 200, "application/json; charset=utf-8", res.toString());
    }

    static void toast(final String m) {
        main.post(new Runnable() { public void run() {
            try { Toast.makeText(app, m, Toast.LENGTH_LONG).show(); } catch (Throwable t) { }
        } });
    }

    static String cached;
    static String page() {
        if (cached != null) return cached;
        try {
            InputStream in = app.getAssets().open("movil.html");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            in.close();
            cached = b.toString("UTF-8");
        } catch (Throwable t) { cached = "Error"; }
        return cached;
    }

    static void send(OutputStream out, int code, String type, String body) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Not Found") + "\r\n"
                + "Content-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(b);
        out.flush();
    }
}
