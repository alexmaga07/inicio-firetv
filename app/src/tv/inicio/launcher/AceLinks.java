package tv.inicio.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Enlaces de AceStream guardados por el usuario (enviados desde el móvil). */
public class AceLinks {
    static final String[] PKGS = {"org.acestream.node.web", "org.acestream.node", "org.acestream.core", "org.acestream.media.atv",
            "org.acestream.media", "org.acestream.live"};
    static final Pattern HEX = Pattern.compile("([0-9a-fA-F]{40})");

    static String pkg(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String p : PKGS) {
            try { pm.getPackageInfo(p, 0); return p; } catch (Throwable t) { }
        }
        return null;
    }

    /** Saca el ID de 40 caracteres de un acestream://..., un enlace http con id=... o el ID suelto. */
    static String parse(String raw) {
        if (raw == null) return null;
        Matcher m = HEX.matcher(raw.trim());
        return m.find() ? m.group(1).toLowerCase() : null;
    }

    /**
     * Tipo de identificador. AceStream usa dos tipos de ID de 40 caracteres y NO son intercambiables:
     *  - INFOHASH ("ih"): el que dan el buscador, search-ace.stream, los magnet y los enlaces "infohash=".
     *  - CONTENT ID (""): el de los enlaces acestream://… que se comparten normalmente.
     * Si se abre un infohash como acestream://ID, el motor responde "cannot get transport file".
     */
    static final String INFOHASH = "ih";

    static String parseType(String raw) {
        String r = raw == null ? "" : raw.toLowerCase();
        return r.contains("infohash=") || r.contains("magnet:") || r.contains("btih:") ? INFOHASH : "";
    }

    static boolean isInfohash(String type) { return INFOHASH.equals(type); }

    /** Enlace que entiende la app de AceStream para cada tipo de ID. */
    static String uri(String id, String type) {
        return isInfohash(type) ? "acestream:?infohash=" + id : "acestream://" + id;
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("inicio", Context.MODE_PRIVATE);
    }

    static synchronized String pin(Context c) {
        String p = prefs(c).getString("ace_pin", null);
        if (p == null) {
            p = String.valueOf(1000 + new Random().nextInt(9000));
            prefs(c).edit().putString("ace_pin", p).apply();
        }
        return p;
    }

    /** Lista de {nombre, id, tipo}. El tipo es "ih" (infohash) o "" (content ID / desconocido). */
    static synchronized List<String[]> list(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("ace_links", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.optString("n"), o.optString("id"), o.optString("t", "")});
            }
        } catch (Throwable t) { }
        return out;
    }

    static synchronized void store(Context c, List<String[]> l) {
        JSONArray a = new JSONArray();
        try {
            for (String[] x : l) {
                JSONObject o = new JSONObject().put("n", x[0]).put("id", x[1]);
                if (x.length > 2 && x[2] != null && !x[2].isEmpty()) o.put("t", x[2]);
                a.put(o);
            }
        } catch (Throwable t) { }
        prefs(c).edit().putString("ace_links", a.toString()).apply();
    }

    /**
     * Marca como infohash los favoritos antiguos (guardados antes de existir el campo de tipo)
     * que aparecen en las listas del buscador, que solo contienen infohashes.
     */
    static synchronized void adoptTypes(Context c, java.util.Map<String, String> typeById) {
        List<String[]> l = list(c);
        boolean changed = false;
        for (String[] x : l) {
            if (!x[2].isEmpty()) continue;
            String t = typeById.get(x[1]);
            if (isInfohash(t)) { x[2] = t; changed = true; }
        }
        if (changed) store(c, l);
    }

    /** Tipo de un ID: el guardado en favoritos o, si no consta, lo que sepa el buscador. */
    static String typeOf(Context c, String id) {
        for (String[] x : list(c)) if (x[1].equals(id) && !x[2].isEmpty()) return x[2];
        return AceSearcher.knownInfohash(c, id) ? INFOHASH : "";
    }

    static String cleanName(String name, String id) {
        String n = name == null ? "" : name.trim().replaceAll("[\\r\\n\\t]+", " ");
        if (n.length() > 60) n = n.substring(0, 60);
        return n.isEmpty() ? "Enlace " + id.substring(0, 6) : n;
    }

    /** Guarda (o renombra si ya existía). Devuelve el nombre usado. type: "ih", "" o null (sin cambiar). */
    static synchronized String save(Context c, String name, String id, String type) {
        List<String[]> l = list(c);
        String n = cleanName(name, id);
        for (String[] x : l) {
            if (x[1].equals(id)) {
                x[0] = n;
                if (type != null && !type.isEmpty()) x[2] = type;
                store(c, l);
                return n;
            }
        }
        l.add(0, new String[]{n, id, type == null ? "" : type});
        store(c, l);
        return n;
    }

    static synchronized boolean remove(Context c, String id) {
        List<String[]> l = list(c);
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i)[1].equals(id)) { l.remove(i); store(c, l); return true; }
        }
        return false;
    }

    static synchronized void moveUp(Context c, String id) {
        List<String[]> l = list(c);
        for (int i = 1; i < l.size(); i++) {
            if (l.get(i)[1].equals(id)) { String[] x = l.remove(i); l.add(i - 1, x); store(c, l); return; }
        }
    }

    static final String PROTON = "ch.protonvpn.android";

    /**
     * Momento en que Inicio abrió AceStream por última vez (0 = no hay nada pendiente).
     * Al volver a Inicio, si está puesto, se manda «Stop» al reproductor: al salir con Atrás/Home,
     * Ace Player a veces sigue reproduciendo en segundo plano o en una ventanita (PiP) y el
     * Fire Stick va a tirones.
     */
    static volatile long aceStartedAt = 0;

    /** ¿Hay una VPN conectada? (Proton VPN gratis bloquea el P2P de AceStream; sin VPN funciona.) */
    static boolean vpnOn(Context c) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable t) { }
        return false;
    }

    /** Abre el ID en AceStream averiguando su tipo (favoritos o buscador). */
    static boolean play(Context c, String id) {
        return play(c, id, null);
    }

    /** Abre el ID en AceStream (enciende la pantalla si estaba en reposo). type null = averiguarlo. */
    static boolean play(Context c, String id, String type) {
        if (id != null && (type == null || type.isEmpty())) type = typeOf(c, id);
        String p = pkg(c);
        if (p == null || id == null) return false;
        try {
            PowerManager pw = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            if (!pw.isInteractive()) {
                PowerManager.WakeLock wl = pw.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "inicio:ace");
                wl.acquire(3000);
            }
        } catch (Throwable t) { }
        Redirector.pause(4000);
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri(id, type)));
        i.setPackage(p);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { c.startActivity(i); aceStartedAt = System.currentTimeMillis(); return true; } catch (Throwable t) { }
        try {
            i.setPackage(null);
            c.startActivity(i);
            aceStartedAt = System.currentTimeMillis();
            return true;
        } catch (Throwable t) { return false; }
    }
}
