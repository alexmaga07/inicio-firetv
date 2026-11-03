package tv.inicio.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;

/**
 * Desconecta la VPN que haya activa (p. ej. Proton) en un clic.
 *
 * Proton VPN no ofrece ninguna forma de desconectarla desde otra app. Pero Android solo permite
 * una VPN a la vez: si Inicio levanta un instante su propia VPN vacía (sin rutas, no captura
 * tráfico), el sistema revoca la anterior; al cerrarla enseguida, queda la conexión normal.
 * La primera vez Android pide permiso («Solicitud de conexión»); después ya no.
 * Para volver a conectar Proton, basta con abrirlo y pulsar Conexión rápida.
 */
public class VpnOff extends VpnService {
    static final int REQUEST = 4711;

    /**
     * Desde una actividad. Devuelve true si ha tenido que pedir el permiso (la respuesta llega a
     * onActivityResult con REQUEST, y entonces hay que llamar a start); false si ya ha desconectado.
     */
    static boolean run(Activity a) {
        Intent consent;
        try { consent = VpnService.prepare(a); } catch (Throwable t) { consent = null; }
        if (consent != null) {
            try { a.startActivityForResult(consent, REQUEST); return true; } catch (Throwable ignored) { }
        }
        start(a);
        return false;
    }

    static void start(Context c) {
        try { c.startService(new Intent(c, VpnOff.class)); } catch (Throwable ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(new Runnable() {
            public void run() {
                ParcelFileDescriptor tun = null;
                try {
                    tun = new Builder()
                            .setSession("Inicio · desconectar VPN")
                            .addAddress("10.255.254.1", 32)   // sin addRoute: no pasa ningún tráfico por aquí
                            .establish();
                    Thread.sleep(700);
                } catch (Throwable ignored) {
                } finally {
                    try { if (tun != null) tun.close(); } catch (Throwable ignored) { }
                    stopSelf();
                }
            }
        }, "inicio-vpnoff").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        stopSelf();
    }
}
