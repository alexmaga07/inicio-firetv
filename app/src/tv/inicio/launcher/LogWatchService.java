package tv.inicio.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Vigilante por registro del sistema (Fire OS 6). Lee los eventos
 * "am_resume_activity" y, cuando se reanuda el inicio de Amazon, vuelve a
 * Inicio. Necesita el permiso READ_LOGS concedido por ADB.
 */
public class LogWatchService extends Service {

    static volatile boolean running = false;
    Thread reader;
    volatile boolean stop = false;
    BroadcastReceiver screenOn;

    static boolean canRun(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED;
    }

    static void ensure(Context c) {
        if (running || GuardService.inst != null || !canRun(c)) return;
        try {
            Intent i = new Intent(c, LogWatchService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        PhoneServer.ensure(this);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("inicio", "Inicio", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, "inicio");
        } else {
            b = new Notification.Builder(this).setPriority(Notification.PRIORITY_MIN);
        }
        startForeground(1, b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle("Inicio")
                .setContentText("Botón Home activo").build());
        screenOn = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) { Redirector.screenOn(LogWatchService.this); }
        };
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        reader = new Thread(new Runnable() { public void run() { loop(); } }, "inicio-logwatch");
        reader.start();
        // Si el servicio arranca cuando el inicio de Amazon ya está delante (arranque en frío), actuar ya.
        if (android.os.SystemClock.elapsedRealtime() < BootReceiver.BOOT_WINDOW) {
            BootReceiver.scheduleChecks(this);
            new Thread(new Runnable() { public void run() {
                Redirector.h.post(new Runnable() { public void run() { BootReceiver.checkNow(LogWatchService.this); } });
            } }).start();
        }
    }

    void loop() {
        while (!stop) {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"logcat", "-b", "events", "-v", "brief",
                        "-T", "1", "am_resume_activity:I", "am_restart_activity:I", "am_create_activity:I",
                        "am_new_intent:I", "*:S"});
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (!stop && (line = r.readLine()) != null) {
                    // Formato: I/am_xxx( pid): [usuario,token,tarea,paquete/actividad,...]
                    int open = line.indexOf('[');
                    int close = line.lastIndexOf(']');
                    if (open < 0 || close < open) continue;
                    // Reanudar o lanzar (restart) cuentan como "se muestra"; al arrancar en frío
                    // el inicio de Amazon se crea y se lanza sin evento de reanudación.
                    final boolean resume = line.contains("am_resume_activity") || line.contains("am_restart_activity");
                    String[] f = line.substring(open + 1, close).split(",");
                    if (f.length < 4) continue;
                    String comp = f[3].trim();
                    int slash = comp.indexOf('/');
                    if (slash < 0) continue;
                    final String pkg = comp.substring(0, slash);
                    String cls = comp.substring(slash + 1);
                    if (cls.startsWith(".")) cls = pkg + cls;
                    final String fcls = cls;
                    Redirector.h.post(new Runnable() {
                        public void run() {
                            if (GuardService.inst != null) return;
                            if (resume) Redirector.onShown(LogWatchService.this, pkg, fcls, true);
                            else Redirector.onOtherApp(pkg);
                        }
                    });
                }
            } catch (Throwable ignored) {
            } finally {
                if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        if (reader != null) reader.interrupt();
        if (screenOn != null) { try { unregisterReceiver(screenOn); } catch (Throwable ignored) { } }
        Redirector.hideOverlay();
        super.onDestroy();
    }
}
