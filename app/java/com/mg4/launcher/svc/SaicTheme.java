package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.IBinder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 0.3.6 (punto 14, specifico SWI133): tema automatico SAIC.
 * <p>
 * Chi rimette "giorno" dopo che il launcher ha scritto "notte" è il servizio Impostazioni di sistema SAIC
 * (systemsettingservice_my24_P, com.saicmotor.service.systemsettings, IGeneralBinder). Con il suo "giorno/notte
 * automatico" acceso (daynight_automode) ogni volta che arriva il segnale delle luci (SIG_SAIC_ID_VEH_SIDE_LGHT,
 * 100990997) con il quadro acceso (accensione 2), e all'avvio (initLamp), chiama UiModeManager.setNightMode: notte solo
 * se le luci valgono 3 (posizione), altrimenti giorno. Così la "notte" decisa dall'orario veniva rimessa a "giorno".
 * <p>
 * Con la scelta MANUALE dell'utente (Giorno o Notte) il tema SAIC va in manuale con setIsNightMode (IGeneralService,
 * codice 17: spegne anche il suo automatico e salva il valore, che SAIC riusa all'avvio), ricordando com'era prima
 * (getDayNightAutoMode 20, getIsNightMode 19). Con "Automatico" o alla disinstallazione si rimette com'era
 * (setIsNightMode, poi setDayNightAutoMode 18). In automatico il launcher non combatte con SAIC: se il suo automatico è
 * acceso il tema di sistema lo decide SAIC con i fari. Log tema.saic.
 */
public final class SaicTheme {
    private static final String PKG = "com.saicmotor.service.systemsettings";
    private static final String CLS = "com.saicmotor.service.systemsettings.SettingsService";
    private static final String ACTION = "com.saicmotor.service.systemsettings.IGeneralService";
    private static final String DESC = "com.saicmotor.sdk.systemsettings.IGeneralService";
    private static final int SET_IS_NIGHT = 17, SET_AUTO = 18, GET_IS_NIGHT = 19, GET_AUTO = 20;
    private static final String K_PREV_AUTO = "saicTemaAutoPrima", K_PREV_NIGHT = "saicTemaNottePrima";

    /** Ultima lettura dell'automatico SAIC: null = non letto. */
    private static volatile Boolean saicAuto;

    private SaicTheme() {}

    /** Ultimo stato letto del "giorno/notte automatico" SAIC (null se mai letto). */
    public static Boolean saicAuto() {
        return saicAuto;
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
    }

    /** Collega il servizio SAIC, esegue il lavoro e scollega. Da un thread di lavoro. */
    private interface Work {
        String run(IBinder b) throws Exception;
    }

    private static String withService(Context c, Work w) {
        Context app = c.getApplicationContext();
        final IBinder[] got = {null};
        CountDownLatch latch = new CountDownLatch(1);
        ServiceConnection conn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName n, IBinder b) {
                got[0] = b;
                latch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName n) {
            }
        };
        boolean bound = false;
        try {
            bound = app.bindService(new Intent(ACTION).setComponent(new ComponentName(PKG, CLS)), conn, Context.BIND_AUTO_CREATE);
            if (!bound) return "servizio SAIC assente";
            if (!latch.await(4, TimeUnit.SECONDS) || got[0] == null) return "servizio SAIC non risponde";
            return w.run(got[0]);
        } catch (Throwable e) {
            return "errore " + e.getClass().getSimpleName();
        } finally {
            if (bound) try {
                app.unbindService(conn);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Legge l'automatico SAIC (per il log e per non combattere). Da un thread di lavoro. */
    public static String read(Context c) {
        return withService(c, b -> {
            boolean auto = Rpc.callBool(b, DESC, GET_AUTO);
            boolean night = Rpc.callBool(b, DESC, GET_IS_NIGHT);
            saicAuto = auto;
            return "SAIC automatico " + (auto ? "acceso" : "spento") + " · notte salvata " + (night ? "sì" : "no");
        });
    }

    /** Scelta del tema nelle impostazioni (auto / giorno / notte). Lavora in un thread suo. */
    public static void onChoice(Context c, String mode) {
        new Thread(() -> {
            String r = "auto".equals(mode) ? restoreNow(c, "scelta Automatico") : manualNow(c, "notte".equals(mode));
            Diag.put("tema.saic", r);
            // con "Automatico" il tema di sistema si ridecide DOPO aver rimesso SAIC com'era (prima si leggeva lo stato vecchio)
            if ("auto".equals(mode)) DayNight.get(c).syncSystem("tema SAIC rimesso");
        }, "mg4-tema-saic").start();
    }

    private static String manualNow(Context c, boolean night) {
        return withService(c, b -> {
            boolean auto = Rpc.callBool(b, DESC, GET_AUTO);
            boolean wasNight = Rpc.callBool(b, DESC, GET_IS_NIGHT);
            SharedPreferences sp = sp(c);
            String saved = "";
            if (!sp.contains(K_PREV_AUTO)) {
                sp.edit().putBoolean(K_PREV_AUTO, auto).putBoolean(K_PREV_NIGHT, wasNight).apply();
                saved = " · ricordato prima: automatico " + (auto ? "acceso" : "spento") + ", notte " + (wasNight ? "sì" : "no");
            }
            Rpc.call(b, DESC, SET_IS_NIGHT, p -> p.writeInt(night ? 1 : 0));
            boolean autoAfter = Rpc.callBool(b, DESC, GET_AUTO);
            saicAuto = autoAfter;
            return "scelta manuale " + (night ? "notte" : "giorno") + " · SAIC messo in manuale (setIsNightMode " + night + ")"
                    + " · automatico SAIC ora " + (autoAfter ? "ANCORA acceso" : "spento") + saved;
        });
    }

    private static String restoreNow(Context c, String why) {
        SharedPreferences sp = sp(c);
        if (!sp.contains(K_PREV_AUTO)) {
            String st = read(c);
            return why + " · niente da rimettere (SAIC non toccato) · " + st;
        }
        boolean auto = sp.getBoolean(K_PREV_AUTO, true), night = sp.getBoolean(K_PREV_NIGHT, false);
        String r = withService(c, b -> {
            Rpc.call(b, DESC, SET_IS_NIGHT, p -> p.writeInt(night ? 1 : 0));
            if (auto) Rpc.call(b, DESC, SET_AUTO, p -> p.writeInt(1));
            boolean now = Rpc.callBool(b, DESC, GET_AUTO);
            saicAuto = now;
            return "ok";
        });
        if ("ok".equals(r)) sp.edit().remove(K_PREV_AUTO).remove(K_PREV_NIGHT).apply();
        return why + " · SAIC rimesso com'era: automatico " + (auto ? "acceso" : "spento") + ", notte " + (night ? "sì" : "no")
                + " · esito " + r;
    }

    /** Disinstallazione: si rimette l'impostazione SAIC di prima (dal thread della disinstallazione). */
    public static void restore(Context c) {
        Diag.put("tema.saic", restoreNow(c, "disinstallazione"));
    }
}
