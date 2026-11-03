package tv.inicio.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Detector de bloqueos de la operadora.
 *
 * Durante el fútbol (LaLiga, Champions…) y otros eventos, las operadoras españolas (Movistar
 * incluida) bloquean por IP cientos de direcciones de Cloudflare por orden judicial. Torrentio y la API de Stremio están detrás de Cloudflare, así que
 * Stremio deja de cargar addons; con una VPN vuelven. AceStream NO usa Cloudflare (sus servidores
 * están en DigitalOcean, OVH, Scaleway…) y su P2P no se bloquea por IP, así que suele seguir
 * funcionando sin VPN. Y Proton VPN gratis corta el P2P de AceStream.
 *
 * Regla que aplica Inicio:  Stremio con bloqueos activos → VPN.   AceStream → sin VPN.
 *
 * La comprobación abre una conexión TCP a cada IP de cada servicio (los bloqueos son por IP, así
 * que se manifiestan como conexiones que no responden). Un servicio cuenta como bloqueado si
 * alguna de sus IPs no responde, porque Stremio puede elegir justo esa.
 */
public class Bloqueos {
    public interface Callback { void done(Result r); }

    public static class Result {
        long at;
        boolean vpn;         // la comprobación se hizo con la VPN puesta (entonces no refleja a la operadora)
        boolean internet;    // responden los sitios de control (Google, Amazon)
        boolean stremio;     // Torrentio y la API de Stremio responden en todas sus IPs
        boolean debrid;      // Real-Debrid responde
        boolean ace;         // los servidores de AceStream responden
        String detail = "";

        /** Hay bloqueos activos visibles ahora mismo desde esta conexión. */
        boolean laliga() { return !vpn && internet && !stremio; }
    }

    // {host, puerto, grupo}
    static final String[][] TARGETS = {
        {"torrentio.strem.fun", "443", "stremio"},
        {"api.strem.io", "443", "stremio"},
        {"api.real-debrid.com", "443", "debrid"},
        {"api.acestream.me", "443", "ace"},
        {"auth1.acestream.org", "80", "ace"},
        {"www.google.com", "443", "control"},
        {"www.amazon.es", "443", "control"},
    };
    static final int CONNECT_MS = 3000;
    static final long FRESH_MS = 3 * 60 * 1000;     // para decidir al abrir Stremio
    static final long REFRESH_MS = 10 * 60 * 1000;  // refresco de la cabecera

    static final Handler main = new Handler(Looper.getMainLooper());
    static volatile Result last;
    static boolean running;
    static final List<Callback> waiting = new ArrayList<>();

    static boolean fresh(long maxAge) {
        Result r = last;
        return r != null && System.currentTimeMillis() - r.at < maxAge;
    }

    /** Comprueba (o reutiliza el último resultado si es reciente y coincide el estado de la VPN). */
    static void check(final Context c, long maxAge, final Callback cb) {
        final Context app = c.getApplicationContext();
        final boolean vpn = AceLinks.vpnOn(app);
        Result r = last;
        if (r != null && r.vpn == vpn && System.currentTimeMillis() - r.at < maxAge) {
            if (cb != null) cb.done(r);
            return;
        }
        synchronized (Bloqueos.class) {
            if (cb != null) waiting.add(cb);
            if (running) return;
            running = true;
        }
        new Thread(new Runnable() {
            public void run() {
                final Result res = probe(vpn);
                last = res;
                final List<Callback> cbs;
                synchronized (Bloqueos.class) {
                    cbs = new ArrayList<>(waiting);
                    waiting.clear();
                    running = false;
                }
                main.post(new Runnable() {
                    public void run() { for (Callback k : cbs) k.done(res); }
                });
            }
        }, "inicio-bloqueos").start();
    }

    static Result probe(boolean vpn) {
        final boolean[] ok = new boolean[TARGETS.length];
        final String[] why = new String[TARGETS.length];
        Thread[] ts = new Thread[TARGETS.length];
        for (int i = 0; i < TARGETS.length; i++) {
            final int k = i;
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    try {
                        InetAddress[] addrs = InetAddress.getAllByName(TARGETS[k][0]);
                        int port = Integer.parseInt(TARGETS[k][1]);
                        int good = 0, v4 = 0;
                        for (InetAddress a : addrs) {
                            if (!(a instanceof java.net.Inet4Address)) continue;
                            v4++;
                            if (reach(a, port) || reach(a, port)) good++;   // un reintento evita falsos positivos
                        }
                        ok[k] = v4 > 0 && good == v4;
                        if (!ok[k]) why[k] = good + "/" + v4 + " IPs";
                    } catch (Throwable t) {
                        ok[k] = false;
                        why[k] = "DNS";
                    }
                }
            });
            ts[i].start();
        }
        long end = System.currentTimeMillis() + 2 * CONNECT_MS + 2500;
        for (Thread t : ts) {
            try { t.join(Math.max(1, end - System.currentTimeMillis())); } catch (Throwable ignored) { }
        }
        Result r = new Result();
        r.at = System.currentTimeMillis();
        r.vpn = vpn;
        boolean st = true, ac = false, deb = true, ctl = false;
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < TARGETS.length; i++) {
            String g = TARGETS[i][2];
            if (g.equals("stremio")) st &= ok[i];
            else if (g.equals("debrid")) deb &= ok[i];
            else if (g.equals("ace")) ac |= ok[i];
            else if (g.equals("control")) ctl |= ok[i];
            if (!ok[i]) d.append(d.length() > 0 ? ", " : "").append(TARGETS[i][0])
                    .append(why[i] == null ? "" : " (" + why[i] + ")");
        }
        r.stremio = st;
        r.debrid = deb;
        r.ace = ac;
        r.internet = ctl;
        r.detail = d.toString();
        return r;
    }

    static boolean reach(InetAddress a, int port) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(a, port), CONNECT_MS);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { s.close(); } catch (Throwable ignored) { }
        }
    }
}
