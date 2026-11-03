package tv.inicio.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Arranque del Fire Stick. Fire OS 6 entrega BOOT_COMPLETED a las apps con hasta
 * 1-2 minutos de retraso, así que se escuchan también avisos más tempranos
 * (LOCKED_BOOT_COMPLETED, cambio de hora por red) y se programan varias
 * comprobaciones. Solo se abre Inicio si lo que hay delante es el inicio de
 * Amazon, para no interrumpir nunca a quien ya está viendo algo.
 * También se recibe MY_PACKAGE_REPLACED: tras una actualización de Inicio se vuelve a
 * arrancar el vigilante del botón Home (en Fire OS 6 se para al reinstalar).
 */
public class BootReceiver extends BroadcastReceiver {

    static final String CHECK = "tv.inicio.launcher.CHECK";
    static final long BOOT_WINDOW = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        try { LogWatchService.ensure(c); } catch (Throwable ignored) { }
        if (!CHECK.equals(a)) {
            if (SystemClock.elapsedRealtime() < BOOT_WINDOW) scheduleChecks(c);
        }
        checkNow(c);
    }

    static void scheduleChecks(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            long[] delays = {4000, 10000, 20000, 35000, 60000};
            for (int k = 0; k < delays.length; k++) {
                Intent it = new Intent(c, BootReceiver.class).setAction(CHECK);
                PendingIntent pi = PendingIntent.getBroadcast(c, 100 + k, it, PendingIntent.FLAG_UPDATE_CURRENT);
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delays[k], pi);
            }
        } catch (Throwable ignored) { }
    }

    static void checkNow(Context c) {
        if (MainActivity.resumed || Redirector.amazonMode) return;
        if (!LogWatchService.canRun(c)) return; // Fire OS 7+: lo resuelve el servicio de accesibilidad
        String top = Redirector.lastActivity();
        if (top == null) return;
        if (top.contains("HomeActivity_vNext") || top.contains("FallbackHome")) {
            try {
                c.startActivity(new Intent(c, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (Throwable ignored) { }
        }
    }
}
