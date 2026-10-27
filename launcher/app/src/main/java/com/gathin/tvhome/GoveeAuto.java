package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * govee_auto: the backlight follows the screen, on when the TV wakes and off when it goes to standby. Registered by
 * HomeKeyService, the one part of the launcher that stays alive with the screen off. Screen-off holds a short wake
 * lock so the request can leave before the TV sleeps; a failed call is only logged.
 *
 * Android 14's Low Power Standby cuts an app's network the moment the screen goes off, unless the TV's energy mode
 * exempts it. A request sent then hangs until the TV wakes and lands just before the wake's "on", so the light
 * blinks. Off is skipped when standby blocks the network, and a request that a newer screen event has overtaken is
 * dropped. The broadcast is not held open: a call stuck across standby would be reported as an ANR.
 */
final class GoveeAuto {

    private static final String TAG = "TvHome.GoveeAuto";
    private static final long HOLD_MS = 8000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final AtomicInteger latest = new AtomicInteger();
    private static BroadcastReceiver receiver;

    private GoveeAuto() {
    }

    static void start(Context context) {
        if (receiver != null || !Govee.auto(context)) return;
        Govee govee = Govee.from(context);
        if (govee == null) return;
        Context app = context.getApplicationContext();
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
                int event = latest.incrementAndGet();
                PowerManager pm = app.getSystemService(PowerManager.class);
                if (!on && standbyBlocksNetwork(pm)) {
                    Log.i(TAG, "off skipped: standby blocks the network");
                    return;
                }
                PowerManager.WakeLock lock = pm != null
                        ? pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvhome:govee") : null;
                if (lock != null) lock.acquire(HOLD_MS);
                IO.execute(() -> {
                    try {
                        if (event != latest.get()) return;
                        boolean ok = govee.powerNow(on);
                        Log.i(TAG, (on ? "on" : "off") + (ok ? " sent" : " failed"));
                    } finally {
                        if (lock != null && lock.isHeld()) lock.release();
                    }
                });
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        try {
            app.registerReceiver(receiver, filter);
        } catch (RuntimeException e) {
            receiver = null;
        }
    }

    /** Whether Low Power Standby is on and this app is not exempt, so a request sent now waits for the next wake. */
    private static boolean standbyBlocksNetwork(PowerManager pm) {
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false;
        return pm.isLowPowerStandbyEnabled() && !pm.isExemptFromLowPowerStandby();
    }

    static void stop(Context context) {
        if (receiver == null) return;
        try {
            context.getApplicationContext().unregisterReceiver(receiver);
        } catch (RuntimeException ignored) {
        }
        receiver = null;
    }
}
