package tv.inicio.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;

/** Vigilante por accesibilidad (Fire OS 7 y posteriores, Android TV y Google TV). */
public class GuardService extends AccessibilityService {

    static volatile GuardService inst;
    BroadcastReceiver screenOn;

    @Override
    protected void onServiceConnected() {
        inst = this;
        PhoneServer.ensure(this);
        if (screenOn == null) {
            screenOn = new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent i) { Redirector.screenOn(GuardService.this); }
            };
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        }
        if (SystemClock.elapsedRealtime() < 3 * 60 * 1000L) {
            Redirector.h.postDelayed(new Runnable() {
                public void run() {
                    if (MainActivity.resumed || Redirector.stockMode) return;
                    // En una tele puede arrancar directamente en la TDT o en un HDMI: solo se abre
                    // Inicio si lo que hay delante es el inicio original (o aún no se sabe qué hay)
                    String p = Redirector.lastPkg;
                    if (p == null || Redirector.isStockPkg(p)) Redirector.launch(GuardService.this, false);
                }
            }, 1500);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = e.getPackageName();
        CharSequence c = e.getClassName();
        Redirector.onShown(this, p == null ? null : p.toString(), c == null ? null : c.toString(), true);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        Redirector.hideOverlay();
        if (screenOn != null) { try { unregisterReceiver(screenOn); } catch (Throwable ignored) { } screenOn = null; }
        inst = null;
        super.onDestroy();
    }
}
