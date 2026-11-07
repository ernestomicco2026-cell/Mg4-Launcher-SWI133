package com.mg4.launcher.svc;

import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 0.3.2 (punto 1): tema giorno/notte automatico con la stessa catena di fonti su tutte le versioni del launcher.
 * <ol>
 *   <li>uiMode / UiModeManager del sistema, ma solo se cambia davvero (visto almeno una volta sia giorno sia notte, o
 *   notte adesso): sulla SWI133 funziona già, sulle altre versioni a volte resta fisso;</li>
 *   <li>stato dei fari: anabbaglianti o luci di posizione accesi = notte;</li>
 *   <li>alba e tramonto calcolati offline dalla posizione (ultima nota del GPS); riserva: fuso orario, poi Italia.</li>
 * </ol>
 * Prima di cambiare tema si aspetta circa 60 s con lo stesso risultato (niente cambi a ogni galleria o lampione);
 * all'avvio si applica subito. La scelta manuale (Giorno / Notte) ha sempre la precedenza: la gestisce Theme.
 */
public final class DayNight {
    private static final long CONFIRM_MS = 60_000;
    private static final String SP = "mg4launcher", K_UI_DAY = "temaUiGiorno", K_UI_NIGHT = "temaUiNotte";

    private static Boolean applied;            // tema automatico applicato (true = giorno)
    private static Boolean pending;            // risultato diverso in attesa di conferma
    private static long pendingSince;
    private static String source = "—", lastChange = "—";
    private static String lastCandidates = "";

    private DayNight() {}

    /** Tema automatico da applicare ora (la prima volta si decide subito). */
    public static synchronized boolean appliedDay(Context c) {
        if (applied == null) {
            Result r = evaluate(c);
            applied = r.day;
            source = r.source;
            lastChange = stamp();
        }
        return applied;
    }

    /**
     * Ricontrolla le fonti (ogni pochi secondi e a ogni cambio di configurazione). True se il tema automatico è appena
     * cambiato, dopo i 60 s di conferma: allora l'interfaccia va ricostruita.
     */
    public static synchronized boolean tick(Context c) {
        Result r = evaluate(c);
        if (applied == null) {
            applied = r.day;
            source = r.source;
            lastChange = stamp();
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (r.day == applied) {
            pending = null;
            source = r.source;
            return false;
        }
        if (pending == null || pending != r.day) {
            pending = r.day;
            pendingSince = now;
            Diag.put("tema.attesa", "fonte " + r.source + " dice " + (r.day ? "giorno" : "notte") + ": cambio tra 60 s se resta così");
            return false;
        }
        if (now - pendingSince < CONFIRM_MS) return false;
        applied = r.day;
        pending = null;
        source = r.source;
        lastChange = stamp();
        Diag.put("tema.attesa", "cambiato in " + (r.day ? "giorno" : "notte") + " (" + r.source + ")");
        return true;
    }

    /** Fonte usata e ultimo cambio, per la diagnostica tema.stato. */
    public static synchronized String describe() {
        return "fonte " + source + " · ultimo cambio automatico " + lastChange;
    }

    private static String stamp() {
        return new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ITALY).format(new Date());
    }

    private static final class Result {
        boolean day;
        String source;
    }

