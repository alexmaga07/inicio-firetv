package tv.inicio.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

/**
 * Lógica común para volver a Inicio cuando aparece el inicio de Amazon.
 * La usan dos vigilantes: GuardService (accesibilidad, Fire OS 7+) y
 * LogWatchService (registro del sistema, Fire OS 6, donde Amazon bloquea
 * activar servicios de accesibilidad por ADB).
 */
final class Redirector {

    static final String AMAZON = "com.amazon.tv.launcher";
    static final String AMAZON_HOME = "HomeActivity_vNext";
    static final long AMAZON_MODE_MAX = 30 * 60 * 1000L;

    static volatile long pausedUntil = 0;
    /** Última app (y actividad) que se ha visto en primer plano, según los vigilantes. */
    static volatile String lastPkg, lastCls;
    static volatile boolean amazonMode = false;
    static volatile long amazonModeSince = 0;
    static long lastRedirect = 0;

    static final Handler h = new Handler(Looper.getMainLooper());
    static PendingIntent pending;
    static volatile android.graphics.Bitmap snapshot;
    static volatile float scale = 1f;
    static volatile android.graphics.Typeface clockFont, dateFont;
    static View overlay;
    static Context overlayCtx;

    private Redirector() { }

    static final Runnable fallback = new Runnable() {
        public void run() {
            if (!MainActivity.resumed && pending != null) {
                try { pending.send(); } catch (Throwable ignored) { }
            }
        }
    };
    static final Runnable hideLater = new Runnable() {
        public void run() { hideOverlay(); }
    };

    static void pause(long ms) { pausedUntil = SystemClock.elapsedRealtime() + ms; }

    static void amazonHome() {
        amazonMode = true;
        amazonModeSince = SystemClock.elapsedRealtime();
    }

    static boolean ignorable(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.equals("android")
                || pkg.equals("com.amazon.tv.ime") || pkg.equals("com.amazon.tv.settings.v2")
                || pkg.equals("tv.inicio.launcher") || pkg.startsWith("com.amazon.tv.quicksettings")
                || pkg.equals("com.amazon.vizzini");
    }

