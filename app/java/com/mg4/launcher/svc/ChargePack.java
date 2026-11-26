package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 1.0.4 (punto F): pacchetti dell'animazione di ricarica. Le clip NON sono nell'APK: si scarica solo lo zip del colore
 * scelto nelle impostazioni, da releases/animazioni.json di questo repository (pacchetti nella cartella animazioni/ del repository).
 * <ul>
 *   <li>in background, con ripresa se il download si interrompe (Range), controllo sha256 e dimensione;</li>
 *   <li>se si cambia colore si scarica il nuovo e si cancella il vecchio;</li>
 *   <li>se animazioni.json o il pacchetto non ci sono: niente animazione, nessun errore a schermo.</li>
 * </ul>
 * Le 8 clip si estraggono in files/animazione/&lt;colore&gt;/ (carica-&lt;colore&gt;-&lt;giorno|notte&gt;-&lt;fase&gt;.mp4).
 * <p>
 * 0.3.4: accanto alle clip c'è il file "sha256" con l'impronta dello zip scaricato. Al massimo ogni 12 ore si confronta
 * con animazioni.json: se il pacchetto su GitHub è cambiato si riscarica; il vecchio si cancella solo quando il nuovo è
 * scaricato e controllato (se il download non riesce l'animazione continua con il vecchio).
 */
public final class ChargePack {
    public static final String[] PHASES = {"aggancio", "loop", "finita", "sgancio"};
    private static final String JSON_URL = Updater.OWN_RAW + "releases/animazioni.json";
    private static final long CHECK_EVERY_MS = 12 * 60 * 60_000L;
    /** File con lo sha256 dello zip da cui vengono le clip (nella cartella del colore). */
    private static final String SHA_FILE = "sha256";

    private static volatile String state = "non controllato";
    private static volatile boolean busy;
    private static volatile boolean failed;
    /** 0.3.4: true mentre l'animazione mostra i fotogrammi: il pacchetto in uso non si sostituisce. */
    public static volatile boolean inUse;
    /** 0.3.4: l'ultimo download ha scartato lo zip (sha256 o dimensione diversi): si riprova al prossimo controllo. */
    private static volatile boolean retrySoon;

    private ChargePack() {}

    /** "Arctic White" → "arctic-white" (nomi dei file e degli zip). */
    public static String slug(String color) {
        return color.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    public static File root(Context c) {
        return new File(c.getFilesDir(), "animazione");
    }

    public static File dir(Context c, String color) {
        return new File(root(c), slug(color));
    }

    /** Clip di una fase, null se il pacchetto del colore non è completo. */
    public static File clip(Context c, String color, boolean day, String phase) {
        File f = new File(dir(c, color), "carica-" + slug(color) + "-" + (day ? "giorno" : "notte") + "-" + phase + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** True se ci sono tutte le 8 clip del colore. */
    public static boolean complete(Context c, String color) {
        for (String ph : PHASES) {
            if (clip(c, color, true, ph) == null || clip(c, color, false, ph) == null) return false;
        }
        return true;
    }

    public static String state() {
        return state;
    }

    public static boolean failed() {
        return failed;
    }

    public static boolean busy() {
        return busy;
    }

    /**
     * Controlla il pacchetto del colore scelto e, se serve, lo scarica (thread a parte). force = true salta l'attesa
     * tra un controllo e l'altro (cambio di colore).
     */
    public static synchronized void ensure(Context ctx, String color, boolean force) {
        Context c = ctx.getApplicationContext();
        SharedPreferences sp = c.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        if (busy) return;
        busy = true;
        // tutto (cartelle, controllo, download) fuori dal thread principale
        new Thread(() -> {
            try {
                removeOthers(c, color);
                String key = "animControllo." + slug(color);
                long last = sp.getLong(key, 0), now = System.currentTimeMillis();
                // 0.3.4: controllo su GitHub forzato, mai fatto o vecchio di più di 12 ore
                boolean due = force || last <= 0 || now - last >= CHECK_EVERY_MS;
                if (complete(c, color)) {
                    if (due && inUse) {
                        // 0.3.4: l'animazione sta usando clip e fotogrammi: niente controllo né sostituzione adesso,
                        // si riprova al prossimo avvio (animControllo non si aggiorna)
                        state = "pacchetto " + color + " presente (controllo aggiornamento rimandato: animazione in corso)";
                        failed = false;
                        generation++;
                        return;
                    }
                    if (!due || !changed(c, color)) {
                        if (due) sp.edit().putLong(key, now).apply();
                        state = "pacchetto " + color + " presente";
                        failed = false;
                        buildFrames(c, color);
                        generation++;
                        return;
                    }
                    // pacchetto cambiato su GitHub: si riscarica (il vecchio resta finché il nuovo non è pronto)
                } else if (!due) {
                    return;
                }
                sp.edit().putLong(key, now).apply();
                retrySoon = false;
                download(c, color);
                // 0.3.4: zip scartato (per esempio un .part rimasto da un download vecchio): si riprova al prossimo
                // controllo invece di aspettare 12 ore
                if (retrySoon) sp.edit().remove(key).apply();
                if (complete(c, color)) buildFrames(c, color);
                generation++;
            } finally {
                busy = false;
            }
        }, "mg4-animazione").start();
    }

    /** Fotogrammi di tutte le clip del colore (una volta sola: poi ChargeFrames li trova già pronti). */
    private static void buildFrames(Context c, String color) {
        java.util.List<File> all = new java.util.ArrayList<>();
        for (String ph : PHASES) {
            all.add(clip(c, color, true, ph));
            all.add(clip(c, color, false, ph));
        }
        ChargeFrames.build(all.toArray(new File[0]));
    }

    /** Fotogrammi delle clip di una cartella di prova (chiavetta). */
    public static String buildFramesIn(File dir, String slug) {
        java.util.List<File> all = new java.util.ArrayList<>();
        for (String ph : PHASES) {
            all.add(clipIn(dir, slug, true, ph));
            all.add(clipIn(dir, slug, false, ph));
        }
        return ChargeFrames.build(all.toArray(new File[0]));
    }

    /** Cresce quando il pacchetto cambia (scaricato, cancellato): chi tiene in memoria complete() lo ricalcola. */
    public static volatile int generation;

    /** Si tiene solo il colore scelto: le cartelle e gli zip parziali degli altri colori si cancellano. */
    private static void removeOthers(Context c, String color) {
        File[] list = root(c).listFiles();
        if (list == null) return;
        String keep = slug(color);
        for (File f : list) {
            String n = f.getName();
            if (n.equals(keep) || n.equals(keep + ".zip.part") || n.equals("prova")) continue;
            deleteTree(f);
        }
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /**
     * 0.3.4: voce del colore in animazioni.json (url, size, sha256), null se il colore non c'è. Eccezione se il file non
     * si scarica o non si legge. Fa una richiesta di rete: solo dal thread di lavoro.
     */
    private static JSONObject entry(String color) throws Exception {
        JSONObject j = new JSONObject(Updater.getText(JSON_URL));
        JSONObject colors = j.optJSONObject("colori");
        return colors == null ? null : colors.optJSONObject(color);
    }

    /**
     * 0.3.4: true se il pacchetto del colore su GitHub è diverso da quello scaricato (sha256 di animazioni.json diverso
     * da quello salvato accanto alle clip). animazioni.json non leggibile o voce senza sha256: false. Pacchetto scaricato
     * da una versione che non salvava lo sha256: si salva quello attuale e false (non si riscarica). Rete: solo dal thread
     * di lavoro.
     */
    private static boolean changed(Context c, String color) {
        String want;
        try {
            JSONObject e = entry(color);
            want = e == null ? "" : e.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return false;
        }
        if (want.isEmpty()) return false;
        File f = new File(dir(c, color), SHA_FILE);
        if (!f.isFile()) {
            writeSha(f, want);
            return false;
        }
        String have;
        try {
            have = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            have = "";
        }
        if (want.equals(have)) return false;
        Diag.put("ricarica.pacchetto", "pacchetto " + color + " cambiato su GitHub: si riscarica");
        return true;
    }

    private static void writeSha(File f, String sha) {
        try {
            java.nio.file.Files.write(f.toPath(), sha.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            Diag.put("ricarica.pacchetto", "sha256 non salvato: " + e.getClass().getSimpleName());
        }
    }

    private static void download(Context c, String color) {
        JSONObject entry;
        try {
            entry = entry(color);
        } catch (Exception e) {
            state = "pacchetto " + color + " assente (animazioni.json non disponibile)";
            Diag.put("ricarica.pacchetto", state);
            return;
        }
        if (entry == null) {
            state = "pacchetto " + color + " assente (non c'è in animazioni.json)";
            Diag.put("ricarica.pacchetto", state);
            return;
        }
        String url = entry.optString("url", ""), sha = entry.optString("sha256", "").toLowerCase(Locale.ROOT);
        long size = entry.optLong("size", -1);
        File root = root(c);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        File part = new File(root, slug(color) + ".zip.part");
        state = "pacchetto " + color + " in download";
        Diag.put("ricarica.pacchetto", state);
        try {
            fetch(url, part, size);
            String got = sha256(part);
            if (!got.equals(sha) || size > 0 && part.length() != size) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();   // la prossima volta si riscarica da zero
                retrySoon = true;
                throw new java.io.IOException("sha256 o dimensione diversi");
            }
            File tmp = new File(root, slug(color) + ".tmp");
            deleteTree(tmp);
            extract(part, tmp, "carica-" + slug(color) + "-");
            File dst = dir(c, color);
            deleteTree(dst);
            if (!tmp.renameTo(dst)) throw new java.io.IOException("cartella non rinominata");
            writeSha(new File(dst, SHA_FILE), got);   // 0.3.4: impronta del pacchetto, per sapere se su GitHub cambia
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            failed = !complete(c, color);
            state = failed ? "pacchetto " + color + " incompleto" : "pacchetto " + color + " presente (scaricato ora)";
        } catch (Exception e) {
            // 0.3.4: se c'era già un pacchetto completo (aggiornamento non riuscito) l'animazione continua con quello
            boolean old = complete(c, color);
            failed = !old;
            state = "pacchetto " + color + " · errore di download: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()) + " (riprende la prossima volta"
                    + (old ? "; resta il pacchetto precedente)" : ")");
        }
        Diag.put("ricarica.pacchetto", state);
    }

    /** Download con ripresa: se c'è già una parte del file si chiede solo il resto (Range). */
    private static void fetch(String url, File part, long size) throws Exception {
        long have = part.isFile() ? part.length() : 0;
        if (size > 0 && have > size) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            have = 0;
        }
        if (size > 0 && have == size) return;
        String u = url;
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(u).openConnection();
            con.setInstanceFollowRedirects(false);
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setRequestProperty("User-Agent", "MG4Launcher");
            if (have > 0) con.setRequestProperty("Range", "bytes=" + have + "-");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) throw new java.io.IOException("redirect senza indirizzo");
                u = new URL(new URL(u), loc).toString();
                continue;
            }
            if (code == 416) {   // il file parziale è già completo (o sbagliato): lo decide lo sha256, altrimenti si riparte da zero
                con.disconnect();
                return;
            }
            boolean append = code == 206;
            if (code != 200 && code != 206) {
                con.disconnect();
                throw new java.io.IOException("HTTP " + code);
            }
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                con.disconnect();
            }
            return;
        }
        throw new java.io.IOException("troppi redirect");
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    /** Estrae solo le clip .mp4 con il prefisso atteso, senza cartelle (niente percorsi strani dentro lo zip). */
    static int extract(File zip, File to, String prefix) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        to.mkdirs();
        int n = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[64 * 1024];
            while ((e = zin.getNextEntry()) != null) {
                String name = new File(e.getName()).getName();
                if (e.isDirectory() || !name.startsWith(prefix) || !name.endsWith(".mp4")) continue;
                try (OutputStream out = new FileOutputStream(new File(to, name))) {
                    int r;
                    while ((r = zin.read(buf)) > 0) out.write(buf, 0, r);
                }
                n++;
            }
        }
        return n;
    }

    // ---- prova da chiavetta USB ---------------------------------------------------------------

    /**
     * Prova dalla modalità tecnica: cerca sulla chiavetta USB uno zip animazione-&lt;colore&gt;.zip (nella cartella
     * principale), lo estrae in files/animazione/prova/ e restituisce {cartella, colore}. Null se non c'è.
     */
    public static Object[] loadFromUsb(Context c) {
        java.util.List<File> roots = new java.util.ArrayList<>();
        File[] st = new File("/storage").listFiles();
        if (st != null) for (File f : st) roots.add(f);
        File[] mr = new File("/mnt/media_rw").listFiles();
        if (mr != null) for (File f : mr) roots.add(f);
        for (File r : roots) {
            File[] zips = r.listFiles((d, n) -> n.startsWith("animazione-") && n.endsWith(".zip"));
            if (zips == null || zips.length == 0) continue;
            File zip = zips[0];
            String sl = zip.getName().substring("animazione-".length(), zip.getName().length() - 4);
            File to = new File(root(c), "prova");
            deleteTree(to);
            try {
                int n = extract(zip, to, "carica-" + sl + "-");
                Diag.put("ricarica.prova", "zip " + zip.getName() + " dalla chiavetta (" + r.getName() + ") · " + n + " clip");
                if (n > 0) return new Object[]{to, sl};
            } catch (Exception e) {
                Diag.put("ricarica.prova", "zip " + zip.getName() + " non letto: " + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    /** Clip di una fase nella cartella di prova (slug del colore già pronto). */
    public static File clipIn(File dir, String slug, boolean day, String phase) {
        File f = new File(dir, "carica-" + slug + "-" + (day ? "giorno" : "notte") + "-" + phase + ".mp4");
        return f.isFile() && f.length() > 0 ? f : null;
    }
}