    private static Result evaluate(Context c) {
        Result r = new Result();
        android.content.SharedPreferences sp = c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
        // 1) uiMode del sistema
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        String ui = night == Configuration.UI_MODE_NIGHT_YES ? "notte" : night == Configuration.UI_MODE_NIGHT_NO ? "giorno" : "non definito";
        if (night == Configuration.UI_MODE_NIGHT_YES && !sp.getBoolean(K_UI_NIGHT, false)) sp.edit().putBoolean(K_UI_NIGHT, true).apply();
        if (night == Configuration.UI_MODE_NIGHT_NO && !sp.getBoolean(K_UI_DAY, false)) sp.edit().putBoolean(K_UI_DAY, true).apply();
        boolean uiWorks = sp.getBoolean(K_UI_NIGHT, false);   // ha già messo almeno una volta la notte: segue davvero il buio
        String mgr = "?";
        try {
            android.app.UiModeManager um = (android.app.UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
            int m = um.getNightMode();
            mgr = m == android.app.UiModeManager.MODE_NIGHT_YES ? "notte" : m == android.app.UiModeManager.MODE_NIGHT_NO ? "giorno"
                    : m == android.app.UiModeManager.MODE_NIGHT_AUTO ? "auto" : String.valueOf(m);
        } catch (Throwable ignored) {
        }
        // 2) fari
        Boolean lights = VehicleClient.lightsNow();
        // 3) sole
        Sun sun = sun(c);
        if (uiWorks && night != Configuration.UI_MODE_NIGHT_UNDEFINED) {
            r.day = night == Configuration.UI_MODE_NIGHT_NO;
            r.source = "uiMode di sistema";
        } else if (lights != null) {
            r.day = !lights;
            r.source = "fari dell'auto";
        } else {
            r.day = sun.day;
            r.source = "alba/tramonto (" + sun.where + ")";
        }
        // tutti i segnali giorno/notte leggibili, per scegliere la fonte giusta dai log (letti dal thread del veicolo)
        String cand = "uiMode " + ui + (uiWorks ? "" : " (mai visto notte: non usato)") + " · UiModeManager " + mgr
                + " · fari " + (lights == null ? "non leggibili" : lights ? "accesi" : "spenti") + " (" + VehicleClient.lightsRaw() + ")"
                + " · sole " + (sun.day ? "giorno" : "notte") + " (alba " + sun.rise + ", tramonto " + sun.set + ", " + sun.where + ")";
        if (!cand.equals(lastCandidates)) {
            lastCandidates = cand;
            Diag.put("tema.candidati", cand);
        }
        return r;
    }

    // ---- alba e tramonto (algoritmo NOAA semplificato, precisione di qualche minuto) ----------------------

    private static final class Sun {
        boolean day = true;
        String rise = "?", set = "?", where = "";
    }

    private static Sun sun(Context c) {
        Sun s = new Sun();
        double lat, lon;
        android.location.Location loc = lastLocation(c);
        TimeZone tz = TimeZone.getDefault();
        if (loc != null) {
            lat = loc.getLatitude();
            lon = loc.getLongitude();
            s.where = String.format(Locale.ROOT, "posizione %.1f, %.1f", lat, lon);
        } else if (tz != null && !"UTC".equals(tz.getID()) && !"GMT".equals(tz.getID())) {
            lon = tz.getRawOffset() / 3_600_000.0 * 15.0;
            lat = tz.getID().startsWith("Europe/") ? 45.0 : 40.0;
            s.where = "fuso " + tz.getID();
        } else {
            lat = 42.5;
            lon = 12.5;   // Italia
            s.where = "Italia";
        }
        Calendar cal = Calendar.getInstance();
        int doy = cal.get(Calendar.DAY_OF_YEAR);
        double offH = (tz == null ? 0 : tz.getOffset(cal.getTimeInMillis())) / 3_600_000.0;
        double[] rs = riseSet(doy, lat, lon, offH);
        double nowH = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0;
        if (rs == null) {
            // giorno o notte polari
            int m = cal.get(Calendar.MONTH);
            boolean summer = lat >= 0 ? m >= 3 && m <= 8 : m <= 2 || m >= 9;
            s.day = summer;
            return s;
        }
        s.rise = hm(rs[0]);
        s.set = hm(rs[1]);
        s.day = nowH >= rs[0] && nowH < rs[1];
        return s;
    }

    private static String hm(double h) {
        h = (h % 24 + 24) % 24;
        int hh = (int) h, mm = (int) Math.round((h - hh) * 60);
        if (mm == 60) {
            hh = (hh + 1) % 24;
            mm = 0;
        }
        return String.format(Locale.ROOT, "%02d:%02d", hh, mm);
    }

    /** Ore locali di alba e tramonto (sole a -0,833°), null se il sole non sorge o non tramonta. */
    static double[] riseSet(int doy, double lat, double lon, double tzHours) {
        double g = 2 * Math.PI / 365.0 * (doy - 1);
        double eqt = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g) - 0.006758 * Math.cos(2 * g)
                + 0.000907 * Math.sin(2 * g) - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosH = (Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl))) - Math.tan(latR) * Math.tan(decl);
        if (cosH > 1 || cosH < -1) return null;
        double ha = Math.toDegrees(Math.acos(cosH));
        double noon = 720 - 4 * lon - eqt;   // minuti UTC
        double rise = (noon - 4 * ha) / 60.0 + tzHours, set = (noon + 4 * ha) / 60.0 + tzHours;
        return new double[]{rise, set};
    }

    private static volatile long locAt;
    private static volatile android.location.Location loc;

    private static volatile boolean locBusy;

    /**
     * Ultima posizione nota (GPS dell'auto), riletta al massimo ogni 10 minuti in background (niente chiamate ai servizi
     * sul thread dell'interfaccia); null se non disponibile.
     */
    private static android.location.Location lastLocation(Context c) {
        long now = SystemClock.elapsedRealtime();
        if ((locAt == 0 || now - locAt >= 600_000) && !locBusy) {
            locAt = now;
            locBusy = true;
            Context app = c.getApplicationContext();
            new Thread(() -> {
                try {
                    loc = readLocation(app);
                } finally {
                    locBusy = false;
                }
            }, "mg4-posizione").start();
        }
        return loc;
    }

    @SuppressWarnings("MissingPermission")
    private static android.location.Location readLocation(Context c) {
        try {
            if (c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // firma platform: il permesso si concede da solo (come CAR_SPEED)
                android.content.pm.PackageManager pm = c.getPackageManager();
                pm.getClass().getMethod("grantRuntimePermission", String.class, String.class, android.os.UserHandle.class)
                        .invoke(pm, c.getPackageName(), android.Manifest.permission.ACCESS_FINE_LOCATION, android.os.Process.myUserHandle());
            }
            android.location.LocationManager lm = (android.location.LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            android.location.Location best = null;
            for (String prov : new String[]{"gps", "network", "passive"}) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(prov);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (Throwable ignored) {
                }
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }
}
