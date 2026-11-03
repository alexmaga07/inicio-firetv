package tv.inicio.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Buscador de canales AceStream.
 *
 * Fuentes (se descargan a la vez, en paralelo):
 *  1) API pública de AceStream (api.acestream.me/all): trae país, idioma y categoría.
 *  2) Playlist M3U de search-ace.stream (contiene todo lo de la API y algo más).
 *  3) Lista M3U personal del usuario, si la configuró desde el móvil.
 *
 * Cada fuente se guarda también en disco: si la red falla (o la VPN está apagada),
 * se usa la última copia buena en vez de dejar la lista vacía.
 *
 * Cada canal es un String[] con las posiciones NAME, ID, INFO, KEY, META, BOOST, COMPACT y TYPE
 * (TYPE = "ih" si el ID es un infohash, "" si es un content ID; ver AceLinks.uri).
 * Los favoritos de AceLinks (solo {nombre, id}) también se pueden pasar a filter().
 */
public class AceSearcher {
    public interface Callback { void done(List<String[]> results, String error); }

    // Posiciones dentro de cada canal
    static final int NAME = 0, ID = 1, INFO = 2, KEY = 3, META = 4, BOOST = 5, COMPACT = 6, TYPE = 7;

    static final String PREF_FEED = "ace_feed_url";
    static final String SEARCH_ACE_PLAYLIST = "https://search-ace.stream/playlist";
    static final String ACE_API_ALL = "https://api.acestream.me/all?api_version=1.0&api_key=test_api_key";
    static final Pattern HEX = Pattern.compile("([a-fA-F0-9]{40})");
    static final int MAX_ITEMS = 8000;
    static final int MAX_BYTES = 6 * 1024 * 1024;
    static final long CACHE_MS = 15 * 60 * 1000;
    static final long RETRY_MS = 60 * 1000;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    static final Handler main = new Handler(Looper.getMainLooper());

    // Lista completa ya procesada (en memoria) y descargas en curso
    static List<String[]> all;
    static java.util.Map<String, String> typeById = new java.util.HashMap<>();
    static long allAt;
    static String allUserUrl;
    static boolean loading;
    static final List<Object[]> waiting = new ArrayList<>();   // {query, callback}

    // ───────────────────────── Lista M3U del usuario ─────────────────────────

    static String feedUrl(Context c) {
        return AceLinks.prefs(c).getString(PREF_FEED, "");
    }

