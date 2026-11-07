package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 0.3.2 (punto 10): stato del controllo automatico degli aggiornamenti, dell'aggiornamento forzato ("minima" in
 * latest.json) e degli avvisi da remoto (releases/avvisi.json). Qui solo le regole e la memoria (SharedPreferences);
 * i popup li mostra MainActivity.
 * <p>
 * Aggiornamento forzato: solo se la versione installata è più vecchia di "minima", solo ad auto ferma (velocità 0
 * letta davvero da almeno 2 minuti di fila, ricontrollata subito prima di installare: se non si legge, niente
 * forzatura, solo l'avviso), al massimo 3 tentativi per versione.
 * Le versioni vecchie ignorano "minima" e continuano a usare lo stesso file.
 */
public final class AutoUpdate {
    public static final int MAX_TRIES = 3;
    private static final String SP = "mg4launcher";

    private AutoUpdate() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    private static String now() {
        return new SimpleDateFormat("dd/MM HH:mm", Locale.ITALY).format(new Date());
    }

    // ---- aggiornamento forzato -----------------------------------------------------------------

    /** True se la versione installata è più vecchia della minima richiesta. */
    public static boolean belowMinimum(String current, String minima) {
        return minima != null && !minima.isEmpty() && Updater.compareVersions(current, minima) < 0;
    }

    public static int tries(Context c, String version) {
        return sp(c).getInt("forzato." + version, 0);
    }

    public static void addTry(Context c, String version) {
        sp(c).edit().putInt("forzato." + version, tries(c, version) + 1).apply();
    }

    /**
     * Auto ferma? Sì solo se lo dice un dato letto: velocità sotto 1 km/h (CarService o, in riserva, servizio veicolo
     * SAIC). null = velocità non leggibile: in quel caso non si forza. La marcia non si usa per decidere: il firmware
     * non dice quale numero corrisponde a P (si registra solo nella diagnostica). Da un thread qualsiasi.
     */
    public static Boolean parked() {
        float v = VehicleClient.speedKmh();
        if (Float.isNaN(v)) return null;
        long now = android.os.SystemClock.elapsedRealtime();
        if (v >= 1f) {
            stoppedSince = 0;
            return false;
        }
        if (stoppedSince == 0) stoppedSince = now;
        // ferma da almeno 2 minuti di fila (non un semaforo)
        return now - stoppedSince >= STOP_MS;
    }

    private static final long STOP_MS = 120_000;
    private static volatile long stoppedSince;

    /** Diagnostica aggiornamento.stato. */
    public static void diag(Context c, String found, String minima, String esito) {
        String cur = "";
        try {
            cur = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        String target = minima == null || minima.isEmpty() ? "" : minima;
        Diag.put("aggiornamento.stato", "ultimo controllo " + now() + " · installata " + cur + " · trovata " + (found == null || found.isEmpty() ? "—" : found)
                + " · minima " + (target.isEmpty() ? "—" : target) + " · " + esito
                + (found == null || found.isEmpty() ? "" : " · tentativi forzati " + tries(c, found) + "/" + MAX_TRIES));
    }

    // ---- avvisi da remoto --------------------------------------------------------------------------

    /** Un avviso da mostrare adesso. */
    public static final class Notice {
        public String id = "", text = "";
        public boolean important, updateButton;
    }

    private static Set<String> set(Context c, String key) {
        return new HashSet<>(sp(c).getStringSet(key, new HashSet<>()));
    }

    private static void add(Context c, String key, String id) {
        Set<String> s = set(c, key);
        s.add(id);
        sp(c).edit().putStringSet(key, s).apply();
    }

    /** L'utente ha letto l'avviso (OK, o Aggiorna). */
    public static void confirmed(Context c, String id) {
        add(c, "avvisiConfermati", id);
        diagNotices(c, null);
    }

    /** Avvisi mostrati in questa accensione (un avviso importante si rimostra solo alla prossima). */
    private static final Set<String> shownThisRun = new HashSet<>();
    private static String lastRead = "";

    /**
     * Avvisi da mostrare ora, nella lingua del launcher (riserva: inglese, poi italiano). Ognuno una volta sola;
     * quelli importanti a ogni accensione finché l'utente non preme OK o non aggiorna il launcher.
     */
    public static List<Notice> pending(Context c, JSONArray arr, String lang, String version) {
        List<Notice> out = new ArrayList<>();
        StringBuilder ids = new StringBuilder();
        if (arr == null) {
            lastRead = "nessun file o nessun avviso";
            diagNotices(c, null);
            return out;
        }
        Set<String> seen = set(c, "avvisiVisti"), ok = set(c, "avvisiConfermati");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "").trim();
            if (id.isEmpty()) continue;
            ids.append(ids.length() == 0 ? "" : ", ").append(id);
            JSONArray vers = o.optJSONArray("versioni");
            boolean forUs = vers == null || vers.length() == 0;
            for (int k = 0; vers != null && k < vers.length(); k++) if (version.equals(vers.optString(k))) forUs = true;
            if (!forUs) continue;
            boolean important = o.optBoolean("importante", false);
            // aggiornato dopo averlo visto: un avviso importante non va più ripetuto
            String verKey = "avviso.ver." + id;
            String firstVer = sp(c).getString(verKey, "");
            if (important && !firstVer.isEmpty() && !firstVer.equals(version)) continue;
            if (ok.contains(id) || shownThisRun.contains(id)) continue;
            if (!important && seen.contains(id)) continue;
            JSONObject tx = o.optJSONObject("testo");
            String text = "";
            if (tx != null) {
                text = tx.optString(lang, "");
                if (text.isEmpty()) text = tx.optString("en", "");
                if (text.isEmpty()) text = tx.optString("it", "");
            } else {
                text = o.optString("testo", "");
            }
            if (text.trim().isEmpty()) continue;
            Notice n = new Notice();
            n.id = id;
            n.text = text.trim();
            n.important = important;
            n.updateButton = "aggiorna".equalsIgnoreCase(o.optString("pulsante", ""));
            out.add(n);
        }
        lastRead = ids.length() == 0 ? "nessun avviso" : ids.toString();
        diagNotices(c, null);
        return out;
    }

    /** Segna l'avviso come mostrato (in questa accensione e per sempre, per quelli non importanti). */
    public static void shown(Context c, Notice n, String version) {
        shownThisRun.add(n.id);
        add(c, "avvisiVisti", n.id);
        if (sp(c).getString("avviso.ver." + n.id, "").isEmpty()) sp(c).edit().putString("avviso.ver." + n.id, version).apply();
        diagNotices(c, n.id);
    }

    private static void diagNotices(Context c, String justShown) {
        Diag.put("avvisi.stato", "letti " + lastRead + " · mostrati " + join(set(c, "avvisiVisti")) + " · confermati " + join(set(c, "avvisiConfermati"))
                + (justShown == null ? "" : " · ultimo mostrato " + justShown + " alle " + now()));
    }

    private static String join(Set<String> s) {
        return s.isEmpty() ? "—" : android.text.TextUtils.join(", ", s);
    }
}
