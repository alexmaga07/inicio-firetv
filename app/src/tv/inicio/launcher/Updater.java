package tv.inicio.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

/**
 * Actualizaciones de Inicio a distancia.
 *
 * Cada versión publicada deja en internet un update.json con este formato:
 *   {"versionCode": 22, "versionName": "2.7.0",
 *    "apk": "https://.../inicio-2.7.0.apk", "sha256": "…", "notas": "Qué cambia"}
 * Inicio lo consulta (al abrirse, como mucho cada 6 h), y si hay una versión mayor que la
 * instalada lo avisa. Al aceptar, descarga el APK, comprueba su SHA-256 y abre el instalador
 * de Android: hace falta pulsar «Instalar» en la tele (sin root no se puede actualizar solo).
 * Android además exige que el APK nuevo esté firmado con la misma clave (inicio.keystore).
 */
public class Updater {
    public interface Callback { void done(Info info); }

    /** Dirección del update.json de la última versión publicada (GitHub Releases). */
    static final String UPDATE_URL =
            "https://github.com/alexmaga07/inicio-firetv/releases/latest/download/update.json";
    static final long EVERY_MS = 6 * 60 * 60 * 1000L;

    public static class Info {
        int versionCode;
        String versionName = "", apk = "", sha256 = "", notas = "";
        boolean newer;
        String error;
    }

    static final Handler main = new Handler(Looper.getMainLooper());
    static volatile Info last;
    static volatile boolean busy;

    static SharedPreferences prefs(Context c) { return AceLinks.prefs(c); }

    static int currentCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; }
        catch (Throwable t) { return 0; }
    }

    static String currentName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; }
        catch (Throwable t) { return "?"; }
    }

    /** Consulta si hay versión nueva. force=false respeta el intervalo de 6 h. */
    static void check(final Context c, boolean force, final Callback cb) {
        final Context app = c.getApplicationContext();
        long lastAt = prefs(app).getLong("upd_checked_at", 0);
        if (!force && last != null && System.currentTimeMillis() - lastAt < EVERY_MS) {
            if (cb != null) cb.done(last);
            return;
        }
        new Thread(new Runnable() {
            public void run() {
                Info info = new Info();
                try {
                    String body = new String(fetch(UPDATE_URL, 512 * 1024), "UTF-8");
                    JSONObject o = new JSONObject(body);
                    info.versionCode = o.getInt("versionCode");
                    info.versionName = o.optString("versionName", "");
                    info.apk = o.getString("apk");
                    info.sha256 = o.optString("sha256", "").toLowerCase();
                    info.notas = o.optString("notas", "");
                    info.newer = info.versionCode > currentCode(app);
                    prefs(app).edit().putLong("upd_checked_at", System.currentTimeMillis()).apply();
                } catch (Throwable t) {
                    info.error = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                last = info;
                final Info r = info;
                if (cb != null) main.post(new Runnable() { public void run() { cb.done(r); } });
            }
        }, "inicio-update-check").start();
    }

    /** ¿Hay que enseñar el aviso? Una vez al día por versión, salvo que se pida a mano. */
    static boolean shouldPrompt(Context c, Info i) {
        if (i == null || !i.newer) return false;
        SharedPreferences p = prefs(c);
        int code = p.getInt("upd_prompted_code", 0);
        long at = p.getLong("upd_prompted_at", 0);
        return code != i.versionCode || System.currentTimeMillis() - at > 24 * 60 * 60 * 1000L;
    }

    static void markPrompted(Context c, Info i) {
        prefs(c).edit().putInt("upd_prompted_code", i.versionCode)
                .putLong("upd_prompted_at", System.currentTimeMillis()).apply();
    }

    public interface Progress { void step(String msg); void failed(String msg); }

    /** Descarga, verifica y abre el instalador. Los avisos llegan por el hilo principal. */
    static void downloadAndInstall(final Context c, final Info i, final Progress p) {
        if (busy) return;
        busy = true;
        final Context app = c.getApplicationContext();
        new Thread(new Runnable() {
            public void run() {
                try {
                    post(p, "Descargando Inicio " + i.versionName + "…", false);
                    byte[] data = fetch(i.apk, 40 * 1024 * 1024);
                    if (!i.sha256.isEmpty()) {
                        String got = sha256(data);
                        if (!got.equals(i.sha256)) throw new Exception("el archivo descargado no coincide (SHA-256)");
                    }
                    File dir = new File(app.getCacheDir(), "update");
                    dir.mkdirs();
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    File apk = new File(dir, "inicio.apk");
                    FileOutputStream o = new FileOutputStream(apk);
                    o.write(data);
                    o.close();
                    apk.setReadable(true, false);
                    post(p, "Pulsa «Instalar» para actualizar", false);
                    main.post(new Runnable() { public void run() { install(app, p); } });
                } catch (Throwable t) {
                    post(p, "No se pudo actualizar: " + (t.getMessage() == null ? t.toString() : t.getMessage()), true);
                } finally {
                    busy = false;
                }
            }
        }, "inicio-update-download").start();
    }

    static void install(Context c, Progress p) {
        try {
            Uri uri = Uri.parse("content://" + UpdateProvider.AUTHORITY + "/inicio.apk");
            Intent in = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, false);
            Redirector.pause(2 * 60 * 1000L);   // que el vigilante del botón Home no tape el instalador
            c.startActivity(in);
        } catch (Throwable t) {
            Redirector.pause(0);
            if (p != null) p.failed("No se pudo abrir el instalador: " + t.getMessage());
        }
    }

    static void post(final Progress p, final String msg, final boolean fail) {
        if (p == null) return;
        main.post(new Runnable() {
            public void run() { if (fail) p.failed(msg); else p.step(msg); }
        });
    }

    static byte[] fetch(String url, int max) throws Exception {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            try {
                h.setConnectTimeout(8000);
                h.setReadTimeout(20000);
                h.setInstanceFollowRedirects(false);   // GitHub redirige a otro dominio: se sigue a mano
                h.setRequestProperty("User-Agent", "Inicio/" + "update");
                int code = h.getResponseCode();
                String loc = h.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) { u = new URL(new URL(u), loc).toString(); continue; }
                if (code != 200) throw new Exception("HTTP " + code);
                InputStream in = h.getInputStream();
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(64 * 1024);
                byte[] buf = new byte[32 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    b.write(buf, 0, r);
                    if (b.size() > max) throw new Exception("archivo demasiado grande");
                }
                in.close();
                return b.toByteArray();
            } finally {
                h.disconnect();
            }
        }
        throw new Exception("demasiadas redirecciones");
    }

    static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }
}