    static void setFeedUrl(Context c, String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) AceLinks.prefs(c).edit().remove(PREF_FEED).apply();
        else AceLinks.prefs(c).edit().putString(PREF_FEED, u).apply();
        synchronized (AceSearcher.class) { all = null; allUserUrl = null; }
    }

    /** Vacía (para quitar la lista) o una dirección http:// / https:// válida. */
    static boolean validUrl(String url) {
        String u = url == null ? "" : url.trim();
        if (u.isEmpty()) return true;
        String low = u.toLowerCase(Locale.ROOT);
        if (!low.startsWith("http://") && !low.startsWith("https://")) return false;
        try {
            URL parsed = new URL(u);
            return parsed.getHost() != null && !parsed.getHost().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ───────────────────────── Búsqueda ─────────────────────────

    static void searchLive(final Context c, final String query, final Callback cb) {
        search(c, query, false, cb);
    }

    /**
     * Devuelve los canales que encajan con la búsqueda, ordenados por relevancia.
     * Si la lista está en memoria y es reciente, responde al instante sin red.
     * Si ya hay una descarga en marcha, se espera a ella en vez de lanzar otra.
     */
    static void search(final Context c, final String query, final boolean refresh, final Callback cb) {
        final Context app = c.getApplicationContext();
        final String userUrl = feedUrl(app);
        synchronized (AceSearcher.class) {
            if (!refresh && all != null && userUrl.equals(allUserUrl)
                    && System.currentTimeMillis() - allAt < CACHE_MS) {
                final List<String[]> snapshot = all;
                main.post(new Runnable() { public void run() { cb.done(filter(snapshot, query), null); } });
                return;
            }
            waiting.add(new Object[]{query, cb});
            if (loading) return;
            loading = true;
        }
        new Thread(new Runnable() {
            public void run() {
                List<String[]> result = null;
                String err = null;
                boolean partial = false;
                try {
                    String[] errOut = new String[1];
                    boolean[] partialOut = new boolean[1];
                    result = load(app, userUrl, refresh, errOut, partialOut);
                    err = errOut[0];
                    partial = partialOut[0];
                } catch (Throwable t) {
                    err = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                final List<Object[]> cbs;
                final List<String[]> list;
                final String e;
                synchronized (AceSearcher.class) {
                    if (result != null && !result.isEmpty()) {
                        all = result;
                        java.util.Map<String, String> types = new java.util.HashMap<>();
                        for (String[] x : result) types.put(x[ID], x[TYPE]);
                        typeById = types;
                        allUserUrl = userUrl;
                        // Si alguna fuente falló, reintenta pronto en vez de esperar 15 minutos
                        allAt = partial ? System.currentTimeMillis() - CACHE_MS + RETRY_MS : System.currentTimeMillis();
                    }
                    list = all != null ? all : new ArrayList<String[]>();
                    e = list.isEmpty() ? (err == null ? "sin respuesta" : err) : null;
                    cbs = new ArrayList<>(waiting);
                    waiting.clear();
                    loading = false;
                }
                if (result != null && !result.isEmpty()) {
                    try { AceLinks.adoptTypes(app, typeById); } catch (Throwable t) { }
                }
                for (final Object[] w : cbs) {
                    final List<String[]> r = filter(list, (String) w[0]);
                    main.post(new Runnable() { public void run() { ((Callback) w[1]).done(r, e); } });
                }
            }
        }, "inicio-search").start();
    }

    /** Descarga las fuentes en paralelo, las une sin duplicados y las ordena. */
    static List<String[]> load(final Context app, String userUrl, final boolean refresh,
                               String[] errOut, boolean[] partialOut) throws InterruptedException {
        final String[] urls = {ACE_API_ALL, SEARCH_ACE_PLAYLIST, userUrl.isEmpty() ? null : userUrl};
        final String[] texts = new String[urls.length];
        final String[] errs = new String[urls.length];
        final boolean[] stale = new boolean[urls.length];
        Thread[] ts = new Thread[urls.length];
        for (int i = 0; i < urls.length; i++) {
            if (urls[i] == null) continue;
            final int k = i;
            ts[i] = new Thread(new Runnable() {
                public void run() {
                    File f = cacheFile(app, urls[k]);
                    if (!refresh && f != null && f.exists()
                            && System.currentTimeMillis() - f.lastModified() < CACHE_MS) {
                        texts[k] = readFile(f);
                        if (texts[k] != null) return;
                    }
                    try {
                        texts[k] = download(urls[k]);
                        writeFile(f, texts[k]);
                    } catch (Throwable t) {
                        errs[k] = t.getMessage() == null ? t.toString() : t.getMessage();
                        texts[k] = readFile(f);   // última copia buena, aunque sea antigua
                        stale[k] = true;
                    }
                }
            }, "inicio-dl-" + i);
            ts[i].start();
        }
        for (Thread t : ts) if (t != null) t.join();

        // Unión: lista del usuario (sus nombres mandan) → API (metadatos) → playlist (el resto)
        Map<String, String[]> byId = new LinkedHashMap<>();
        if (texts[2] != null) for (String[] x : parseM3u(texts[2], 12)) if (!byId.containsKey(x[ID])) byId.put(x[ID], x);
        if (texts[0] != null) {
            for (String[] x : parseApiJson(texts[0])) {
                String[] old = byId.get(x[ID]);
                if (old == null) { byId.put(x[ID], x); continue; }
                if (old[INFO].isEmpty()) old[INFO] = x[INFO];
                old[META] = (old[META] + " " + x[META]).trim();
                old[BOOST] = String.valueOf(Integer.parseInt(old[BOOST]) + Integer.parseInt(x[BOOST]));
            }
        }
        if (texts[1] != null) for (String[] x : parseM3u(texts[1], 0)) if (!byId.containsKey(x[ID])) byId.put(x[ID], x);

        List<String[]> out = new ArrayList<>(byId.values());
        // Orden por defecto (sin búsqueda): primero lo español y lo verificado; estable para lo demás
        Collections.sort(out, new Comparator<String[]>() {
            public int compare(String[] a, String[] b) { return boost(b) - boost(a); }
        });

        for (int i = 0; i < urls.length; i++) {
            if (urls[i] == null) continue;
            if (errs[i] != null || stale[i]) partialOut[0] = true;
            if (errs[i] != null && errOut[0] == null) errOut[0] = errs[i];
        }
        return out;
    }

    // ───────────────────────── Filtro y ranking ─────────────────────────

    /**
     * Filtra y ordena por relevancia. Todas las palabras tienen que aparecer
     * (sin distinguir mayúsculas ni acentos). Los números van como número entero:
     * «dazn 1» no encuentra «DAZN 1080» ni canales cuyo ID contenga un 1.
     */
    static List<String[]> filter(List<String[]> list, String query) {
        if (list == null) return new ArrayList<>();
        String q = normQuery(query);
        if (q.isEmpty()) return new ArrayList<>(list);
        final String[] tokens = q.split(" ");
        final String qCompact = q.replace(" ", "");

        final List<String[]> hits = new ArrayList<>();
        final List<Integer> scores = new ArrayList<>();
        for (String[] x : list) {
            String key = x.length > KEY ? x[KEY] : norm(x[NAME]);
            String meta = x.length > META ? x[META] : "";
            String id = x[ID];
            String compact = x.length > COMPACT ? x[COMPACT] : key.replace(" ", "");
            int score = 0;
            boolean ok = true;
            for (String tk : tokens) {
                int s = tokenScore(tk, key, compact, meta, id);
                if (s <= 0) { ok = false; break; }
                score += s;
            }
            if (!ok) continue;
            if (compact.equals(qCompact)) score += 60;
            else if (compact.startsWith(qCompact)) score += 20;
            if (key.startsWith(q)) score += 30;
            else if (key.contains(q)) score += 12;
            score += boost(x) - Math.min(key.length(), 60) / 15;
            hits.add(x);
            scores.add(score);
        }
        Integer[] idx = new Integer[hits.size()];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        java.util.Arrays.sort(idx, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int d = scores.get(b) - scores.get(a);
                return d != 0 ? d : a - b;
            }
        });
        List<String[]> out = new ArrayList<>(idx.length);
        for (Integer i : idx) out.add(hits.get(i));
        return out;
    }

    /** Puntos de una palabra de la búsqueda en un canal (0 = no aparece). */
    static int tokenScore(String tk, String key, String compact, String meta, String id) {
        boolean numeric = isDigits(tk);
        int best = 0;
        int from = 0;
        while (true) {
            int i = key.indexOf(tk, from);
            if (i < 0) break;
            int end = i + tk.length();
            boolean startWord = i == 0 || key.charAt(i - 1) == ' ';
            boolean endWord = end == key.length() || key.charAt(end) == ' ';
            int s;
            if (numeric) {
                boolean digitBefore = i > 0 && Character.isDigit(key.charAt(i - 1));
                boolean digitAfter = end < key.length() && Character.isDigit(key.charAt(end));
                s = (digitBefore || digitAfter) ? 0 : (startWord ? 10 : 4);
            } else if (tk.length() <= 2) {
                // «la», «f1», «tv»… solo al principio de palabra («la 1» no es «Saarland 1»)
                s = !startWord ? 0 : endWord ? 10 : 6;
            } else {
                s = startWord && endWord ? 10 : startWord ? 6 : 3;
            }
            if (s > best) best = s;
            if (best == 10) break;
            from = i + 1;
        }
        if (best > 0) return best;
        if (!numeric && tk.length() >= 4 && compact.contains(tk)) return 2;   // «laliga» ↔ «La Liga»
        if (!numeric && tk.length() >= 3 && meta.contains(tk)) return 1;      // «deportes», «españa»…
        if (tk.length() >= 8 && tk.matches("[0-9a-f]+") && id.contains(tk)) return 5;   // trozo del ID
        return 0;
    }

    static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (!Character.isDigit(s.charAt(i))) return false;
        return true;
    }

    static int boost(String[] x) {
        if (x.length <= BOOST) return 0;
        try { return Integer.parseInt(x[BOOST]); } catch (Throwable t) { return 0; }
    }

    static final Pattern MARKS = Pattern.compile("\\p{M}+");
    static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}+]+");
    static final Pattern MOVISTAR = Pattern.compile("(?<![\\p{L}\\p{N}])m[+.](?=\\s|$)");
    static final Pattern MOVISTAR_START = Pattern.compile("^m[+.](?=\\s)");

    /** Minúsculas, sin acentos, sin signos. «M+ / M.» al principio cuentan como «movistar». */
    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD);
        t = MARKS.matcher(t).replaceAll("").toLowerCase(Locale.ROOT).trim();
        t = MOVISTAR_START.matcher(t).replaceFirst("movistar");
        t = NOT_WORD.matcher(t).replaceAll(" ").trim();
        return t;
    }

    static String normQuery(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s, Normalizer.Form.NFD);
        t = MARKS.matcher(t).replaceAll("").toLowerCase(Locale.ROOT).trim();
        t = MOVISTAR.matcher(t).replaceAll("movistar");
        t = NOT_WORD.matcher(t).replaceAll(" ").trim();
        return t;
    }

    // ───────────────────────── Limpieza de nombres ─────────────────────────

    static final Pattern URL_RE = Pattern.compile("(?i)(?:https?://|www\\.)\\S*");
    static final Pattern DOMAIN_RE = Pattern.compile(
            "(?i)(?<![\\w.])([a-z0-9][a-z0-9-]{1,30})\\.(?:top|online|blog|com|net|org|io|me|xyz|site|live|link|cc|info|club|stream|fun|pro|app|eth|win|lol)(?![\\w])");
    static final Pattern HANDLE_RE = Pattern.compile("(?<![\\w])@\\w+");
    static final Pattern ZERONET_RE = Pattern.compile("(?i)\\bzeronet\\b");
    static final Pattern ARROW_RE = Pattern.compile("\\s*(?:-->|->|=>)\\s*(.*)$");
    static final Pattern EDGES = Pattern.compile("^[\\s|,;:\\-–—>•·]+|[\\s|,;:\\-–—>•·(]+$");

    /** Devuelve {nombre limpio, fuente (o "")}. Quita URLs, dominios y firmas de las listas. */
    static String[] clean(String raw, String id) {
        String n = raw == null ? "" : raw.replace("﻿", "").replaceAll("[\\r\\n\\t]+", " ").trim();
        String source = "";

        Matcher a = ARROW_RE.matcher(n);
        if (a.find()) {
            String tail = a.group(1).trim();
            if (!tail.isEmpty() && tail.length() <= 25 && !tail.contains("://")) source = tail;
            if (a.start() > 0) n = n.substring(0, a.start());
        }

        int cut = n.length();
        Matcher m = DOMAIN_RE.matcher(n);
        if (m.find()) {
            cut = Math.min(cut, m.start());
            if (source.isEmpty()) source = capital(m.group(1));
        }
        for (Pattern p : new Pattern[]{URL_RE, HANDLE_RE, ZERONET_RE}) {
            Matcher mm = p.matcher(n);
            if (mm.find()) cut = Math.min(cut, mm.start());
        }
        String head = EDGES.matcher(n.substring(0, cut)).replaceAll("").trim();
        if (head.isEmpty() && cut < n.length()) {
            // Todo lo útil estaba detrás: en vez de cortar, borra la basura
            head = n;
            for (Pattern p : new Pattern[]{URL_RE, DOMAIN_RE, HANDLE_RE, ZERONET_RE}) head = p.matcher(head).replaceAll(" ");
            head = EDGES.matcher(head).replaceAll("").trim();
        }
        // «Movistar LaLiga Shickat» → «Movistar LaLiga» cuando la firma coincide con la fuente
        if (!source.isEmpty()) {
            String lowHead = head.toLowerCase(Locale.ROOT), lowSrc = source.toLowerCase(Locale.ROOT);
            if (lowHead.endsWith(" " + lowSrc)) head = head.substring(0, head.length() - lowSrc.length()).trim();
            head = EDGES.matcher(head).replaceAll("").trim();
        }
        head = head.replaceAll("\\s{2,}", " ");
        if (head.length() > 60) head = head.substring(0, 60).trim();
        if (head.isEmpty()) head = raw == null || raw.trim().isEmpty() ? "Canal " + id.substring(0, 6) : raw.trim();
        if (head.length() > 60) head = head.substring(0, 60).trim();
        return new String[]{head, source};
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    static final Pattern SPANISH = Pattern.compile(
            "(?i)\\bspain\\b|españa|espana|\\[es\\]|\\(es\\)|\\(sp\\)|\\belcano\\b|shickat|new era|\\bmovistar\\b|^m[+.]\\s|laliga|la liga|\\bm\\+");

    static final Pattern ADULT = Pattern.compile("(?i)adult|\\bxxx\\b|\\b18\\+");

    /**
     * ¿Es este ID un infohash conocido por el buscador? Mira la lista en memoria y, si aún
     * no se ha cargado (p. ej. al reproducir desde el móvil), las copias en disco de la API y
     * de search-ace.stream, que solo contienen infohashes.
     */
    static boolean knownInfohash(Context c, String id) {
        if (id == null) return false;
        java.util.Map<String, String> m = typeById;
        if (m.containsKey(id)) return AceLinks.isInfohash(m.get(id));
        for (String u : new String[]{ACE_API_ALL, SEARCH_ACE_PLAYLIST}) {
            String t = readFile(cacheFile(c.getApplicationContext(), u));
            if (t != null && t.toLowerCase(Locale.ROOT).contains(id.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Como knownInfohash, pero si el buscador aún no tiene ninguna lista (tele recién encendida o que
     * nunca ha abierto el buscador) la carga primero, esperando como mucho ms.
     * NO llamar desde el hilo principal (el resultado de la carga llega por él).
     */
    static boolean knownInfohashBlocking(Context c, String id, long ms) {
        if (knownInfohash(c, id)) return true;
        if (!typeById.isEmpty()) return false;   // ya hay lista cargada y no está en ella
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        search(c, "", false, new Callback() {
            public void done(List<String[]> r, String e) { done.countDown(); }
        });
        try { done.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ignored) { }
        return knownInfohash(c, id);
    }

    /** Construye un canal con su clave de búsqueda ya calculada. */
    static String[] entry(String rawName, String id, String category, String country, boolean spanish, int boost,
                          String type) {
        String[] c = clean(rawName, id);
        String name = c[0], source = c[1];
        String rn = rawName == null ? "" : rawName;
        spanish = spanish || SPANISH.matcher(rn).find();
        boolean adult = (category != null && category.startsWith("erotic")) || ADULT.matcher(rn).find();
        String cat = adult ? "Adultos" : catLabel(category);
        String countryLabel = spanish ? "España" : country == null || country.isEmpty() ? "" : country.toUpperCase(Locale.ROOT);
        StringBuilder info = new StringBuilder();
        for (String part : new String[]{cat, countryLabel, source}) {
            if (part.isEmpty()) continue;
            if (part.equals(countryLabel) && name.toUpperCase(Locale.ROOT).contains(countryLabel.toUpperCase(Locale.ROOT))) continue;
            if (info.length() > 0) info.append(" · ");
            info.append(part);
        }
        String meta = norm(cat + " " + (category == null ? "" : category) + " " + countryLabel + " "
                + (spanish ? "espana spain" : "") + " " + source);
        int b = boost + (spanish ? 8 : 0);
        if (adult) b -= 6;   // al final de la lista
        String key = norm(name);
        return new String[]{name, id, info.toString(), key, meta, String.valueOf(b), key.replace(" ", ""),
                type == null ? "" : type};
    }

    static String catLabel(String c) {
        if (c == null) return "";
        String k = c.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty()) return "";
        if (k.equals("sport") || k.equals("sports")) return "Deportes";
        if (k.equals("movies")) return "Cine";
        if (k.equals("series")) return "Series";
        if (k.equals("kids")) return "Infantil";
        if (k.equals("music") || k.equals("music_video")) return "Música";
        if (k.equals("informational") || k.equals("news")) return "Noticias";
        if (k.equals("documentaries") || k.equals("educational")) return "Documentales";
        if (k.equals("entertaining")) return "Entretenimiento";
        if (k.equals("religion")) return "Religión";
        if (k.startsWith("erotic")) return "Adultos";
        return "";   // «tv», «regional», «amateur», «other»… no aportan nada
    }

    // ───────────────────────── Parsers ─────────────────────────

    static List<String[]> parseApiJson(String text) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(text);
            for (int i = 0; i < arr.length() && out.size() < MAX_ITEMS; i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                Matcher m = HEX.matcher(o.optString("infohash", ""));
                if (!m.find() || name.isEmpty()) continue;
                String id = m.group(1).toLowerCase(Locale.ROOT);
                JSONArray cats = o.optJSONArray("categories");
                JSONArray countries = o.optJSONArray("country");
                JSONArray langs = o.optJSONArray("language");
                String cat = cats != null && cats.length() > 0 ? cats.optString(0, "") : "";
                if (cats != null && cat.equalsIgnoreCase("tv") && cats.length() > 1) cat = cats.optString(1, "");
                String country = countries != null && countries.length() > 0 ? countries.optString(0, "") : "";
                boolean spanish = has(countries, "es") || has(langs, "spa");
                // +3: la API solo lista canales que están emitiendo
                out.add(entry(name, id, cat, country, spanish, 3, AceLinks.INFOHASH));   // la API da infohashes
            }
        } catch (Throwable t) { }
        return out;
    }

    static boolean has(JSONArray a, String v) {
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (v.equalsIgnoreCase(a.optString(i, ""))) return true;
        return false;
    }

    static final Pattern GROUP_RE = Pattern.compile("group-title=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    /** M3U con acestream://ID, ?id=ID, infohash=ID o el ID suelto. */
    static List<String[]> parseM3u(String text, int boost) {
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String pending = null, group = null;
        for (String raw : text.split("\r?\n")) {
            String t = raw.replace("﻿", "").trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("#EXTINF")) {
                int q = t.lastIndexOf('"');
                int k = t.indexOf(',', Math.max(0, q + 1));
                if (k < 0) k = t.lastIndexOf(',');
                pending = k >= 0 ? t.substring(k + 1).trim() : "";
                Matcher g = GROUP_RE.matcher(t);
                group = g.find() ? g.group(1).trim() : null;
                continue;
            }
            if (t.startsWith("#")) continue;
            Matcher m = HEX.matcher(t);
            if (m.find()) {
                String id = m.group(1).toLowerCase(Locale.ROOT);
                String name = pending;
                if (name == null || name.isEmpty()) {
                    name = t.substring(0, m.start()).replace("acestream://", "")
                            .replaceAll("[\\s,;|:=\\-]+$", "").trim();
                }
                if (seen.add(id)) {
                    // «…getstream?infohash=…» o magnet → infohash; acestream://… o el ID suelto → content ID
                    out.add(entry(name, id, group, null, false, boost, AceLinks.parseType(t)));
                    if (out.size() >= MAX_ITEMS) break;
                }
            }
            pending = null;
            group = null;
        }
        return out;
    }

    // ───────────────────────── Red y caché en disco ─────────────────────────

    static String download(String url) throws Exception {
        String u = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            try {
                h.setConnectTimeout(5000);
                h.setReadTimeout(8000);
                h.setInstanceFollowRedirects(false);
                h.setRequestProperty("User-Agent", UA);
                h.setRequestProperty("Accept", "*/*");
                int code = h.getResponseCode();
                String loc = h.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code != 200) throw new Exception("HTTP " + code);
                InputStream in = h.getInputStream();
                ByteArrayOutputStream b = new ByteArrayOutputStream(256 * 1024);
                byte[] buf = new byte[16 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    b.write(buf, 0, r);
                    if (b.size() > MAX_BYTES) break;
                }
                in.close();
                if (b.size() == 0) throw new Exception("respuesta vacía");
                return b.toString("UTF-8");
            } finally {
                h.disconnect();
            }
        }
        throw new Exception("demasiadas redirecciones");
    }

    static File cacheFile(Context c, String url) {
        try {
            File dir = new File(c.getCacheDir(), "ace");
            if (!dir.exists()) dir.mkdirs();
            return new File(dir, Integer.toHexString(url.hashCode()) + ".txt");
        } catch (Throwable t) { return null; }
    }

    static void writeFile(File f, String text) {
        if (f == null || text == null) return;
        try {
            File tmp = new File(f.getPath() + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(text.getBytes("UTF-8"));
            o.close();
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        } catch (Throwable t) { }
    }

    static String readFile(File f) {
        if (f == null || !f.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(f);
            ByteArrayOutputStream b = new ByteArrayOutputStream((int) Math.max(1024, f.length()));
            byte[] buf = new byte[16 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
            in.close();
            return b.size() == 0 ? null : b.toString("UTF-8");
        } catch (Throwable t) { return null; }
    }
}
