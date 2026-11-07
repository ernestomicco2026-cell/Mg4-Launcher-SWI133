package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 0.3.2 (punto 5): ritorno all'app di prima dopo la retromarcia.
 * <p>
 * Segnalazione: con CarPlay (anche da un box Android riconosciuto come CarPlay) la retromarcia apre giustamente le
 * telecamere, ma alla chiusura l'auto torna sul launcher invece che su CarPlay. Nel firmware SWI133 né la home MG né il
 * servizio media riaprono CarPlay: il ritorno dipende solo dalla pila di Android (l'AVM chiude e torna davanti ciò
 * che c'è sotto). Nessuna delle nostre funzioni di avvio interviene qui: StartGuard agisce solo all'accensione/risveglio
 * e solo sulla home MG, HomeLock e HomeMode non aprono nulla. Cosa porta davanti il launcher si registra comunque
 * (retro.ritorno: "riportato da").
 * <p>
 * Quindi: quando davanti arriva la telecamera (retromarcia o 360) si ricorda l'app che c'era; quando la telecamera si
 * chiude e davanti c'è il launcher, la si riapre come fa il pulsante CarPlay/Android Auto. Non si riapre se l'utente
 * ha premuto Home o scelto un'altra app, se la telecamera è rimasta aperta più di 5 minuti, se il telefono/box non è più
 * collegato o se l'opzione è spenta. Tutto dal servizio di accessibilità (eventi di cambio finestra).
 */
public final class RetroReturn {
    private static final long MAX_CAMERA_MS = 5 * 60_000L, CHECK_DELAY_MS = 1200;
    public static final String CAMERA_CLOSE = "com.saicmotor.360camera.close";

    private static String lastPkg, lastCls;
    private static boolean camActive;
    private static long camAt;
    private static String savedPkg, savedCls, savedKind;
    private static volatile long homeAt;
    private static String broughtBy = "", camPkg = "";
    private static long broughtAt;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private RetroReturn() {}

    private static String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
    }

    static boolean isCamera(String pkg) {
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("aroundview") || p.contains(".avm") || p.contains("rvc") || (p.startsWith("com.mg4.") && p.contains("360"));
    }

    /** Pacchetti che passano davanti senza essere "un'app scelta" (barra di sistema, finestre di Android, tastiera). */
    private static boolean transient_(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.equals("android") || pkg.contains("inputmethod")
                || pkg.startsWith("com.android.packageinstaller");
    }

    private static String kind(String pkg) {
        if (pkg.startsWith("com.allgo.carplay")) return "CarPlay";
        if (pkg.startsWith("com.allgo.app.androidauto")) return "Android Auto";
        String p = pkg.toLowerCase(Locale.ROOT);
        if (p.contains("navi") || p.contains("map") || p.contains("waze") || p.contains("telenav")) return "navigatore";
        return "app";
    }

    /** Chi ha appena portato davanti il launcher (intento Home e da chi, apertura del launcher, ripresa normale…). */
    public static synchronized void noteBroughtBy(String why) {
        broughtBy = why;
        broughtAt = SystemClock.elapsedRealtime();
    }

    /** Come noteBroughtBy, ma solo se nessuno ha scritto un motivo più preciso negli ultimi 2 secondi. */
    public static synchronized void noteBroughtByIfNone(String why) {
        if (SystemClock.elapsedRealtime() - broughtAt > 2000) noteBroughtBy(why);
    }

    /** Casetta del dock toccata: l'utente vuole la home, niente ritorno automatico. */
    public static synchronized void homePressed() {
        homeAt = SystemClock.elapsedRealtime();
    }

    /** Broadcast di chiusura della telecamera (SystemUI/AVM): solo per il registro. */
    public static synchronized void cameraClosedBroadcast() {
        if (camActive) Diag.put("retro.telecamera", "chiusura annunciata dall'AVM · " + now());
    }

    /** Nuova finestra in primo piano (TYPE_WINDOW_STATE_CHANGED). */
    public static synchronized void onForeground(Context c, String pkg, String cls) {
        if (pkg == null || transient_(pkg)) return;
        String me = c.getPackageName();
        if (isCamera(pkg)) {
            if (!camActive) {
                camActive = true;
                camAt = SystemClock.elapsedRealtime();
                camPkg = pkg;
                boolean useful = lastPkg != null && !lastPkg.equals(me) && !isCamera(lastPkg) && !StartGuard.isHome(c, lastPkg);
                savedPkg = useful ? lastPkg : null;
                savedCls = useful ? lastCls : null;
                savedKind = useful ? kind(lastPkg) : "";
                final String prev = lastPkg;
                new Thread(() -> {
                    int g = VehicleClient.gearRaw();
                    Diag.put("retro.ritorno", "telecamera aperta (" + pkg + ") · marcia grezza " + g + " · davanti prima "
                            + (prev == null ? "nessuna app" : prev) + (useful ? " → memorizzata (" + kind(prev) + ")" : " → niente da riaprire") + " · " + now());
                }, "mg4-retro").start();
            }
            lastPkg = pkg;
            lastCls = cls;
            return;
        }
        if (camActive) {
            camActive = false;
            long dur = SystemClock.elapsedRealtime() - camAt;
            final String sp = savedPkg, sc = savedCls, sk = savedKind;
            final long openedAt = camAt, closedAt = SystemClock.elapsedRealtime();
            if (pkg.equals(me)) {
                // si decide un attimo dopo: intanto il launcher rilegge il telefono collegato e si sa chi l'ha portato davanti
                MediaClient.refreshSoon();
                H.postDelayed(() -> decide(c, sp, sc, sk, dur, openedAt, closedAt, 0), CHECK_DELAY_MS);
            } else if (sp != null && pkg.equals(sp)) {
                Diag.put("retro.ritorno", "telecamera chiusa dopo " + (dur / 1000) + " s · tornata da sola " + sk + " (" + pkg + ") · " + now());
            } else {
                Diag.put("retro.ritorno", "telecamera chiusa dopo " + (dur / 1000) + " s · davanti " + pkg + " (scelta dell'utente o del sistema): non riapro "
                        + (sp == null ? "niente" : sp) + " · " + now());
            }
        }
        lastPkg = pkg;
        lastCls = cls;
    }

    private static void decide(Context c, String pkg, String cls, String kind, long dur, long openedAt, long closedAt, int round) {
        boolean proj = "CarPlay".equals(kind) || "Android Auto".equals(kind);
        // lo stato del telefono deve essere letto DOPO la chiusura della telecamera (mentre CarPlay era davanti il
        // launcher non leggeva): si aspetta al massimo altri 3 secondi
        if (proj && MediaClient.deviceTypeAt() < closedAt && round < 3) {
            MediaClient.refreshSoon();
            H.postDelayed(() -> decide(c, pkg, cls, kind, dur, openedAt, closedAt, round + 1), 1000);
            return;
        }
        boolean home = homeAt > openedAt;   // Home premuto durante la telecamera o subito dopo
        String by;
        synchronized (RetroReturn.class) {
            by = SystemClock.elapsedRealtime() - broughtAt < 10_000 ? broughtBy : "ripresa della pila di Android (nessun nostro comando)";
        }
        String head = "telecamera " + camPkg + " chiusa dopo " + (dur / 1000) + " s · memorizzata " + (pkg == null ? "nessuna" : kind + " " + pkg)
                + " · riportato da: " + by + " · ";
        String no = null;
        if (pkg == null) no = "prima c'era già il launcher";
        else if (!c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE).getBoolean("retroRitorno", true)) no = "opzione spenta";
        else if (home) no = "l'utente ha premuto Home";
        else if (dur > MAX_CAMERA_MS) no = "telecamera aperta più di 5 minuti";
        else if (!c.getPackageName().equals(StartGuard.topPackage(c))) no = "nel frattempo davanti " + StartGuard.topPackage(c);
        else if (proj && MediaClient.deviceTypeAt() < closedAt) no = "stato del telefono non letto dopo la telecamera";
        else if (proj && MediaClient.deviceTypeNow() == 0) no = kind + " non più collegato (telefono o box staccato)";
        if (no != null) {
            Diag.put("retro.ritorno", head + "NON riaperta: " + no + " · " + now());
            return;
        }
        boolean ok;
        if (proj) {
            ok = MediaClient.openProjection(c, "Android Auto".equals(kind) ? MediaClient.DEV_AAUTO : MediaClient.DEV_CARPLAY);
        } else {
            ok = open(c, pkg, cls);
        }
        Diag.put("retro.ritorno", head + (ok ? "riaperta " + kind : "riapertura NON riuscita") + " · " + now());
    }

    private static boolean open(Context c, String pkg, String cls) {
        // prima l'attività esatta che c'era (se esportata), poi l'apertura normale dell'app
        if (cls != null && cls.startsWith(pkg)) {
            try {
                Intent i = new Intent(Intent.ACTION_MAIN).setComponent(new ComponentName(pkg, cls));
                android.content.pm.ResolveInfo ri = c.getPackageManager().resolveActivity(i, 0);
                if (ri != null && ri.activityInfo != null && ri.activityInfo.exported) {
                    c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        try {
            Intent i = c.getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            Diag.error("retro.ritorno", e.toString());
            return false;
        }
    }
}
