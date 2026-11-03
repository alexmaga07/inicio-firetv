package tv.inicio.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    static final int ACCENT = 0xFFFFC870;
    static final int INK = 0xFFFFF6F1;
    static final int MUTED = 0xFFF3E4DD;
    static final int LABEL = 0xFFE6D3CB;
    static final int SHADE = 0x1C0C1E;

    /** Apps con nombre y descripción conocidos. La fila "Ver" por defecto son las 6 primeras. */
    static final String[][] KNOWN = {
        {"com.stremio.one", "Stremio", "Películas y series con tus addons"},
        {"org.xbmc.kodi", "Kodi", "Tu centro multimedia"},
        {"com.amazon.firebat", "Prime Video", "Series y películas de Amazon"},
        {"com.hbo.hbonow", "HBO Max", "Series y películas de HBO"},
        {"com.netflix.ninja", "Netflix", "Series y películas"},
        {"com.amazon.firetv.youtube", "YouTube", "Vídeos y canales"},
        {"com.google.android.youtube.tv", "YouTube", "Vídeos y canales"},
        {"com.antena3.atresplayer.tv", "Atresplayer", "Series y programas de Atresmedia"},
        {"tv.twitch.android.viewer", "Twitch", "Directos y streamers"},
        {"com.disney.disneyplus", "Disney+", "Películas y series de Disney"},
        {"es.rtve.rtveplay", "RTVE Play", "Series y programas de RTVE"},
        {"com.spotify.tv.android", "Spotify", "Música y podcasts"},
    };
    static final String DEFAULT_VER = "com.stremio.one,org.xbmc.kodi,com.amazon.firebat,com.hbo.hbonow,"
            + "com.netflix.ninja,com.amazon.firetv.youtube,com.google.android.youtube.tv";

    static final String[][] TOOLS = {
        {"ch.protonvpn.android", "Proton VPN", "Conexión privada"},
        {"org.jdownloader", "JDownloader", "Gestor de descargas"},
        {"#ace", "AceStream", "Buscador de canales en directo y tus favoritos"},
        {"com.amazon.venezia", "Appstore", "Instalar y actualizar apps"},
        {"#settings", "Ajustes", "Configuración del Fire TV"},
        {"#more", "Más apps", "El resto de tus aplicaciones"},
        {"#amazon", "Menú Amazon", "Volver al inicio original de Amazon"},
    };

    static volatile boolean resumed = false;

    float s;
    Typeface fLight, fMedium, fBold, fExtra;
    TextView clock, date, spotGroup, spotName, spotDesc, vpn;
    View firstTile, focusTile;
    static int focusIndex = -1;
    final Set<String> shown = new HashSet<>();
    BroadcastReceiver tick;
    final SimpleDateFormat hourFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d 'de' MMMM", new Locale("es", "ES"));

    int px(float v) { return Math.round(v * s); }

    Typeface font(int weight, Typeface fallback) {
        try {
            return new Typeface.Builder(getAssets(), "manrope.ttf")
                    .setFontVariationSettings("'wght' " + weight).build();
        } catch (Throwable t) {
            return fallback;
        }
    }

    static GradientDrawable round(int color, float radius, int strokeW, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, strokeColor);
        return g;
    }

    TextView text(float size, Typeface tf, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTypeface(tf);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setShadowLayer(px(14), 0, px(2), 0xB31C0C1E);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        s = Math.max(dm.widthPixels, dm.heightPixels) / 1920f;

        fLight = font(200, Typeface.create("sans-serif-light", Typeface.NORMAL));
        fMedium = font(500, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        fBold = font(700, Typeface.create("sans-serif", Typeface.BOLD));
        fExtra = font(800, Typeface.create("sans-serif-black", Typeface.NORMAL));

        FrameLayout root = new FrameLayout(this);

        View left = new View(this);
        left.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB81C0C1E, 0x661C0C1E, 0x001C0C1E}));
        root.addView(left, new FrameLayout.LayoutParams(px(1250), -1));
        View vert = new View(this);
        vert.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x9E1C0C1E, 0x331C0C1E, 0x001C0C1E, 0x001C0C1E, 0xB31C0C1E}));
        root.addView(vert, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(120), px(76), px(120), px(56));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        root.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // Cabecera: reloj, fecha y estado de la VPN
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.VERTICAL);
        clock = text(150, fLight, INK);
        clock.setLetterSpacing(-0.02f);
        date = text(30, fMedium, MUTED);
        date.setPadding(px(6), px(6), 0, 0);
        times.addView(clock);
        times.addView(date);
        header.addView(times, new LinearLayout.LayoutParams(0, -2, 1));
        vpn = text(23, fBold, 0xFFFBEFEA);
        vpn.setShadowLayer(0, 0, 0, 0);
        vpn.setGravity(Gravity.CENTER_VERTICAL);
        vpn.setPadding(px(24), 0, px(26), 0);
        vpn.setBackground(round(0x991E0E20, px(28), Math.max(1, px(1)), 0x1FFFFFFF));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-2, px(56));
        vlp.topMargin = px(22);
        header.addView(vpn, vlp);
        col.addView(header);

        // Destacado
        LinearLayout spot = new LinearLayout(this);
        spot.setOrientation(LinearLayout.VERTICAL);
        spotGroup = text(22, fBold, ACCENT);
        spotGroup.setLetterSpacing(0.14f);
        spotGroup.setAllCaps(true);
        spotName = text(72, fExtra, INK);
        spotName.setLetterSpacing(-0.02f);
        spotDesc = text(28, fMedium, MUTED);
        spot.addView(spotGroup);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.topMargin = px(10);
        spot.addView(spotName, nlp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-2, -2);
        dlp.topMargin = px(12);
        spot.addView(spotDesc, dlp);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, px(196));
        slp.topMargin = px(44);
        col.addView(spot, slp);

        // Fila "Ver": apps elegidas por el usuario (guardadas en preferencias)
        PackageManager pm = getPackageManager();
        List<String[]> ver = new ArrayList<>();
        for (String pkg : verList()) {
            if (shown.contains(pkg) || launchIntent(pm, pkg) == null) continue;
            String[] a = describe(pm, pkg);
            if (shown.contains("name:" + a[1])) continue;
            ver.add(a);
            shown.add(pkg);
            shown.add("name:" + a[1]);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setClipChildren(false);
        hs.setClipToPadding(false);
        hs.setFillViewport(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        row.setPadding(0, 0, px(40), 0);
        float gap = 4;
        float tileW = 340;
        float tileH = tileW * 9f / 16f;
        for (int i = 0; i < ver.size(); i++) {
            View cell = tile(pm, ver.get(i), "Ver", tileW, tileH, i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(tileW + 32), -2);
            if (i > 0) lp.leftMargin = px(gap);
            row.addView(cell, lp);
        }
        if (focusIndex == 999 && row.getChildCount() > 0) {
            focusTile = ((ViewGroup) row.getChildAt(row.getChildCount() - 1)).getChildAt(0);
        }
        hs.addView(row, new FrameLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
        rlp.topMargin = px(10);
        rlp.leftMargin = -px(16);
        rlp.rightMargin = -px(120);
        col.addView(hs, rlp);

        View spacer = new View(this);
        col.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));

        // Dock de herramientas
        LinearLayout dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setPadding(px(10), px(10), px(10), px(10));
        dock.setBackground(round(0x991E0E20, px(48), Math.max(1, px(1)), 0x1FFFFFFF));
        int k = 0;
        for (String[] a : TOOLS) {
            if (!a[0].startsWith("#") && launchIntent(pm, a[0]) == null) continue;
            if (a[0].equals("#ace") && AceLinks.pkg(this) == null) continue;
            View item = dockItem(pm, a);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, px(84));
            if (k++ > 0) lp.leftMargin = px(8);
            dock.addView(item, lp);
        }
        LinearLayout.LayoutParams dkl = new LinearLayout.LayoutParams(-2, -2);
        dkl.gravity = Gravity.CENTER_HORIZONTAL;
        col.addView(dock, dkl);

        setContentView(root);
        if (!ver.isEmpty()) setSpot(ver.get(0), "Ver");
        updateClock();
    }

    Intent launchIntent(PackageManager pm, String pkg) {
        try {
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            return i;
        } catch (Throwable t) {
            return null;
        }
    }

    void setSpot(String[] a, String group) {
        spotGroup.setText(group);
        spotName.setText(a[1]);
        spotDesc.setText(a[2]);
    }

    View tile(PackageManager pm, final String[] a, final String group, float w, float h, final int index) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setClipChildren(false);

        // El marco reserva un margen P alrededor de la tarjeta: ahí caben el anillo de foco y la
        // ampliación, así nada se dibuja fuera de sus límites y nunca se recorta.
        final int P = px(16);
        final FrameLayout frame = new FrameLayout(this);
        frame.setFocusable(true);
        frame.setClickable(true);
        final FrameLayout card = new FrameLayout(this);
        final float r = px(22);
        card.setBackground(round(0x8C1E0E20, r, Math.max(1, px(2)), 0x1FFFFFFF));
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r);
            }
        });
        card.setClipToOutline(true);

        ImageView img = new ImageView(this);
        Drawable banner = null, icon = null;
        try { banner = pm.getApplicationBanner(a[0]); } catch (Throwable t) { }
        if (banner == null) {
            try { icon = pm.getApplicationIcon(a[0]); } catch (Throwable t) { }
        }
        if (banner != null) {
            img.setImageDrawable(banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setImageDrawable(icon);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(px(h * 0.62f), px(h * 0.62f));
            ilp.gravity = Gravity.CENTER;
            card.addView(img, ilp);
        }
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -1);
        cl.setMargins(P, P, P, P);
        frame.addView(card, cl);
        final View ring = new View(this);
        ring.setBackground(round(Color.TRANSPARENT, r + P - px(2), px(4), ACCENT));
        ring.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams rgl = new FrameLayout.LayoutParams(-1, -1);
        rgl.setMargins(px(2), px(2), px(2), px(2));
        frame.addView(ring, rgl);

        final TextView label = text(26, fMedium, LABEL);
        label.setPadding(px(4), 0, 0, 0);
        label.setText(a[1]);

        cell.addView(frame, new LinearLayout.LayoutParams(-1, px(h) + 2 * P));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = px(2);
        llp.leftMargin = P;
        cell.addView(label, llp);

        frame.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                ring.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
                label.setTextColor(has ? INK : LABEL);
                label.setTypeface(has ? fBold : fMedium);
                card.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start();
                if (has) setSpot(a, group);
            }
        });
        frame.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { open(a[0], a[1]); }
        });
        frame.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { tileOptions(index, a); return true; }
        });
        frame.setTag(new Object[]{index, a});
        if (index == focusIndex) focusTile = frame;
        if (firstTile == null) firstTile = frame;
        return cell;
    }

    View dockItem(PackageManager pm, final String[] a) {
        final LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(px(12), 0, px(28), 0);
        item.setFocusable(true);
        item.setClickable(true);
        final GradientDrawable off = round(Color.TRANSPARENT, px(38), px(2), Color.TRANSPARENT);
        final GradientDrawable on = round(0x21FFFFFF, px(38), px(2), ACCENT);
        item.setBackground(off);

        View badge;
        Drawable icon = null;
        if (a[0].equals("#settings")) {
            try { icon = pm.getApplicationIcon("com.amazon.tv.settings.v2"); } catch (Throwable t) { }
        } else if (a[0].equals("#amazon")) {
            try { icon = pm.getApplicationIcon("com.amazon.tv.launcher"); } catch (Throwable t) { }
        } else if (a[0].equals("#ace")) {
            try { icon = pm.getApplicationIcon(AceLinks.pkg(this)); } catch (Throwable t) { }
        } else if (!a[0].startsWith("#")) {
            try { icon = pm.getApplicationIcon(a[0]); } catch (Throwable t) { }
        }
        if (icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            final float rr = px(30);
            iv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rr);
                }
            });
            iv.setClipToOutline(true);
            badge = iv;
        } else {
            TextView t = text(30, fExtra, ACCENT);
            t.setShadowLayer(0, 0, 0, 0);
            t.setGravity(Gravity.CENTER);
            t.setText(a[0].equals("#more") ? "+" : a[1].substring(0, 1));
            t.setBackground(round(0x1AFFFFFF, px(30), 0, 0));
            badge = t;
        }
        item.addView(badge, new LinearLayout.LayoutParams(px(60), px(60)));
        final TextView label = text(25, fBold, 0xFFFBEFEA);
        label.setShadowLayer(0, 0, 0, 0);
        label.setText(a[1]);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = px(14);
        item.addView(label, lp);

        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean has) {
                item.setBackground(has ? on : off);
                if (has) setSpot(a, "Herramientas");
            }
        });
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (a[0].equals("#settings")) {
                    openSettings();
                } else if (a[0].equals("#amazon")) {
                    goAmazon();
                } else if (a[0].equals("#more")) {
                    showAllApps();
                } else if (a[0].equals("#ace")) {
                    showAce();
                } else {
                    open(a[0], a[1]);
                }
            }
        });
        return item;
    }

    void open(String pkg, String name) {
        if (STREMIO.equals(pkg)) { openStremio(name); return; }
        launch(pkg, name);
    }

    void launch(String pkg, String name) {
        Intent i = launchIntent(getPackageManager(), pkg);
        if (i == null) { toast(name + " no está instalada"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Redirector.pause(1500);
        try {
            startActivity(i);
            if (pkg.startsWith("org.acestream")) AceLinks.aceStartedAt = System.currentTimeMillis();
        } catch (Throwable t) { toast("No se pudo abrir " + name); }
    }

    /**
     * Para el reproductor de AceStream si se ha quedado funcionando en segundo plano o en
     * ventanita (PiP) al salir con Atrás/Home: manda la tecla multimedia «Stop», que Ace Player
     * atiende cerrando el reproductor y el motor deja de descargar. No necesita permisos.
     */
    void stopBackgroundAce() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
            long t = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP, 0));
        } catch (Throwable ignored) { }
    }

    void openSettings() {
        Redirector.pause(1500);
        try {
            startActivity(new Intent().setClassName("com.amazon.tv.launcher",
                    "com.amazon.tv.launcher.ui.MainSettingsActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Throwable t2) { toast("No se pudieron abrir los ajustes"); }
        }
    }

    void goAmazon() {
        Redirector.amazonHome();
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setClassName("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.HomeActivity_vNext")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) { toast("No se pudo abrir el menú de Amazon"); }
    }

    // ---- AceStream: enlaces enviados desde el móvil ----
    android.app.Dialog aceDialog;

    android.graphics.drawable.StateListDrawable focusBg(float r, int base) {
        android.graphics.drawable.StateListDrawable d = new android.graphics.drawable.StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, round(0x26FFFFFF, r, px(3), ACCENT));
        d.addState(new int[]{}, round(base, r, Math.max(1, px(2)), 0x24FFFFFF));
        return d;
    }

    TextView pill(String label) {
        TextView t = text(26, fBold, INK);
        t.setShadowLayer(0, 0, 0, 0);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(30), 0, px(30), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setBackground(focusBg(px(18), 0x14FFFFFF));
        return t;
    }

    /** Ventana de AceStream: guardados + buscador en directo (search-ace.stream + motor local) + M3U. */
    void showAce() {
        PhoneServer.ensure(this);
        if (aceDialog != null && aceDialog.isShowing()) aceDialog.dismiss();
        final String addr = PhoneServer.address(this);
        final android.app.Dialog dlg = new android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar);
        aceDialog = dlg;
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(44), px(34), px(44), px(28));
        box.setBackground(round(0xF5231026, px(28), Math.max(1, px(2)), 0x24FFFFFF));

        TextView title = text(46, fExtra, INK);
        title.setShadowLayer(0, 0, 0, 0);
        title.setText("AceStream · Buscador y Favoritos");
        box.addView(title);
        final TextView status = text(24, fMedium, MUTED);
        status.setShadowLayer(0, 0, 0, 0);
        LinearLayout.LayoutParams stl = new LinearLayout.LayoutParams(-1, -2);
        stl.topMargin = px(2);
        box.addView(status, stl);

        final TextView vpnWarn = text(23, fBold, ACCENT);
        vpnWarn.setShadowLayer(0, 0, 0, 0);
        vpnWarn.setText("⚠  VPN conectada · Proton VPN gratis bloquea AceStream (P2P) y los canales no cargan. Pulsa OK aquí para desconectarla.");
        vpnWarn.setPadding(px(16), px(8), px(16), px(8));
        vpnWarn.setFocusable(true);
        vpnWarn.setClickable(true);
        vpnWarn.setBackground(focusBg(px(14), 0x22FFC870));
        vpnWarn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                disconnectVpnThen(new Runnable() { public void run() {
                    vpnWarn.setVisibility(AceLinks.vpnOn(MainActivity.this) ? View.VISIBLE : View.GONE);
                } });
            }
        });
        vpnWarn.setVisibility(AceLinks.vpnOn(this) ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams vwl = new LinearLayout.LayoutParams(-1, -2);
        vwl.topMargin = px(8);
        box.addView(vpnWarn, vwl);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final android.widget.EditText q = new android.widget.EditText(this);
        q.setSingleLine(true);
        q.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        q.setHint("Buscar canal en search-ace.stream (ej. LaLiga, DAZN, F1…)");
        q.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(28));
        q.setTypeface(fMedium);
        q.setTextColor(INK);
        q.setHintTextColor(0x99E6D3CB);
        q.setPadding(px(24), 0, px(24), 0);
        q.setBackground(focusBg(px(18), 0x33000000));
        row.addView(q, new LinearLayout.LayoutParams(0, px(80), 1));
        final TextView bSearch = pill("Buscar");
        final TextView bAll = pill("Actualizar");
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, px(80));
        bl.leftMargin = px(14);
        row.addView(bSearch, bl);
        LinearLayout.LayoutParams bl2 = new LinearLayout.LayoutParams(-2, px(80));
        bl2.leftMargin = px(14);
        row.addView(bAll, bl2);
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, -2);
        rl.topMargin = px(18);
        box.addView(row, rl);

        final android.widget.ListView list = new android.widget.ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(px(6));
        final Drawable ringOn = round(0x21FFFFFF, px(18), px(3), ACCENT);
        final Drawable ringOff = new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT);
        list.setSelector(ringOff);
        list.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) { list.setSelector(has ? ringOn : ringOff); }
        });
        list.setDrawSelectorOnTop(false);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setItemsCanFocus(false);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, 0, 1);
        ll.topMargin = px(18);
        box.addView(list, ll);

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.Bitmap qr = QrCode.bitmap("http://" + addr, px(156));
        if (qr != null) {
            ImageView qv = new ImageView(this);
            qv.setImageBitmap(qr);
            qv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            final float qr8 = px(14);
            qv.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), qr8); }
            });
            qv.setClipToOutline(true);
            LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(px(156), px(156));
            ql.rightMargin = px(22);
            foot.addView(qv, ql);
        }
        LinearLayout hintCol = new LinearLayout(this);
        hintCol.setOrientation(LinearLayout.VERTICAL);
        TextView hintT = text(25, fBold, INK);
        hintT.setShadowLayer(0, 0, 0, 0);
        hintT.setText("Con el mando: OK reproduce · Mantén OK o ☰ guarda en Favoritos ★");
        hintCol.addView(hintT);
        TextView hint = text(20, fMedium, LABEL);
        hint.setShadowLayer(0, 0, 0, 0);
        hint.setText("Buscador integrado con search-ace.stream y motor local.\n"
                + "Opcional: escanea el QR (" + addr + ") si quieres mandar un enlace desde el móvil.");
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-2, -2);
        hl.topMargin = px(4);
        hintCol.addView(hint, hl);
        foot.addView(hintCol, new LinearLayout.LayoutParams(0, -2, 1));
        final TextView bApp = pill("Abrir app AceStream");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-2, px(70));
        al.leftMargin = px(14);
        foot.addView(bApp, al);
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2);
        fl.topMargin = px(16);
        box.addView(foot, fl);

        final List<String[]> items = new ArrayList<>();   // {tipo s|f, nombre, id}
        final List<String[]> feed = new ArrayList<>();
        final String[] err = {null};
        final boolean[] loading = {false};

        final android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            public int getCount() { return items.size(); }
            public Object getItem(int p) { return items.get(p); }
            public long getItemId(int p) { return p; }
            public View getView(int p, View conv, ViewGroup parent) {
                LinearLayout v = (LinearLayout) conv;
                if (v == null) {
                    v = new LinearLayout(MainActivity.this);
                    v.setOrientation(LinearLayout.VERTICAL);
                    v.setPadding(px(26), px(12), px(26), px(12));
                    TextView n = text(29, fBold, INK);
                    n.setShadowLayer(0, 0, 0, 0);
                    n.setSingleLine(true);
                    n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    TextView sub = text(20, fMedium, LABEL);
                    sub.setShadowLayer(0, 0, 0, 0);
                    v.addView(n);
                    v.addView(sub);
                }
                String[] x = items.get(p);
                boolean saved = x[0].equals("s");
                ((TextView) v.getChildAt(0)).setText((saved ? "★  " : "▶  ") + x[1]);
                TextView sub = (TextView) v.getChildAt(1);
                String extra = x.length > 3 && !x[3].isEmpty() ? "  ·  " + x[3] : "";
                sub.setText((saved ? "Favorito guardado" : "Canal en directo") + extra + "  ·  " + x[2].substring(0, 8) + "…" + x[2].substring(34));
                sub.setTextColor(saved ? ACCENT : LABEL);
                return v;
            }
        };
        list.setAdapter(adapter);

        final Runnable render = new Runnable() {
            public void run() {
                vpnWarn.setVisibility(AceLinks.vpnOn(MainActivity.this) ? View.VISIBLE : View.GONE);
                String query = q.getText().toString().trim();
                items.clear();
                Set<String> savedIds = new HashSet<>();
                List<String[]> saved = AceLinks.list(MainActivity.this);
                for (String[] l : AceSearcher.filter(saved, query)) {
                    items.add(new String[]{"s", l[0], l[1], "", l.length > 2 ? l[2] : ""});
                }
                for (String[] l : saved) savedIds.add(l[1]);
                for (String[] f : AceSearcher.filter(feed, query)) {
                    if (!savedIds.contains(f[1])) items.add(new String[]{"f", f[0], f[1],
                            f.length > AceSearcher.INFO ? f[AceSearcher.INFO] : "",
                            f.length > AceSearcher.TYPE ? f[AceSearcher.TYPE] : ""});
                }
                adapter.notifyDataSetChanged();
                if (loading[0]) {
                    status.setText("Buscando canales en search-ace.stream…");
                } else if (!query.isEmpty()) {
                    status.setText(items.size() + (items.size() == 1 ? " canal encontrado" : " canales encontrados")
                            + " para «" + query + "»");
                } else if (err[0] != null && feed.isEmpty()) {
                    status.setText("No se pudo conectar con search-ace.stream (" + err[0] + "). Revisa la conexión y pulsa Actualizar.");
                } else {
                    status.setText(feed.size() + " canales en directo disponibles  ·  " + savedIds.size() + " favoritos guardados");
                }
            }
        };

        final Runnable[] load = new Runnable[2];   // 0: normal, 1: forzando descarga
        for (int i = 0; i < 2; i++) {
            final boolean refresh = i == 1;
            load[i] = new Runnable() {
                public void run() {
                    loading[0] = true;
                    render.run();
                    AceSearcher.search(MainActivity.this, "", refresh, new AceSearcher.Callback() {
                        public void done(List<String[]> results, String error) {
                            loading[0] = false;
                            feed.clear();
                            feed.addAll(results);
                            err[0] = error;
                            if (!dlg.isShowing()) return;
                            render.run();
                            if (q.hasFocus() || bAll.hasFocus() || bSearch.hasFocus() || vpnWarn.hasFocus()) return;
                            if (!items.isEmpty()) list.requestFocus();
                        }
                    });
                }
            };
        }

        final Runnable runLiveSearch = new Runnable() {
            public void run() {
                final String query = q.getText().toString().trim();
                try {
                    ((android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                            .hideSoftInputFromWindow(q.getWindowToken(), 0);
                } catch (Throwable t) { }
                if (query.isEmpty()) {
                    load[0].run();
                    return;
                }
                loading[0] = true;
                render.run();
                AceSearcher.searchLive(MainActivity.this, query, new AceSearcher.Callback() {
                    public void done(List<String[]> results, String error) {
                        loading[0] = false;
                        // Añadimos nuevos hallazgos al feed en memoria sin perder los anteriores
                        Set<String> existing = new HashSet<>();
                        for (String[] f : feed) existing.add(f[1]);
                        for (String[] r : results) {
                            if (existing.add(r[1])) feed.add(r);
                        }
                        err[0] = error;
                        if (!dlg.isShowing()) return;
                        render.run();
                        if (!items.isEmpty()) { list.requestFocus(); list.setSelection(0); }
                    }
                });
            }
        };

        q.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
            public void onTextChanged(CharSequence c, int a, int b, int d) { }
            public void afterTextChanged(android.text.Editable e) { render.run(); }
        });
        q.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int actionId, KeyEvent ev) {
                runLiveSearch.run();
                return true;
            }
        });
        bSearch.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { runLiveSearch.run(); }
        });
        bAll.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { q.setText(""); load[1].run(); }
        });
        bApp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                String p = AceLinks.pkg(MainActivity.this);
                if ("org.acestream.node.web".equals(p) && android.os.Build.VERSION.SDK_INT < 26) {
                    Redirector.pause(1500);
                    try {
                        startActivity(new Intent().setClassName(p, "org.acestream.tvapp.main.MainActivity")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        return;
                    } catch (Throwable t) { }
                }
                open(p, "AceStream");
            }
        });

        list.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            public void onItemClick(android.widget.AdapterView<?> a, View v, int p, long id) {
                if (p < 0 || p >= items.size()) return;
                final String id2 = items.get(p)[2];
                final String type2 = items.get(p).length > 4 ? items.get(p)[4] : null;
                // Proton VPN gratis corta el tráfico P2P ("Free servers do not allow P2P traffic"):
                // con la VPN puesta el canal se queda en «Prebuffering 0%». Sin VPN funciona.
                if (!AceLinks.vpnOn(MainActivity.this)) {
                    if (!AceLinks.play(MainActivity.this, id2, type2)) toast("No se pudo abrir AceStream");
                    return;
                }
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("La VPN está conectada")
                        .setMessage("Proton VPN gratis bloquea el P2P que usa AceStream: el canal se quedará en "
                                + "«Prebuffering 0%» (y Proton pausa toda la conexión).\n\n"
                                + "Pulsa «Desconectar VPN y ver». Para Stremio la vuelves a conectar en Proton.")
                        .setPositiveButton("Desconectar VPN y ver", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                disconnectVpnThen(new Runnable() { public void run() {
                                    if (!AceLinks.play(MainActivity.this, id2, type2)) toast("No se pudo abrir AceStream");
                                } });
                            }
                        })
                        .setNegativeButton("Ver igualmente", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) {
                                if (!AceLinks.play(MainActivity.this, id2, type2)) toast("No se pudo abrir AceStream");
                            }
                        })
                        .show();
            }
        });
        list.setOnItemLongClickListener(new android.widget.AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(android.widget.AdapterView<?> a, View v, int p, long id) {
                if (p < 0 || p >= items.size()) return true;
                String[] x = items.get(p);
                if (x[0].equals("s")) {
                    dlg.dismiss();
                    linkOptions(new String[]{x[1], x[2]});
                } else {
                    String n = AceLinks.save(MainActivity.this, x[1], x[2], x.length > 4 ? x[4] : null);
                    toast("Guardado en favoritos: " + n);
                    render.run();
                }
                return true;
            }
        });
        dlg.setOnKeyListener(new android.content.DialogInterface.OnKeyListener() {
            public boolean onKey(android.content.DialogInterface d, int keyCode, KeyEvent e) {
                if (keyCode != KeyEvent.KEYCODE_MENU) return false;
                if (e.getAction() == KeyEvent.ACTION_UP && list.hasFocus()) {
                    int p = list.getSelectedItemPosition();
                    if (p >= 0 && p < items.size()) list.getOnItemLongClickListener().onItemLongClick(list, null, p, p);
                }
                return true;
            }
        });

        dlg.setContentView(box);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.setLayout(px(1260), px(980));
            w.setDimAmount(0.65f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
        }
        dlg.show();
        render.run();
        if (!items.isEmpty()) { list.requestFocus(); list.setSelection(0); } else q.requestFocus();
        load[0].run();
    }

    void phoneHelp(String addr) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Enviar enlaces desde el móvil")
                .setMessage("Con el móvil en la misma wifi, abre en el navegador:\n\n"
                        + "http://" + addr + "\n\n"
                        + "Pega el enlace acestream:// (o el ID) y pulsa «Ver en la tele» o «Guardar». "
                        + "Lo guardado aparece aquí para abrirlo con el mando.\n\n"
                        + "Consejo: añade esa página a la pantalla de inicio del móvil.")
                .setPositiveButton("Entendido", null)
                .show();
    }

    void linkOptions(final String[] l) {
        final String[] opts = {"Reproducir", "Subir en la lista", "Borrar"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(l[0])
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) {
                            if (!AceLinks.play(MainActivity.this, l[1])) toast("No se pudo abrir AceStream");
                        } else if (which == 1) {
                            AceLinks.moveUp(MainActivity.this, l[1]); showAce();
                        } else {
                            AceLinks.remove(MainActivity.this, l[1]); toast("Borrado: " + l[0]); showAce();
                        }
                    }
                })
                .show();
    }

    void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    void showAllApps() {
        final PackageManager pm = getPackageManager();
        final List<String[]> apps = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(getPackageName());
        for (String p0 : verList()) seen.add(p0);
        for (String[] a : TOOLS) seen.add(a[0]);
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String c : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(c);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (seen.add(p)) apps.add(new String[]{p, String.valueOf(ri.loadLabel(pm))});
            }
        }
        Collections.sort(apps, new java.util.Comparator<String[]>() { public int compare(String[] x, String[] y) { return x[1].compareToIgnoreCase(y[1]); } });
        String[] names = new String[apps.size()];
        for (int i = 0; i < names.length; i++) names[i] = apps.get(i)[1];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Más apps · elige una para abrirla o añadirla a Ver")
                .setItems(names, new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface d, int which) {
                    String[] a = apps.get(which);
                    appOptions(a);
                } })
                .show();
    }

    // ---- Lista de apps de la fila "Ver" ----
    List<String> verList() {
        String raw = getSharedPreferences("inicio", MODE_PRIVATE).getString("ver", DEFAULT_VER);
        List<String> out = new ArrayList<>();
        for (String p0 : raw.split(",")) if (!p0.trim().isEmpty() && !out.contains(p0.trim())) out.add(p0.trim());
        return out;
    }

    void saveVer(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String p0 : l) { if (sb.length() > 0) sb.append(','); sb.append(p0); }
        getSharedPreferences("inicio", MODE_PRIVATE).edit().putString("ver", sb.toString()).apply();
    }

    String[] describe(PackageManager pm, String pkg) {
        for (String[] k : KNOWN) if (k[0].equals(pkg)) return k;
        String label = pkg;
        try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Throwable t) { }
        return new String[]{pkg, label, "Aplicación"};
    }

    /** Opciones de una app de la fila Ver (pulsación larga de OK o botón menú). */
    void tileOptions(final int index, final String[] a) {
        final String[] opts = {"Mover a la izquierda", "Mover a la derecha", "Quitar de Ver"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a[1])
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        // Trabajar sobre las apps visibles, en su orden
                        List<String> visible = new ArrayList<>();
                        PackageManager pm = getPackageManager();
                        Set<String> names = new HashSet<>();
                        for (String p0 : verList()) {
                            if (launchIntent(pm, p0) == null) continue;
                            if (!names.add(describe(pm, p0)[1])) continue;
                            visible.add(p0);
                        }
                        int i = visible.indexOf(a[0]);
                        if (i < 0) return;
                        if (which == 0 && i > 0) { visible.remove(i); visible.add(i - 1, a[0]); focusIndex = i - 1; }
                        else if (which == 1 && i < visible.size() - 1) { visible.remove(i); visible.add(i + 1, a[0]); focusIndex = i + 1; }
                        else if (which == 2) { visible.remove(i); focusIndex = Math.max(0, i - 1); }
                        else return;
                        saveVer(visible);
                        recreate();
                    }
                })
                .show();
    }

    /** Elegida una app en "Más apps": abrirla o añadirla a la fila Ver. */
    void appOptions(final String[] a) {
        final String[] opts = {"Abrir", "Añadir a Ver"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a[1])
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (which == 0) { open(a[0], a[1]); return; }
                        List<String> l = verList();
                        if (!l.contains(a[0])) l.add(a[0]);
                        saveVer(l);
                        focusIndex = 999;
                        recreate();
                    }
                })
                .show();
    }

    void updateClock() {
        Date now = new Date();
        clock.setText(hourFmt.format(now));
        String d = dateFmt.format(now);
        date.setText(d.isEmpty() ? d : Character.toUpperCase(d.charAt(0)) + d.substring(1));
        boolean on = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            for (Network nw : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(nw);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) on = true;
            }
        } catch (Throwable t) { }
        Bloqueos.Result b = Bloqueos.last;
        boolean block = !on && b != null && !b.vpn && b.laliga();
        Updater.Info up = Updater.last;
        boolean upd = up != null && up.newer;
        String label = "●  " + (on ? "VPN activa" : "VPN desactivada") + (block ? "  ·  ⛔ Bloqueos activos" : "")
                + (upd ? "  ·  ⬆ Inicio " + up.versionName : "");
        SpannableString sp = new SpannableString(label);
        sp.setSpan(new ForegroundColorSpan(on || block ? ACCENT : 0xFF9A8A90), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (block) sp.setSpan(new ForegroundColorSpan(ACCENT), label.indexOf("⛔"), label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (upd) sp.setSpan(new ForegroundColorSpan(ACCENT), label.indexOf("⬆"), label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        vpn.setText(sp);
        // Comprobar si hay bloqueos cada 10 min mientras Inicio está a la vista (solo sin VPN:
        // con la VPN puesta no se ve lo que bloquea la operadora, ni hace falta)
        if (!on && resumed && !Bloqueos.fresh(Bloqueos.REFRESH_MS)) {
            Bloqueos.check(this, Bloqueos.REFRESH_MS, new Bloqueos.Callback() {
                public void done(Bloqueos.Result r) { if (resumed) updateClockOnly(); }
            });
        }
    }

    /** Repinta la cabecera sin lanzar otra comprobación. */
    void updateClockOnly() {
        try { updateClock(); } catch (Throwable ignored) { }
    }

    // ---- Actualizaciones de Inicio ----
    void offerUpdate(final Updater.Info info) {
        Updater.markPrompted(this, info);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Nueva versión de Inicio: " + info.versionName)
                .setMessage((info.notas.isEmpty() ? "" : info.notas + "\n\n")
                        + "Tienes la " + Updater.currentName(this) + ". Se descarga y Android te pedirá "
                        + "pulsar «Instalar». No se pierde nada de lo guardado.")
                .setPositiveButton("Actualizar ahora", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int w) {
                        Updater.downloadAndInstall(MainActivity.this, info, new Updater.Progress() {
                            public void step(String m) { toast(m); }
                            public void failed(String m) { toast(m); }
                        });
                    }
                })
                .setNegativeButton("Más tarde", null)
                .show();
    }

    // ---- Bloqueos (fútbol, F1…) y VPN ----
    static final String STREMIO = "com.stremio.one";
    final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    Runnable afterVpnOff;

    /**
     * Abre Stremio. Si hay bloqueos activos y la VPN está apagada, avisa antes: Torrentio y la API
     * de Stremio están en Cloudflare y no cargarán. (Torrentio + Real-Debrid no usa P2P en la tele,
     * así que Proton VPN gratis no da problemas con Stremio.)
     */
    void openStremio(final String name) {
        if (AceLinks.vpnOn(this)) { launch(STREMIO, name); return; }
        if (!Bloqueos.fresh(Bloqueos.FRESH_MS)) toast("Comprobando bloqueos…");
        Bloqueos.check(this, Bloqueos.FRESH_MS, new Bloqueos.Callback() {
            public void done(Bloqueos.Result r) {
                updateClockOnly();
                if (!r.laliga()) { launch(STREMIO, name); return; }
                new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("⛔ Hay bloqueos activos ahora mismo")
                        .setMessage("Tu operadora está bloqueando IPs de Cloudflare (lo hace durante el fútbol y otros "
                                + "eventos), y ahí están Torrentio y Stremio: los addons no cargarán.\n\n"
                                + "Conecta Proton VPN (Conexión rápida) y vuelve a abrir Stremio. "
                                + "Con Torrentio + Real-Debrid la VPN gratis funciona bien."
                                + (r.detail.isEmpty() ? "" : "\n\nNo responde: " + r.detail))
                        .setPositiveButton("Abrir Proton VPN", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) { launch(AceLinks.PROTON, "Proton VPN"); }
                        })
                        .setNegativeButton("Abrir Stremio igualmente", new android.content.DialogInterface.OnClickListener() {
                            public void onClick(android.content.DialogInterface d, int w) { launch(STREMIO, name); }
                        })
                        .show();
            }
        });
    }

    /**
     * Desconecta la VPN (Proton no deja hacerlo desde fuera: ver VpnOff) y, cuando ya no está,
     * ejecuta after. La primera vez Android pide permiso con una ventana «Solicitud de conexión».
     */
    void disconnectVpnThen(Runnable after) {
        afterVpnOff = after;
        // Si Android pide permiso, se espera a la respuesta (onActivityResult) antes de vigilar
        if (!VpnOff.run(this)) waitVpnOff(0);
    }

    void waitVpnOff(final int tries) {
        main.postDelayed(new Runnable() {
            public void run() {
                if (!AceLinks.vpnOn(MainActivity.this)) {
                    toast("VPN desconectada");
                    updateClockOnly();
                    Runnable a = afterVpnOff;
                    afterVpnOff = null;
                    if (a != null) a.run();
                } else if (tries < 30) {
                    waitVpnOff(tries + 1);   // hasta ~9 s
                } else {
                    afterVpnOff = null;
                    toast("No se pudo desconectar la VPN: ábrela y pulsa Desconectar");
                }
            }
        }, 300);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == VpnOff.REQUEST) {
            if (res == Activity.RESULT_OK) { VpnOff.start(this); waitVpnOff(0); }
            else { afterVpnOff = null; toast("Sin ese permiso no puedo desconectar la VPN"); }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Redirector.lastPkg = getPackageName();
        Redirector.lastCls = MainActivity.class.getName();
        // Si se viene de AceStream, cerrar su reproductor por si sigue en segundo plano o en PiP
        if (AceLinks.aceStartedAt != 0) {
            AceLinks.aceStartedAt = 0;
            stopBackgroundAce();
        }
        // ¿Hay versión nueva de Inicio? (como mucho una consulta cada 6 h)
        Updater.check(this, false, new Updater.Callback() {
            public void done(Updater.Info info) {
                if (!resumed) return;
                updateClockOnly();
                if (Updater.shouldPrompt(MainActivity.this, info)) offerUpdate(info);
            }
        });
        Redirector.launcherShown();
        LogWatchService.ensure(this);
        PhoneServer.ensure(this);
        updateClock();
        if (tick == null) {
            tick = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { updateClock(); }
            };
            IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            registerReceiver(tick, f);
        }
        View cur = getCurrentFocus();
        if (focusTile != null) { focusTile.requestFocus(); focusTile = null; focusIndex = -1; }
        else if ((cur == null || !cur.isFocusable()) && firstTile != null) firstTile.requestFocus();
    }

    /** Guarda una imagen de Inicio (sin el reloj) para enseñarla al instante al volver. */
    void snapshot() {
        try {
            View d = getWindow().getDecorView();
            int w = d.getWidth(), hh = d.getHeight();
            if (w <= 0 || hh <= 0) return;
            android.graphics.Bitmap bmp = Redirector.snapshot;
            if (bmp == null || bmp.getWidth() != w || bmp.getHeight() != hh) {
                bmp = android.graphics.Bitmap.createBitmap(w, hh, android.graphics.Bitmap.Config.RGB_565);
            }
            int cv = clock.getVisibility(), dv = date.getVisibility();
            clock.setVisibility(View.INVISIBLE);
            date.setVisibility(View.INVISIBLE);
            d.draw(new android.graphics.Canvas(bmp));
            clock.setVisibility(cv);
            date.setVisibility(dv);
            Redirector.snapshot = bmp;
            Redirector.scale = s;
            Redirector.clockFont = fLight;
            Redirector.dateFont = fMedium;
        } catch (Throwable t) { }
    }

    @Override
    protected void onPause() {
        snapshot();
        resumed = false;
        if (tick != null) { try { unregisterReceiver(tick); } catch (Throwable t) { } tick = null; }
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (firstTile != null) firstTile.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (firstTile != null) firstTile.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f != null && f.getTag() instanceof Object[]) {
                Object[] t = (Object[]) f.getTag();
                tileOptions((Integer) t[0], (String[]) t[1]);
            } else {
                showAllApps();
            }
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }
}
