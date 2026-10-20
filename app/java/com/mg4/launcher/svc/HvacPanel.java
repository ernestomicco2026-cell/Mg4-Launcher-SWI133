package com.mg4.launcher.svc;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Pannello clima della SWI133. Qui non esiste più l'app Clima com.saicmotor.hmi.hvac: il clima è il pannello di
 * com.android.car.hvac (CarHvacApp), che si apre chiedendolo al SystemUI MG, esattamente come la home originale
 * (launcher_my24_P, AcCardView → IStartActivityService.startHvac()).
 */
public final class HvacPanel {
    /** Pseudo-pacchetto usato dalla home e dal catalogo app al posto di un'attività vera. */
    public static final String PKG = "@hvac";

    private static final String SYSUI = "com.android.systemui";
    private static final String CLS = "com.android.systemui.StartActivityService";
    private static final String ACTION = "com.android.systemui.saicmotor.action.StartActivity";
    private static final String DESC = "com.android.systemui.IStartActivityService";
    private static final int START_HVAC = 1;

    private static ServiceLink link;
    private static Boolean available;
    private static boolean pending;

    private HvacPanel() {}

    private static Intent intent() {
        return ServiceLink.byComponent(SYSUI, CLS, ACTION);
    }

    /** Il servizio del SystemUI che apre il pannello clima esiste su questo firmware? */
    public static synchronized boolean available(Context c) {
        if (available == null) {
            boolean ok = false;
            try {
                ok = c.getPackageManager().resolveService(intent(), 0) != null;
            } catch (Throwable ignored) {
            }
            available = ok;
            Diag.put("clima.pannello", ok ? "servizio SystemUI presente" : "servizio SystemUI assente: pulsante disattivato");
        }
        return available;
    }

    /** Collegamento anticipato (all'avvio della home), così il primo tocco apre subito il pannello. */
    public static synchronized void prepare(Context c) {
        if (link != null || !available(c)) return;
        link = new ServiceLink(c, "clima.pannello", intent());
        link.setOnConnected(() -> {
            synchronized (HvacPanel.class) {
                if (!pending) return;
                pending = false;
            }
            call();
        });
        link.start();
    }

    /** Apre il pannello clima. False se non è disponibile. */
    public static boolean open(Context c) {
        if (!available(c)) return false;
        prepare(c);
        if (link.isConnected()) {
            call();
        } else {
            synchronized (HvacPanel.class) {
                pending = true;
            }
            // se il collegamento non arriva entro pochi secondi la richiesta si lascia cadere
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                synchronized (HvacPanel.class) {
                    if (pending) Diag.error("clima.pannello", "SystemUI non collegato: pannello non aperto");
                    pending = false;
                }
            }, 4000);
        }
        return true;
    }

    private static void call() {
        IBinder b = link == null ? null : link.binder();
        try {
            link.lastCall = "startHvac";
            Rpc.call(b, DESC, START_HVAC, Rpc.NONE);
            Diag.put("clima.pannello.apertura", "ok");
        } catch (Exception e) {
            Diag.error("clima.pannello", e.toString());
        }
    }
}