    /** Última actividad mostrada según el registro del sistema (necesita READ_LOGS). */
    static String lastActivity() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-b", "events", "-v", "brief", "-t", "400",
                    "am_resume_activity:I", "am_restart_activity:I", "am_new_intent:I", "*:S"});
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line, last = null;
            while ((line = r.readLine()) != null) {
                int o = line.indexOf('['), c = line.lastIndexOf(']');
                if (o < 0 || c < o) continue;
                String[] f = line.substring(o + 1, c).split(",");
                if (f.length >= 4 && f[3].contains("/")) last = f[3].trim();
            }
            return last;
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
    }

    /** Se ha abierto o creado una actividad de otra app: salir del modo Amazon. */
    static void onOtherApp(String pkg) {
        if (pkg != null && !AMAZON.equals(pkg) && amazonMode && !ignorable(pkg)) amazonMode = false;
    }

    /** Se ha mostrado una ventana/actividad. Llamar desde el hilo principal. */
    static void onShown(Context ctx, String pkg, String cls, boolean overlayAllowed) {
        if (pkg == null) return;
        if (pkg.equals("tv.inicio.launcher") || !ignorable(pkg)) { lastPkg = pkg; lastCls = cls; }
        if (!AMAZON.equals(pkg)) {
            if (amazonMode && !ignorable(pkg)) amazonMode = false;
            return;
        }
        if (cls == null || !cls.endsWith(AMAZON_HOME)) return;
        long now = SystemClock.elapsedRealtime();
        if (amazonMode && now - amazonModeSince < AMAZON_MODE_MAX) return;
        amazonMode = false;
        if (now < pausedUntil) return;
        if (now - lastRedirect < 800) return;
        lastRedirect = now;
        launch(ctx, overlayAllowed);
    }

    static void launch(Context ctx, boolean withOverlay) {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        if (withOverlay) showOverlay(ctx);
        Intent i = new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        pending = PendingIntent.getActivity(app, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            AlarmManager am = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            am.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis(), pending);
        } catch (Throwable t) {
            try { pending.send(); } catch (Throwable ignored) { }
        }
        h.removeCallbacks(fallback);
        h.removeCallbacks(hideLater);
        h.postDelayed(fallback, 2500);
        h.postDelayed(hideLater, 7000);
    }

    /** Al encender la tele: abrir Inicio directamente (no hay bloqueo tras Home). */
    /**
     * Al encenderse la pantalla. Fire OS devuelve sola la app que había antes de dormirse
     * (p. ej. una película pausada en Stremio), así que Inicio solo se abre si lo que queda
     * delante es el inicio de Amazon (o no se sabe qué hay). Antes se abría siempre y sacaba
     * al usuario de Stremio al volver de la suspensión (5 min sin tocar el mando).
     */
    static void screenOn(final Context ctx) {
        h.postDelayed(new Runnable() {
            public void run() {
                if (MainActivity.resumed || amazonMode) return;
                String p = lastPkg, c = lastCls;
                boolean amazonHome = p == null || (AMAZON.equals(p) && c != null && c.endsWith(AMAZON_HOME));
                if (!amazonHome) return;
                try {
                    ctx.startActivity(new Intent(ctx, MainActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } catch (Throwable t) { launch(ctx, false); }
            }
        }, 1500);   // margen para que Fire OS reanude la app anterior y el vigilante lo vea
    }

    static void launcherShown() {
        amazonMode = false;
        h.post(new Runnable() {
            public void run() {
                h.removeCallbacks(fallback);
                h.removeCallbacks(hideLater);
                hideOverlay();
            }
        });
    }

    static void showOverlay(Context ctx) {
        if (overlay != null) return;
        try {
            int type;
            if (ctx instanceof AccessibilityService) {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            } else if (Build.VERSION.SDK_INT >= 26) {
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
            }
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            View v = buildOverlay(ctx, !(ctx instanceof AccessibilityService));
            // En Fire OS 6 la espera es de ~5 s: la capa se queda con el foco para que
            // las pulsaciones no lleguen al inicio de Amazon que hay debajo.
            int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN;
            if (ctx instanceof AccessibilityService) flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type, flags, PixelFormat.OPAQUE);
            wm.addView(v, lp);
            overlay = v;
            overlayCtx = ctx;
        } catch (Throwable t) {
            overlay = null;
        }
    }

    /** Imagen de Inicio guardada + reloj en vivo, colocado igual que en la pantalla real. */
    static View buildOverlay(Context ctx, boolean loading) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(ctx);
        f.setBackgroundResource(R.drawable.fondo);
        android.graphics.Bitmap b = snapshot;
        if (b == null) { if (loading) addLoading(ctx, f); return f; }
        android.widget.ImageView iv = new android.widget.ImageView(ctx);
        iv.setImageBitmap(b);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        f.addView(iv, new android.widget.FrameLayout.LayoutParams(-1, -1));
        float s = scale;
        android.widget.LinearLayout times = new android.widget.LinearLayout(ctx);
        times.setOrientation(android.widget.LinearLayout.VERTICAL);
        times.setPadding(Math.round(120 * s), Math.round(76 * s), 0, 0);
        java.util.Date now = new java.util.Date();
        android.widget.TextView c = new android.widget.TextView(ctx);
        c.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(150 * s));
        if (clockFont != null) c.setTypeface(clockFont);
        c.setTextColor(MainActivity.INK);
        c.setIncludeFontPadding(false);
        c.setLetterSpacing(-0.02f);
        c.setShadowLayer(Math.round(14 * s), 0, Math.round(2 * s), 0xB31C0C1E);
        c.setText(new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(now));
        android.widget.TextView d = new android.widget.TextView(ctx);
        d.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(30 * s));
        if (dateFont != null) d.setTypeface(dateFont);
        d.setTextColor(MainActivity.MUTED);
        d.setIncludeFontPadding(false);
        d.setShadowLayer(Math.round(14 * s), 0, Math.round(2 * s), 0xB31C0C1E);
        d.setPadding(Math.round(6 * s), Math.round(6 * s), 0, 0);
        String ds = new java.text.SimpleDateFormat("EEEE, d 'de' MMMM", new java.util.Locale("es", "ES")).format(now);
        d.setText(ds.isEmpty() ? ds : Character.toUpperCase(ds.charAt(0)) + ds.substring(1));
        times.addView(c);
        times.addView(d);
        f.addView(times, new android.widget.FrameLayout.LayoutParams(-2, -2));
        if (loading) addLoading(ctx, f);
        return f;
    }

    /**
     * Fire OS 6 (Android 7) bloquea abrir apps unos 5 s tras pulsar Home. Mientras tanto se
     * oscurece un poco la pantalla y se muestra "Cargando Inicio" con una barra que se llena.
     */
    static void addLoading(Context ctx, android.widget.FrameLayout f) {
        float s = scale > 0 ? scale : ctx.getResources().getDisplayMetrics().widthPixels / 1920f;
        View dim = new View(ctx);
        dim.setBackgroundColor(0x661C0C1E);
        f.addView(dim, new android.widget.FrameLayout.LayoutParams(-1, -1));

        android.widget.LinearLayout pill = new android.widget.LinearLayout(ctx);
        pill.setOrientation(android.widget.LinearLayout.VERTICAL);
        pill.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        pill.setPadding(Math.round(44 * s), Math.round(26 * s), Math.round(44 * s), Math.round(26 * s));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xE61E0E20);
        bg.setCornerRadius(Math.round(36 * s));
        bg.setStroke(Math.max(1, Math.round(1 * s)), 0x26FFFFFF);
        pill.setBackground(bg);

        android.widget.LinearLayout line = new android.widget.LinearLayout(ctx);
        line.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        line.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.ProgressBar spin = new android.widget.ProgressBar(ctx);
        spin.setIndeterminate(true);
        try { spin.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(MainActivity.ACCENT)); } catch (Throwable ignored) { }
        line.addView(spin, new android.widget.LinearLayout.LayoutParams(Math.round(44 * s), Math.round(44 * s)));
        android.widget.TextView t = new android.widget.TextView(ctx);
        t.setText("Cargando Inicio…");
        t.setTextColor(MainActivity.INK);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Math.round(30 * s));
        if (dateFont != null) t.setTypeface(dateFont);
        android.widget.LinearLayout.LayoutParams tl = new android.widget.LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = Math.round(20 * s);
        line.addView(t, tl);
        pill.addView(line);

        android.widget.FrameLayout track = new android.widget.FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable tbg = new android.graphics.drawable.GradientDrawable();
        tbg.setColor(0x33FFFFFF);
        tbg.setCornerRadius(Math.round(4 * s));
        track.setBackground(tbg);
        final View bar = new View(ctx);
        android.graphics.drawable.GradientDrawable bbg = new android.graphics.drawable.GradientDrawable();
        bbg.setColor(MainActivity.ACCENT);
        bbg.setCornerRadius(Math.round(4 * s));
        bar.setBackground(bbg);
        track.addView(bar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        android.widget.LinearLayout.LayoutParams kl = new android.widget.LinearLayout.LayoutParams(Math.round(320 * s), Math.round(8 * s));
        kl.topMargin = Math.round(20 * s);
        pill.addView(track, kl);
        bar.setPivotX(0);
        bar.setScaleX(0.04f);
        bar.animate().scaleX(1f).setDuration(4600).setInterpolator(new android.view.animation.DecelerateInterpolator(0.6f)).start();

        android.widget.FrameLayout.LayoutParams pl = new android.widget.FrameLayout.LayoutParams(-2, -2);
        pl.gravity = android.view.Gravity.CENTER;
        f.addView(pill, pl);
    }

    static void hideOverlay() {
        if (overlay == null) return;
        try {
            ((WindowManager) overlayCtx.getSystemService(Context.WINDOW_SERVICE)).removeView(overlay);
        } catch (Throwable ignored) { }
        overlay = null;
        overlayCtx = null;
    }
}
