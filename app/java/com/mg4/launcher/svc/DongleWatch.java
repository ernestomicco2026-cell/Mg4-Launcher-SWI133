package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Riavvio automatico dei dongle CarPlay / Android Auto wireless che a volte non partono finché non si tolgono e
 * rimettono (es. "AutoKit-TBox Plus", "SmartBox").
 * <ul>
 *   <li>interviene solo con un dongle riconosciuto: produttore USB dei dongle (Carlinkit 1314) oppure il dispositivo USB
 *   il cui numero di serie ha già dato una sessione con un nome da dongle (il launcher lo impara da solo). Un iPhone o
 *   un telefono Android collegato via cavo e le chiavette di musica non vengono mai toccati;</li>
 *   <li>solo se dopo N secondi dall'accensione (20 di default) non è partita nessuna sessione CarPlay/AA;</li>
 *   <li>al massimo 2 tentativi per accensione: reset della porta USB del dongle (come toglierlo e rimetterlo), poi
 *   riavvio del servizio CarPlay/AA (Allgo);</li>
 *   <li>0.9.8 (come la SWI68): niente più riconoscimento "automatico" di qualsiasi Apple/Android: i dongle wireless si presentano sull'USB
 *   come un iPhone (05ac:12a8) o in modalità accessorio Android (18d1:2d00), proprio come i telefoni col cavo, che venivano
 *   resettati. Ora conta come dongle solo: produttore dei dongle (Carlinkit), nome USB da dongle (es. "smartlinkBox",
 *   "OKBox") oppure numero di serie che ha già dato una sessione con un nome da dongle. Apple, modalità accessorio Android
 *   e dispositivi che si presentano come telefono (nome, classe) sono esclusi in tutti gli altri casi, e anche i numeri di
 *   serie che hanno dato una sessione col nome di un telefono. Con un telefono escluso collegato non si tocca
 *   nulla sull'USB. Il motivo di ogni esclusione va nella diagnostica (dongle.escluso);</li>
 *   <li>mai durante una chiamata.</li>
 *   <li>1.0.6 (issue #43): interviene solo con un dongle già riconosciuto in una sessione passata (VID:PID salvato) e
 *   collegato adesso; senza dongle visibile non fa niente; il secondo tentativo riavvia TUTTI i servizi AllGo e
 *   controlla che ripartano; mai con un telefono collegato, mai sulla porta con una chiavetta (anche solo vista).</li>
 * </ul>
 * Ogni passo va nella diagnostica (chiavi dongle.*).
 */
public final class DongleWatch {
    private static final String PREFS = "dongle";
    /** Produttori USB dei dongle (non di telefoni): Carlinkit / "Magic Communication Tec.". */
    private static final int[] DONGLE_VIDS = {0x1314};
    private static final int APPLE_VID = 0x05AC, GOOGLE_AOAP_VID = 0x18D1;
    /** Nomi con cui i dongle si presentano a CarPlay/AA (in minuscolo, senza spazi). */
    private static final String[] DONGLE_NAMES = {"autokit", "carlinkit", "cpc200", "tbox", "smartbox", "ottocast",
            "u2w", "u2aw", "aawireless", "ma1", "carsifi", "cplay2air", "magicbox", "aibox", "wireless", "dongle", "adapter",
            "carplaybox", "playbox", "bmbox", "okbox", "smartlink", "carlink", "cpbox", "aabox", "linkbox", "aiplay",
            "vehiconn", "play2video", "carbox"};
    /** Nomi di telefoni (in minuscolo, senza spazi): USB o sessione con questi nomi = telefono, mai resettato. */
    private static final String[] PHONE_NAMES = {"iphone", "ipad", "pixel", "redmi", "xiaomi", "poco", "galaxy", "samsung",
            "oneplus", "huawei", "honor", "oppo", "vivo", "realme", "motorola", "moto", "nokia", "xperia", "sony", "nothing",
            "phone", "android"};
    private static final String[] ALLGO_PACKAGES = {"com.allgo.carplay.service", "com.allgo.app.androidauto", "com.allgo.rui"};

    private static DongleWatch instance;

    public static synchronized DongleWatch get(Context c, MediaClient media) {
        if (instance == null) instance = new DongleWatch(c.getApplicationContext(), media);
        return instance;
    }

    /** Istanza già creata dal launcher (null prima dell'avvio dei servizi). */
    public static synchronized DongleWatch peek() {
        return instance;
    }

    private final Context ctx;
    private final MediaClient media;
    private final SharedPreferences sp;
    private final Handler bg;
    private boolean started;

    private DongleWatch(Context c, MediaClient media) {
        ctx = c;
        this.media = media;
        sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        HandlerThread th = new HandlerThread("mg4-dongle");
        th.start();
        bg = new Handler(th.getLooper());
    }

    // ---- impostazioni ----------------------------------------------------------------------

    public boolean enabled() {
        return sp.getBoolean("attivo", true);
    }

    public void setEnabled(boolean on) {
        sp.edit().putBoolean("attivo", on).apply();
        Diag.put("dongle.automatico", on ? "acceso" : "spento");
    }

    /** Secondi dall'accensione prima di intervenire. */
    public int delaySeconds() {
        return sp.getInt("secondi", 20);
    }

    public void setDelaySeconds(int s) {
        sp.edit().putInt("secondi", s).apply();
    }

    // ---- avvio -----------------------------------------------------------------------------

    public synchronized void start() {
        if (started) return;
        started = true;
        bg.post(this::logUsb);
        long wait = delaySeconds() * 1000L - SystemClock.elapsedRealtime();
        bg.postDelayed(this::check, Math.max(3000, wait));
        bg.postDelayed(watch, 15_000);
    }

    /** Tentativo 1 non prima di questi secondi dall'accensione (il dongle deve avere il tempo di partire). */
    private static final int FIRST_TRY_MIN_S = 40;
    private int watchTick;
    /** 1.0.6: chiavetta vista dopo l'avvio (SWI133: il dongle non si resetta più fino al prossimo avvio); servizi AllGo visti attivi. */
    private volatile boolean storageSeen, allgoSeen;

    /**
     * Ogni 5 s: chiavetta USB e servizi AllGo (SWI133: nessuna porta media da imparare); ogni 20 s: impara il dongle durante le
     * sessioni (anche quelle che partono tardi) e aggiorna l'elenco USB se cambia.
     */
    private final Runnable watch = new Runnable() {
        @Override
        public void run() {
            try {
                if (!storageSeen && storagePresent()) {
                    storageSeen = true;
                    Diag.put("dongle.chiavetta", "chiavetta USB vista: in questa accensione il dongle non si resetta");
                    BootLog.mark(ctx, "chiavetta", null);
                }
                if (!allgoSeen && SystemClock.elapsedRealtime() < 10 * 60_000L) {
                    String run = allgoRunning();
                    if (run.contains("RemoteUIService") && (run.contains("CarPlayService") || run.contains("AndroidAuto"))) {
                        allgoSeen = true;
                        BootLog.mark(ctx, "allgo", null);
                    }
                }
                if (watchTick++ % 4 == 0) {
                    learn();
                    logUsb();
                }
            } catch (Throwable e) {
                Diag.error("dongle.watch", e.toString());
            }
            bg.postDelayed(this, 5_000);
        }
    };

    private int bootCount() {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Tentativi già fatti in questa accensione (resta giusto anche se il launcher si riavvia). */
    private int attempts() {
        return sp.getInt("boot", -1) == bootCount() ? sp.getInt("tentativi", 0) : 0;
    }

    private void setAttempts(int n) {
        sp.edit().putInt("boot", bootCount()).putInt("tentativi", n).apply();
    }

    private boolean sessionActive() {
        return media.last().deviceType != 0;
    }

    /**
     * 1.0.6 (issue #43): si interviene SOLO se un dongle già riconosciuto (visto in una sessione passata, VID:PID salvato)
     * è collegato ADESSO e la sessione non parte. "Dongle non visibile" = niente. Chi non ha mai avuto un dongle
     * riconosciuto: la funzione resta spenta da sola. Mai toccare la porta con una chiavetta (anche solo vista dopo
     * l'avvio); mai fermare i servizi con un telefono collegato (cavo o Bluetooth) o in chiamata. Ogni decisione va in
     * dongle.esito con il motivo.
     */
    private void check() {
        if (sessionActive()) {
            Diag.put("dongle.esito", attempts() == 0 ? "sessione partita da sola" : "sessione partita dopo " + attempts() + " tentativi");
            learn();
            return;
        }
        if (!enabled()) {
            Diag.put("dongle.esito", "nessuna sessione, riavvio automatico spento");
            return;
        }
        if (knownIds().isEmpty() && learned().isEmpty()) {
            Diag.put("dongle.esito", "nessuna sessione · mai visto un dongle riconosciuto in una sessione: funzione spenta da sola, non faccio niente");
            return;
        }
        int n = attempts();
        if (n >= 2) {
            Diag.put("dongle.esito", "nessuna sessione dopo 2 tentativi: niente altro in questa accensione");
            return;
        }
        if (inCall()) {
            Diag.put("dongle.esito", "chiamata in corso: rimando di 15 s");
            bg.postDelayed(this::check, 15_000);
            return;
        }
        UsbDevice d = findDongle();
        if (d == null) {
            String excl = excluded();
            Diag.put("dongle.esito", "nessuna sessione, ma nessun dongle riconosciuto collegato adesso"
                    + (excl.isEmpty() ? "" : " (collegato solo " + excl + ")") + ": non faccio niente");
            return;
        }
        String phone = phoneConnected();
        if (n == 0) {
            String excl = excluded();
            if (!excl.isEmpty()) {
                Diag.put("dongle.esito", "dongle " + describe(d) + " collegato, ma c'è anche un telefono col cavo (" + excl + "): non tocco niente");
                return;
            }
            long left = FIRST_TRY_MIN_S * 1000L - SystemClock.elapsedRealtime();
            if (left > 0) {
                Diag.put("dongle.esito", "dongle " + describe(d) + " collegato, nessuna sessione: aspetto ancora " + (left / 1000) + " s");
                bg.postDelayed(this::check, left);
                return;
            }
            setAttempts(1);
            // SWI133: nessuna riaccensione della porta media (mai verificata su questo hub): solo il reset USB del dongle
            String why = storagePresent() ? "chiavetta USB collegata" : storageSeen ? "chiavetta USB vista dopo l'avvio" : null;
            StringBuilder sb = new StringBuilder("dongle " + describe(d) + " collegato e nessuna sessione · ");
            if (why != null) sb.append("reset USB non fatto (").append(why).append(")");
            else sb.append("reset USB del solo dongle: ").append(resetUsb(d) ? "fatto" : "non riuscito");
            Diag.put("dongle.tentativo1", sb.toString());
            Diag.put("dongle.esito", "tentativo 1 fatto: ricontrollo tra " + Math.max(delaySeconds(), 30) + " s");
            BootLog.mark(ctx, "dongle", "tentativo 1");
            if (why == null) checkReappear("dongle.dopoReset", d.getVendorId(), d.getProductId());
            bg.postDelayed(this::check, Math.max(delaySeconds(), 30) * 1000L);
            return;
        }
        if (phone != null) {
            Diag.put("dongle.esito", "dongle " + describe(d) + " collegato, nessuna sessione dopo il tentativo 1, ma c'è un telefono collegato ("
                    + phone + "): i servizi CarPlay/Android Auto non si fermano");
            return;
        }
        setAttempts(2);
        Diag.put("dongle.tentativo2", "riavvio di tutti i servizi CarPlay/Android Auto: " + restartAllgo());
        BootLog.mark(ctx, "dongle", "tentativo 2");
        bg.postDelayed(this::check, Math.max(delaySeconds(), 30) * 1000L);
    }

    /** Telefono collegato: col cavo (escluso dal riavvio) o in Bluetooth. null se nessuno (1.0.6). */
    private String phoneConnected() {
        if (!excluded().isEmpty()) return "col cavo USB";
        // il servizio telefono del launcher è collegato solo con il riquadro Telefono: qui lo si apre per qualche secondo
        PhoneClient pc = null;
        try {
            pc = PhoneClient.get(ctx);
            pc.start(null);
            for (int i = 0; i < 20 && !pc.serviceConnected(); i++) SystemClock.sleep(250);
            SystemClock.sleep(800);   // lettura dello stato dopo il collegamento
            if (pc.phoneConnected()) return "Bluetooth";
            if (!pc.serviceConnected()) Diag.put("dongle.telefono", "servizio telefono non raggiungibile: Bluetooth non controllato");
        } catch (Throwable e) {
            Diag.put("dongle.telefono", "controllo Bluetooth non riuscito: " + e.getClass().getSimpleName());
        } finally {
            if (pc != null) try {
                pc.stop(null);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** Pulsante "Riavvia CarPlay/AA": reset del dongle se riconosciuto, poi riavvio del servizio. */
    public void restartNow(Runnable onDone) {
        bg.post(() -> {
            String res;
            if (inCall()) {
                res = "chiamata in corso: non riavviato";
            } else {
                UsbDevice d = findDongle();
                StringBuilder sb = new StringBuilder();
                String excl = excluded();
                boolean phoneOnly = d == null && !excl.isEmpty();
                boolean stick = storagePresent() || storageSeen;
                if (phoneOnly) sb.append("collegato solo ").append(excl).append(": USB non toccata; ");
                else if (d != null && stick) sb.append("chiavetta USB vista: reset USB non fatto; ");
                else if (d != null) sb.append("reset USB ").append(describe(d)).append(": ").append(resetUsb(d) ? "fatto" : "non riuscito").append("; ");
                sb.append("servizio: ").append(restartAllgo());
                res = sb.toString();
            }
            Diag.put("dongle.manuale", res);
            if (onDone != null) new Handler(android.os.Looper.getMainLooper()).post(onDone);
        });
    }

    public boolean inCall() {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int m = am == null ? 0 : am.getMode();
            if (m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION || m == AudioManager.MODE_RINGTONE) return true;
        } catch (Throwable ignored) {
        }
        return media.remoteCallActive();
    }

    // ---- riconoscimento --------------------------------------------------------------------

    private static boolean dongleName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty() || n.contains("iphone") || n.contains("ipad")) return false;
        for (String d : DONGLE_NAMES) if (n.contains(d)) return true;
        return false;
    }

    private Set<String> learned() {
        return new HashSet<>(sp.getStringSet("seriali", new HashSet<>()));
    }

    private Set<String> phones() {
        return new HashSet<>(sp.getStringSet("telefoni", new HashSet<>()));
    }

    /**
     * Sessione attiva: con un nome da dongle si ricorda il numero di serie dei dispositivi USB Apple/AOAP/dongle collegati;
     * con il nome di un telefono (es. "iPhone 16 Pro max", "REDMI Note…") quel numero di serie diventa un telefono, che non
     * si resetta mai. Vale l'ultima sessione vista.
     */
    private void learn() {
        MediaClient.State m = media.last();
        if (m.deviceType == 0) return;
        boolean dongle = dongleName(m.deviceName);
        if (dongle && !usesDongle()) {
            sp.edit().putBoolean("usaDongle", true).apply();
            Diag.put("dongle.inUso", "sessione con " + m.deviceName + ": quest'auto usa un dongle");
        }
        Set<String> s = learned(), ph = phones(), ids = knownIds();
        boolean changed = false;
        for (UsbDevice d : devices()) {
            int vid = d.getVendorId();
            if (vid != APPLE_VID && vid != GOOGLE_AOAP_VID && !isDongleVid(vid) && !dongleName(product(d))) continue;
            String serial = serial(d);
            if (serial.isEmpty()) {
                Diag.putQuiet("dongle.seriale", describe(d) + ": numero di serie non leggibile");
                if (dongle && !generic(d)) changed |= ids.add(idOf(d));   // VID:PID proprio dei dongle: basta quello
                continue;
            }
            if (dongle) changed |= s.add(serial) | ph.remove(serial);
            else changed |= ph.add(serial) | s.remove(serial);
            if (dongle) changed |= ids.add(idOf(d));
        }
        if (changed) {
            sp.edit().putStringSet("seriali", s).putStringSet("telefoni", ph).putStringSet("dongleId", ids).apply();
            if (dongle) Diag.put("dongle.riconosciuto", m.deviceName + " (" + s.size() + " numeri di serie memorizzati)");
            // 1.0.4: niente nome del telefono nel log (dato personale)
            else Diag.put("dongle.telefonoRiconosciuto", "sessione con un telefono: il dispositivo USB è un telefono, non verrà mai resettato");
        }
    }

    // ---- chiavetta USB (SWI133: la porta media non si tocca mai, serve solo per non resettare il dongle) ----------

    private boolean usesDongle() {
        return sp.getBoolean("usaDongle", false);
    }

    /** C'è una chiavetta o un disco USB (classe mass storage) collegato. */
    private boolean storagePresent() {
        for (UsbDevice d : devices()) {
            if (systemDevice(d)) continue;
            if (d.getDeviceClass() == 8) return true;
            try {
                for (int i = 0; i < d.getInterfaceCount(); i++) if (d.getInterface(i).getInterfaceClass() == 8) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean isDongleVid(int vid) {
        for (int v : DONGLE_VIDS) if (v == vid) return true;
        return false;
    }

    /** VID:PID dei dongle visti in una sessione con un nome da dongle (1.0.6). */
    private Set<String> knownIds() {
        return new HashSet<>(sp.getStringSet("dongleId", new HashSet<>()));
    }

    private static String idOf(UsbDevice d) {
        return String.format(Locale.ROOT, "%04x:%04x", d.getVendorId(), d.getProductId());
    }

    /** VID:PID che usano anche i telefoni (Apple, modalità accessorio Android): da soli non bastano a dire "dongle". */
    private static boolean generic(UsbDevice d) {
        return d.getVendorId() == APPLE_VID || d.getVendorId() == GOOGLE_AOAP_VID;
    }

    /**
     * 1.0.6 (issue #43): solo un dongle GIÀ RICONOSCIUTO in una sessione passata e collegato adesso. Vale il VID:PID
     * salvato; per Apple e modalità accessorio Android (gli stessi dei telefoni) serve anche il numero di serie
     * salvato. Un numero di serie già visto in una sessione con un nome da dongle (versioni di prima) conta come
     * riconosciuto, e il suo VID:PID si salva. Un numero di serie visto col nome di un telefono non è mai un dongle.
     * Nome o produttore da dongle da soli NON bastano più.
     */
    private UsbDevice findDongle() {
        Set<String> s = learned(), ph = phones(), ids = knownIds();
        for (UsbDevice d : devices()) {
            if (systemDevice(d)) continue;
            String serial = serial(d);
            if (!serial.isEmpty() && ph.contains(serial)) continue;
            boolean serialKnown = !serial.isEmpty() && s.contains(serial);
            boolean idKnown = ids.contains(idOf(d));
            if (generic(d) ? serialKnown : idKnown || serialKnown) {
                if (!idKnown) {
                    ids.add(idOf(d));
                    sp.edit().putStringSet("dongleId", ids).apply();
                    Diag.put("dongle.riconosciuto", describe(d) + ": numero di serie già visto in una sessione con un dongle, VID:PID salvato");
                }
                return d;
            }
        }
        return null;
    }

    private static boolean phoneName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (n.isEmpty()) return false;
        for (String p : PHONE_NAMES) if (n.contains(p)) return true;
        return n.matches("sm[a-z]?\\d{3}[a-z0-9]{0,3}");   // modelli Samsung, es. "SM-S918B"
    }

    /** Il dispositivo si presenta come un telefono: nome (prodotto o produttore) o classe (comunicazioni, foto/MTP). */
    private static String phoneLike(UsbDevice d) {
        String p = product(d), m = "";
        try {
            m = d.getManufacturerName();
        } catch (Throwable ignored) {
        }
        if (phoneName(p)) return "nome \"" + p + "\"";
        if (phoneName(m)) return "produttore \"" + m + "\"";
        if (d.getDeviceClass() == 2) return "classe 2 (comunicazioni)";
        try {
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                int c = d.getInterface(i).getInterfaceClass();
                if (c == 6) return "interfaccia classe 6 (foto/MTP)";
                if (c == 2) return "interfaccia classe 2 (comunicazioni)";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * Dispositivi esclusi dal riavvio, con il motivo (scritti in dongle.escluso): Apple, modalità accessorio Android
     * (18d1:2d00–2d05), dispositivi che si presentano come telefono o già visti come telefono. "" se nessuno.
     */
    private String excluded() {
        Set<String> s = learned(), ph = phones();
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices()) {
            if (systemDevice(d) || isDongleVid(d.getVendorId()) || dongleName(product(d))) continue;
            String serial = serial(d);
            if (!serial.isEmpty() && s.contains(serial) && !ph.contains(serial)) continue;   // dongle già riconosciuto
            int vid = d.getVendorId(), pid = d.getProductId();
            String why = null;
            if (!serial.isEmpty() && ph.contains(serial)) why = "già visto in una sessione con il nome di un telefono";
            else if (vid == APPLE_VID) why = "Apple (05ac), non riconosciuto come dongle";
            else if (vid == GOOGLE_AOAP_VID && pid >= 0x2D00 && pid <= 0x2D05)
                why = "modalità accessorio Android (18d1:" + Integer.toHexString(pid) + "), non riconosciuto come dongle";
            else {
                String ph2 = phoneLike(d);
                if (ph2 != null) why = "si presenta come telefono (" + ph2 + ")";
            }
            if (why == null) continue;
            sb.append(sb.length() == 0 ? "" : "; ").append(describe(d)).append(" escluso: ").append(why);
        }
        String v = sb.toString();
        if (!v.isEmpty()) Diag.put("dongle.escluso", v);
        return v;
    }

    private static String product(UsbDevice d) {
        try {
            String p = d.getProductName();
            return p == null ? "" : p;
        } catch (Throwable e) {
            return "";
        }
    }

    /** Dispositivi di sistema sempre collegati (root hub, modem, STM32) o hub: mai dongle né telefoni. */
    private static boolean systemDevice(UsbDevice d) {
        int v = d.getVendorId();
        return d.getDeviceClass() == 9 || v == 0x1D6B || v == 0x05C6 || v == 0x0483;
    }

    private Iterable<UsbDevice> devices() {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um != null) return um.getDeviceList().values();
        } catch (Throwable e) {
            Diag.error("dongle.usb", e.toString());
        }
        return new java.util.ArrayList<>();
    }

    private String serial(UsbDevice d) {
        try {
            grant(d);
            String s = d.getSerialNumber();
            return s == null ? "" : s;
        } catch (Throwable e) {
            return "";
        }
    }

    private static String describe(UsbDevice d) {
        String p = "";
        try {
            p = d.getProductName();
        } catch (Throwable ignored) {
        }
        return String.format(Locale.ROOT, "%04x:%04x %s", d.getVendorId(), d.getProductId(), p == null ? "" : p).trim();
    }

    private void logUsb() {
        StringBuilder sb = new StringBuilder();
        for (UsbDevice d : devices()) {
            String m = "";
            try {
                m = d.getManufacturerName();
            } catch (Throwable ignored) {
            }
            sb.append(sb.length() == 0 ? "" : " | ").append(describe(d)).append(m == null || m.isEmpty() ? "" : " (" + m + ")")
                    .append(" classe ").append(d.getDeviceClass());
        }
        Diag.put("usb.dispositivi", sb.length() == 0 ? "nessuno" : sb.toString());
    }

    // ---- azioni ----------------------------------------------------------------------------

    /** Permesso USB concesso dal launcher stesso (MANAGE_USB, firma di sistema), senza finestra di conferma. */
    private void grant(UsbDevice d) {
        try {
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            if (um == null || um.hasPermission(d)) return;
            UsbManager.class.getMethod("grantPermission", UsbDevice.class).invoke(um, d);
        } catch (Throwable ignored) {
        }
    }

    /** Dopo un reset o una riaccensione della porta: registra se il dongle ricompare sul bus (1.x). */
    private void checkReappear(String tag, int vid, int pid) {
        bg.postDelayed(() -> {
            try {
                UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
                boolean found = false, any = false;
                for (UsbDevice x : um.getDeviceList().values()) {
                    if (isDongleVid(x.getVendorId())) any = true;
                    if (vid != 0 && x.getVendorId() == vid && x.getProductId() == pid) found = true;
                }
                String esito = vid != 0 ? (found ? "ricomparso" : "NON ricomparso") : (any ? "un dongle è visibile" : "nessun dongle visibile");
                Diag.put(tag, esito + " 8 s dopo il reset");
            } catch (Throwable e) {
                Diag.error(tag, e.toString());
            }
        }, 8000);
    }

    /** Reset della porta USB (come staccare e riattaccare), lo stesso che usa il servizio Allgo. */
    private boolean resetUsb(UsbDevice d) {
        UsbDeviceConnection c = null;
        try {
            grant(d);
            UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
            c = um.openDevice(d);
            if (c == null) {
                Diag.error("dongle.reset", "openDevice non riuscito (permesso?)");
                return false;
            }
            Object r = UsbDeviceConnection.class.getMethod("resetDevice").invoke(c);
            return !(r instanceof Boolean) || (Boolean) r;
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            Diag.error("dongle.reset", t.toString());
            return false;
        } finally {
            if (c != null) try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 1.0.6 (issue #43): i servizi che si fermano e si riavviano TUTTI (prima ne ripartiva solo uno e Android Auto restava
     * spento fino al riavvio). Componenti letti dal manifest degli APK AllGo del firmware R69 (tutti esportati, gli
     * stessi che avviano i loro ricevitori BOOT_COMPLETED).
     */
    private static final String[][] ALLGO_SERVICES = {
            {"com.allgo.rui", "com.allgo.rui.RemoteUIService", "com.allgo.rui.RemoteUIService"},
            {"com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayService", "com.allgo.app.carplay.CarPlayService"},
            {"com.allgo.app.androidauto", "com.allgo.app.androidauto.AndroidAutoService", "com.allgo.app.androidauto.AndroidAutoService"},
            {"com.allgo.app.androidauto", "com.allgo.app.androidauto.AndroidAutoMainService", "com.allgo.app.androidauto.AndroidAutoMainService"}};

    private static String shortName(String cls) {
        return cls.substring(cls.lastIndexOf('.') + 1);
    }

    /** Servizi AllGo attivi adesso (getRunningServices, con REAL_GET_TASKS si vedono anche quelli delle altre app). */
    private String allgoRunning() {
        StringBuilder sb = new StringBuilder();
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            @SuppressWarnings("deprecation")
            java.util.List<android.app.ActivityManager.RunningServiceInfo> l = am.getRunningServices(300);
            for (android.app.ActivityManager.RunningServiceInfo i : l) {
                if (i.service == null || !i.service.getPackageName().startsWith("com.allgo.")) continue;
                for (String[] sv : ALLGO_SERVICES) {
                    if (sv[1].equals(i.service.getClassName()) && sb.indexOf(shortName(sv[1])) < 0)
                        sb.append(sb.length() == 0 ? "" : ",").append(shortName(sv[1]));
                }
            }
        } catch (Throwable e) {
            return "?";
        }
        return sb.toString();
    }

    private boolean installed(String pkg) {
        try {
            ctx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Ferma i servizi CarPlay/AA (com.allgo.carplay.service, com.allgo.app.androidauto, com.allgo.rui) e li riavvia
     * TUTTI (RemoteUIService, CarPlayService, AndroidAutoService, AndroidAutoMainService); dopo 5 s controlla che siano
     * ripartiti davvero. Se uno non riparte: solo log, niente altro.
     */
    private String restartAllgo() {
        StringBuilder sb = new StringBuilder();
        sb.append("attivi prima [").append(allgoRunning()).append("] · ");
        // 0.3.6: CarPlayService e AndroidAutoMainService si avviano solo con BIND_TELECOM_CONNECTION_SERVICE. Senza il
        // permesso non si ferma niente, altrimenti CarPlay/AA resterebbero spenti fino al prossimo avvio
        if (ctx.checkSelfPermission("android.permission.BIND_TELECOM_CONNECTION_SERVICE") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return sb + "permesso BIND_TELECOM_CONNECTION_SERVICE mancante: niente fermato (i servizi non ripartirebbero)";
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            java.lang.reflect.Method fs = android.app.ActivityManager.class.getMethod("forceStopPackage", String.class);
            for (String p : ALLGO_PACKAGES) {
                if (!installed(p)) continue;
                fs.invoke(am, p);
                sb.append(p.substring(p.lastIndexOf('.') + 1)).append(" fermato; ");
            }
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            return sb + "arresto non riuscito: " + t + " (niente riavviato)";
        }
        SystemClock.sleep(1500);
        java.util.List<String> started = new java.util.ArrayList<>();
        for (String[] sv : ALLGO_SERVICES) {
            if (!installed(sv[0])) continue;
            try {
                ComponentName cn = ctx.startService(new Intent(sv[2]).setComponent(new ComponentName(sv[0], sv[1])));
                sb.append(shortName(sv[1])).append(cn != null ? " avviato; " : " non trovato; ");
                if (cn != null) started.add(shortName(sv[1]));
            } catch (Throwable e) {
                sb.append(shortName(sv[1])).append(" non avviato (").append(e.getClass().getSimpleName()).append("); ");
            }
        }
        sb.append(restartProjection()).append(" · ");
        SystemClock.sleep(5000);
        String run = allgoRunning();
        StringBuilder missing = new StringBuilder();
        for (String[] sv : ALLGO_SERVICES) {
            if (!installed(sv[0])) continue;
            String n = shortName(sv[1]);
            if (!run.contains(n)) missing.append(missing.length() == 0 ? "" : ", ").append(n);
        }
        sb.append("dopo 5 s attivi [").append(run).append("]");
        if ("?".equals(run)) sb.append(" · controllo non riuscito");
        else if (missing.length() > 0) {
            sb.append(" · NON ripartiti: ").append(missing).append(" (niente altro)");
            Diag.put("dongle.servizi", "non ripartiti dopo il riavvio: " + missing);
        } else sb.append(" · tutti ripartiti");
        return sb.toString();
    }

    /** SWI133: servizio di proiezione MG (cpaaservice_my24_P), tramite fra RemoteUIService e le app dell'auto. */
    private static final String PROJECTION_PKG = "com.saicmotor.projection";
    private static final String PROJECTION_CLS = "com.saicmotor.projection.service.SaicProjectionServer";

    /**
     * SWI133: cpaaservice si collega a RemoteUIService una volta sola (all'avvio) e non si ricollega a un
     * RemoteUIService riavviato. Dopo il riavvio di Allgo si riavvia anche lui, così si ricollega al nuovo e le app MG
     * (CarPlay/AA nella barra e nelle schermate originali) continuano a funzionare.
     */
    private String restartProjection() {
        try {
            ctx.getPackageManager().getPackageInfo(PROJECTION_PKG, 0);
        } catch (Exception e) {
            return "";
        }
        SystemClock.sleep(1500);
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            android.app.ActivityManager.class.getMethod("forceStopPackage", String.class).invoke(am, PROJECTION_PKG);
            ctx.startService(new Intent().setComponent(new ComponentName(PROJECTION_PKG, PROJECTION_CLS)));
            return "; cpaaservice riavviato";
        } catch (Throwable e) {
            Throwable t = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            return "; cpaaservice: " + t.getClass().getSimpleName();
        }
    }
}
